package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.chain.ChainGateway;
import com.carbon.trace.chain.model.ChainCertStatus;
import com.carbon.trace.chain.model.ChainCertificate;
import com.carbon.trace.chain.model.CertifyCommand;
import com.carbon.trace.chain.webase.ChainTxLocator;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.entity.CertificationRecord;
import com.carbon.trace.entity.ReportRecord;
import com.carbon.trace.mapper.CertificationRecordMapper;
import com.carbon.trace.service.CertificationService;
import com.carbon.trace.service.ChainSyncService;
import com.carbon.trace.service.ComplianceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 认证记录服务实现。
 *
 * 核验以链上存证为唯一依据：先按电子防伪码或交易哈希定位链上凭证，再由排放编号关联认证记录；
 * 证书状态（有效 / 已撤销 / 已作废）以链上状态为准，业务库只提供审核意见等展示字段。
 */
@Service
public class CertificationServiceImpl implements CertificationService {

    private static final Logger log = LoggerFactory.getLogger(CertificationServiceImpl.class);

    private static final String NOT_MATCH_MSG = "未在区块链存证中匹配到该哈希，请核对后重试";
    private static final String REVOKED_MSG = "该证书已被监管机构撤销，认证已失效";
    private static final String INVALIDATED_MSG = "该认证存证已作废：对应的排放记录已被删除";

    private final CertificationRecordMapper certificationRecordMapper;
    private final ChainGateway chainGateway;
    private final ChainSyncService chainSyncService;
    private final ComplianceService complianceService;
    private final ChainTxLocator txLocator;

    public CertificationServiceImpl(CertificationRecordMapper certificationRecordMapper,
                                    ChainGateway chainGateway,
                                    ChainSyncService chainSyncService,
                                    ComplianceService complianceService,
                                    ChainTxLocator txLocator) {
        this.certificationRecordMapper = certificationRecordMapper;
        this.chainGateway = chainGateway;
        this.chainSyncService = chainSyncService;
        this.complianceService = complianceService;
        this.txLocator = txLocator;
    }

    @Override
    public List<CertificationRecord> list(String enterpriseId, String status, String keyword) {
        LambdaQueryWrapper<CertificationRecord> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(enterpriseId)) {
            wrapper.eq(CertificationRecord::getEnterpriseId, enterpriseId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(CertificationRecord::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(CertificationRecord::getId, keyword)
                    .or().like(CertificationRecord::getEnterpriseName, keyword)
                    .or().like(CertificationRecord::getAuditor, keyword));
        }
        wrapper.orderByDesc(CertificationRecord::getCertifiedAt);
        List<CertificationRecord> list = certificationRecordMapper.selectList(wrapper);
        // 列表不再逐条回链（52 条 × 2 秒 ≈ 112 秒，远超前端 15 秒超时）；
        // 链上交易哈希/区块高度仅从本地缓存回填，实时链上数据见单条详情与核验接口
        fillChainInfoFromCache(list);
        return list;
    }

