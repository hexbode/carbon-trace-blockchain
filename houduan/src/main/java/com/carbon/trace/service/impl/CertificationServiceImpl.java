package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.chain.BlockTransaction;
import com.carbon.trace.chain.ChainStore;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.BlockchainUtil;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.entity.CertificationRecord;
import com.carbon.trace.entity.ReportRecord;
import com.carbon.trace.mapper.CertificationRecordMapper;
import com.carbon.trace.service.BlockchainService;
import com.carbon.trace.service.CertificationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 认证记录服务实现
 */
@Service
public class CertificationServiceImpl implements CertificationService {

    private static final String NOT_MATCH_MSG = "未在区块链存证中匹配到该哈希，请核对后重试";
    private static final String REVOKED_MSG = "该证书已被监管机构撤销，认证已失效";

    private final CertificationRecordMapper certificationRecordMapper;
    private final ChainStore chainStore;
    private final BlockchainService blockchainService;

    public CertificationServiceImpl(CertificationRecordMapper certificationRecordMapper,
                                    ChainStore chainStore,
                                    BlockchainService blockchainService) {
        this.certificationRecordMapper = certificationRecordMapper;
        this.chainStore = chainStore;
        this.blockchainService = blockchainService;
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
        fillChainInfo(list);
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
        // 以区块链存证为准：先在链上交易中定位该电子防伪码，再由排放编号关联认证记录
        BlockTransaction chainTx = findChainTransaction(fingerprint.trim());
        if (chainTx == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "未查询到该电子防伪码对应的认证信息");
        }
        CertificationRecord record = certificationRecordMapper.selectOne(
                new LambdaQueryWrapper<CertificationRecord>()
                        .eq(CertificationRecord::getEmissionId, chainTx.getEmissionId())
                        .last("limit 1"));
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "未查询到该电子防伪码对应的认证信息");
        }
        // 防伪码与链上哈希均以链上存证为准展示，业务表被改动不影响前端取值
        applyChainInfo(record, chainTx);
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
        return record;
    }

    @Override
    public Map<String, Object> verify(String hash) {
        Map<String, Object> result = new LinkedHashMap<>();
        // 以区块链存证为准：先按电子防伪码或交易哈希在链上交易中定位
        BlockTransaction chainTx = findChainTransaction(hash);
        if (chainTx == null) {
            result.put("valid", false);
            result.put("msg", NOT_MATCH_MSG);
            result.put("cert", null);
            return result;
        }
        // 链上命中后仅关联认证记录判断撤销等业务状态，不使用其电子防伪码字段参与匹配
        CertificationRecord cert = certificationRecordMapper.selectOne(
                new LambdaQueryWrapper<CertificationRecord>()
                        .eq(CertificationRecord::getEmissionId, chainTx.getEmissionId())
                        .last("limit 1"));
        if (cert != null && "revoked".equals(cert.getStatus())) {
            // 已撤销的证书不再视为有效，但仍返回证书信息供前端展示撤销状态
            result.put("valid", false);
            result.put("msg", REVOKED_MSG);
            result.put("cert", cert);
            return result;
        }
        if (cert != null) {
            // 防伪码与链上哈希均以链上存证为准展示，业务表被改动不影响前端取值
            applyChainInfo(cert, chainTx);
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

        CertificationRecord cert = certificationRecordMapper.selectOne(
                new LambdaQueryWrapper<CertificationRecord>()
                        .eq(CertificationRecord::getEmissionId, report.getEmissionId())
                        .last("limit 1"));
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

        // 组装待上链的交易（每 4 笔打包成一个区块）
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("txHash", BlockchainUtil.generateTxHash(txSnapshot(report)));
        tx.put("emissionId", report.getEmissionId());
        tx.put("enterpriseName", report.getEnterpriseName());
        tx.put("period", report.getPeriod());
        tx.put("amount", report.getAmount());
        tx.put("fingerprint", report.getFingerprint());
        tx.put("auditor", auditor);

        if (create) {
            certificationRecordMapper.insert(cert);
        } else {
            certificationRecordMapper.updateById(cert);
        }
        // 链数据保存在文件中，不受数据库事务保护，必须等事务提交后再上链，
        // 否则事务回滚（如并发死锁）时链上会残留没有认证记录的孤儿交易
        appendAfterCommit(tx);
        return cert;
    }

    /** 事务提交后再写入链上，保证「链上有存证」与「库中有认证记录」一致 */
    private void appendAfterCommit(Map<String, Object> tx) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    blockchainService.appendTransaction(tx);
                }
            });
        } else {
            blockchainService.appendTransaction(tx);
        }
    }

    private Map<String, Object> txSnapshot(ReportRecord report) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("emissionId", report.getEmissionId());
        snapshot.put("enterpriseName", report.getEnterpriseName());
        snapshot.put("period", report.getPeriod());
        snapshot.put("amount", report.getAmount());
        snapshot.put("fingerprint", report.getFingerprint());
        snapshot.put("auditor", report.getAuditor());
        return snapshot;
    }

    /**
     * 在链上交易中按电子防伪码或交易哈希定位存证记录
     * （忽略大小写、忽略 0x 前缀）。链上数据是验证的唯一依据。
     */
    private BlockTransaction findChainTransaction(String input) {
        return chainStore.findByCodeOrHash(input);
    }

    /** 用链上交易回填单条认证记录的防伪码、交易哈希与所在区块 */
    private void applyChainInfo(CertificationRecord record, BlockTransaction chainTx) {
        if (record == null || chainTx == null) {
            return;
        }
        record.setFingerprint(chainTx.getFingerprint());
        record.setTxHash(chainTx.getTxHash());
        record.setBlockIndex(chainTx.getBlockIndex());
    }

    /** 批量用链上交易回填认证记录的防伪码、交易哈希与所在区块（按排放编号关联） */
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
        Map<String, BlockTransaction> chainTxByEmission = chainStore.byEmissionIds(emissionIds);
        for (CertificationRecord record : records) {
            applyChainInfo(record, chainTxByEmission.get(record.getEmissionId()));
        }
    }
}