    @Override
    public CertificationRecord getById(String id) {
        CertificationRecord record = certificationRecordMapper.selectById(id);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "认证记录不存在");
        }
        fillChainInfo(List.of(record));
        return record;
    }

    @Override
    public CertificationRecord getByFingerprint(String fingerprint) {
        if (!StringUtils.hasText(fingerprint)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "未查询到该电子防伪码对应的认证信息");
        }
        // 以链上存证为准：链上定位该电子防伪码，再由排放编号关联认证记录
        ChainCertificate chainCert = chainGateway.findByFingerprint(fingerprint.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "未查询到该电子防伪码对应的认证信息"));
        CertificationRecord record = findRecordByEmissionId(chainCert.getEmissionId());
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "未查询到该电子防伪码对应的认证信息");
        }
        // 防伪码与链上哈希均以链上存证为准展示，业务表被改动不影响前端取值
        applyChainInfo(record, chainCert);
        return record;
    }

    @Override
    public List<CertificationRecord> listSamples() {
        // 固定返回 3 张演示证书，接口无参数，公众无法借此遍历其他认证记录
        List<CertificationRecord> list = certificationRecordMapper.selectList(
                new LambdaQueryWrapper<CertificationRecord>()
                        .in(CertificationRecord::getId, List.of("CR000001", "CR000002", "CR000003"))
                        .orderByAsc(CertificationRecord::getId));
        fillChainInfo(list);
        return list;
    }

    @Override
    public CertificationRecord revoke(String id, Map<String, Object> body) {
        // 撤销原因必填，先做入参校验再校验记录是否存在
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        if (!StringUtils.hasText(reason)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "撤销原因不能为空");
        }
        CertificationRecord record = getById(id);
        String detail = body.get("detail") == null ? "" : String.valueOf(body.get("detail"));
        String revokeReason = StringUtils.hasText(detail) ? reason + "：" + detail : reason;
        record.setStatus("revoked");
        record.setRevokeReason(revokeReason);
        record.setRevokedAt(LocalDateTime.now());
        certificationRecordMapper.updateById(record);

        // 撤销同样要写链：链上状态变为 REVOKED 且为终局状态，核验以链上状态为准
        chainSyncService.afterCommit("撤销认证上链", record.getEnterpriseId(), record.getEnterpriseName(),
                record.getPeriod(), () -> chainGateway.revoke(id, revokeReason));
        return record;
    }

    @Override
    public Map<String, Object> verify(String hash) {
        Map<String, Object> result = new LinkedHashMap<>();
        // 以链上存证为准：先按电子防伪码或交易哈希定位
        ChainCertificate chainCert = chainGateway.findAny(hash).orElse(null);
        if (chainCert == null) {
            result.put("valid", false);
            result.put("msg", NOT_MATCH_MSG);
            result.put("cert", null);
            return result;
        }
        CertificationRecord cert = findRecordByEmissionId(chainCert.getEmissionId());

        // 链上状态优先：已撤销 / 已作废都不再视为有效，但仍返回证书信息供前端展示
        if (chainCert.getStatus() == ChainCertStatus.REVOKED) {
            if (cert != null) {
                cert.setStatus("revoked");
                if (StringUtils.hasText(chainCert.getReason())) {
                    cert.setRevokeReason(chainCert.getReason());
                }
                applyChainInfo(cert, chainCert);
            }
            result.put("valid", false);
            result.put("msg", REVOKED_MSG);
            result.put("cert", cert);
            return result;
        }
        if (chainCert.getStatus() == ChainCertStatus.INVALID) {
            if (cert != null) {
                applyChainInfo(cert, chainCert);
            }
            result.put("valid", false);
            result.put("msg", INVALIDATED_MSG);
            result.put("cert", cert);
            return result;
        }

        if (cert != null) {
            if ("revoked".equals(cert.getStatus())) {
                // 业务库与链上状态不一致时以链为准，并留下排查线索
                log.warn("业务库认证记录 {} 状态为 revoked，但链上存证为有效，已按链上状态返回。", cert.getId());
            }
            applyChainInfo(cert, chainCert);
        }
        result.put("valid", true);
        result.put("msg", "验证通过：该电子防伪码已在区块链存证中匹配成功");
        result.put("cert", cert);
        return result;
    }

    @Override
    @Transactional
    public CertificationRecord certify(ReportRecord report) {
        String auditor = StringUtils.hasText(report.getAuditor()) ? report.getAuditor() : UserContext.currentName();
        String opinion = report.getAuditOpinion();

        CertificationRecord cert = findRecordByEmissionId(report.getEmissionId());
        boolean create = cert == null;
        if (create) {
            cert = new CertificationRecord();
            // 编号统一：认证编号与排放记录/报告编号一致，三表同号
            cert.setId(report.getEmissionId());
            cert.setEmissionId(report.getEmissionId());
        }
        cert.setEnterpriseId(report.getEnterpriseId());
        cert.setEnterpriseName(report.getEnterpriseName());
        cert.setPeriod(report.getPeriod());
        cert.setAmount(report.getAmount());
        cert.setFingerprint(report.getFingerprint());
        cert.setAuditor(auditor);
        cert.setAuditOpinion(opinion);
        cert.setCertifiedAt(LocalDateTime.now());
        cert.setStatus("valid");
        cert.setRevokeReason(null);
        cert.setRevokedAt(null);

        if (create) {
            certificationRecordMapper.insert(cert);
        } else {
            certificationRecordMapper.updateById(cert);
        }

        // 合规判定由链上合约执行，判定结果作为合规快照随存证一起上链
        int complianceFlags = complianceService.flagsForCertify(report.getEmissionId());

        CertifyCommand command = new CertifyCommand();
        command.setCertId(cert.getId());
        command.setEnterpriseId(cert.getEnterpriseId());
        command.setEnterpriseName(cert.getEnterpriseName());
        command.setPeriod(cert.getPeriod());
        command.setAmount(cert.getAmount());
        command.setFingerprint(cert.getFingerprint());
        command.setAuditor(auditor);
        command.setComplianceFlags(complianceFlags);

        // 链上数据不受数据库事务保护，必须等事务提交后再写链，否则事务回滚会在链上留下孤儿存证
        chainSyncService.afterCommit("认证存证上链", cert.getEnterpriseId(), cert.getEnterpriseName(),
                cert.getPeriod(), () -> chainGateway.certify(command));
        return cert;
    }

    // ---------------- 内部工具 ----------------

    private CertificationRecord findRecordByEmissionId(String emissionId) {
        if (!StringUtils.hasText(emissionId)) {
            return null;
        }
        return certificationRecordMapper.selectOne(new LambdaQueryWrapper<CertificationRecord>()
                .eq(CertificationRecord::getEmissionId, emissionId)
                .last("limit 1"));
    }

    /** 用链上凭证回填单条认证记录的防伪码、交易哈希与所在区块 */
    private void applyChainInfo(CertificationRecord record, ChainCertificate chainCert) {
        if (record == null || chainCert == null || !chainCert.isExists()) {
            return;
        }
        if (StringUtils.hasText(chainCert.getFingerprint())) {
            record.setFingerprint(chainCert.getFingerprint());
        }
        record.setTxHash(chainCert.getTxHash());
        record.setBlockIndex(chainCert.getBlockIndex() == null ? null : chainCert.getBlockIndex().intValue());
    }

    /** 批量用链上凭证回填认证记录（按排放编号关联） */
    private void fillChainInfo(List<CertificationRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<String> emissionIds = records.stream()
                .map(CertificationRecord::getEmissionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (emissionIds.isEmpty()) {
            return;
        }
        Map<String, ChainCertificate> chainCertByEmission = chainGateway.certificatesByEmissionIds(emissionIds);
        for (CertificationRecord record : records) {
            applyChainInfo(record, chainCertByEmission.get(record.getEmissionId()));
        }
    }

    /** 列表场景：只从本地定位缓存回填交易哈希/区块高度，绝不回链 */
    private void fillChainInfoFromCache(List<CertificationRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        List<String> emissionIds = records.stream()
                .map(CertificationRecord::getEmissionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (emissionIds.isEmpty()) {
            return;
        }
        Map<String, ChainTxLocator.Entry> cached = txLocator.lookupAll(emissionIds);
        for (CertificationRecord record : records) {
            ChainTxLocator.Entry entry = cached.get(record.getEmissionId());
            if (entry == null) {
                continue;
            }
            record.setTxHash(entry.txHash());
            record.setBlockIndex(entry.blockNumber() == null ? null : entry.blockNumber().intValue());
        }
    }

    /** 用链上凭证回填单条认证记录的防伪码、交易哈希与所在区块 */
    private void applyChainInfo(CertificationRecord record, ChainCertificate chainCert) {
        if (record == null || chainCert == null || !chainCert.isExists()) {
            return;
        }
        if (StringUtils.hasText(chainCert.getFingerprint())) {
            record.setFingerprint(chainCert.getFingerprint());
        }
        record.setTxHash(chainCert.getTxHash());
        record.setBlockIndex(chainCert.getBlockIndex() == null ? null : chainCert.getBlockIndex().intValue());
    }
}
