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

    /** 示例证书固定 3 张；链上信息 60 秒内复用，避免公共查询页每次加载都等待链调用 */
    private static final long SAMPLE_CHAIN_CACHE_TTL_MS = 60_000L;
    private static final List<String> SAMPLE_CERT_IDS = List.of("CR000001", "CR000002", "CR000003");
    /** 示例证书链上信息缓存（进程内）；空表示尚未加载 */
    private volatile Map<String, ChainCertificate> sampleChainCache = Map.of();
    /** 最近一次缓存写入时间（毫秒），用于 TTL 判定 */
    private volatile long sampleChainCachedAt = 0L;

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

    /**
     * 按企业、状态、关键字过滤查询认证记录，按认证时间倒序。
     * 链上交易哈希与区块高度只从本地定位缓存回填，绝不逐条回链（列表逐条查询会拖垮前端超时）；
     * 实时链上数据由详情与核验接口提供。
     *
     * @param enterpriseId 企业编号，空则不筛选
     * @param status       认证状态（valid / revoked / invalid），空则不筛选
     * @param keyword      关键字，匹配认证编号、企业名称、审核人，空则不筛选
     * @return 认证记录列表
     */
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

    /**
     * 单条详情：查询后实时回填链上防伪码、交易哈希与所在区块。
     * 仅对单条记录回链，查询开销可接受；列表场景见 {@link #list}。
     *
     * @param id 认证编号（三表同号，与排放记录编号一致）
     * @return 认证记录（链上信息已回填）
     * @throws ApiException 404：认证记录不存在
     */
    @Override
    public CertificationRecord getById(String id) {
        CertificationRecord record = certificationRecordMapper.selectById(id);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "认证记录不存在");
        }
        fillChainInfo(List.of(record));
        return record;
    }

    /**
     * 公共端按电子防伪码精确查询（大小写敏感）。
     * 以链上存证为唯一依据：先由防伪码定位链上凭证，再用凭证中的排放编号关联本地认证记录；
     * 防伪码与链上哈希最终以链上值为准回填，业务库被改动不影响展示。
     *
     * @param fingerprint 12 位电子防伪码
     * @return 认证记录（已回填链上信息）
     * @throws ApiException 404：防伪码为空、链上未匹配或本地无对应记录
     */
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

    /**
     * 公共端固定示例证书：无参数、固定 3 张，公众无法借此遍历其他认证记录。
     * 这三条会实时回填链上信息（仅 3 条，开销可控）。
     *
     * @return 示例认证记录列表，按编号升序
     */
    @Override
    public List<CertificationRecord> listSamples() {
        // 固定返回 3 张演示证书，接口无参数，公众无法借此遍历其他认证记录
        List<CertificationRecord> list = certificationRecordMapper.selectList(
                new LambdaQueryWrapper<CertificationRecord>()
                        .in(CertificationRecord::getId, SAMPLE_CERT_IDS)
                        .orderByAsc(CertificationRecord::getId));
        fillSampleChainInfo(list);
        return list;
    }

    /**
     * 监管撤销认证：本地置为 revoked 并记录撤销原因与时间，随后写链把链上状态置为 REVOKED。
     * 撤销是终局状态（链上只追加不删除，撤销后不可恢复），核验一律以链上状态为准。
     * 方法本身未声明事务：若调用方处于事务中，写链由 {@link ChainSyncService#afterCommit} 推迟到提交后，
     * 失败按退避重试，最终失败生成 chain_failed 告警；无事务时立即写链。
     *
     * @param id   认证编号
     * @param body 请求体，reason 为撤销原因（必填），detail 为补充说明（可选）
     * @return 已更新（本地已撤销）的认证记录
     * @throws ApiException 400：撤销原因为空；404：认证记录不存在
     */
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
        // 先落本地（撤销原因/时间可供列表展示），再在事务提交后写链；链上状态是核验的唯一依据
        certificationRecordMapper.updateById(record);

        // 撤销同样要写链：链上状态变为 REVOKED 且为终局状态，核验以链上状态为准
        chainSyncService.afterCommit("撤销认证上链", record.getEnterpriseId(), record.getEnterpriseName(),
                record.getPeriod(), () -> chainGateway.revoke(id, revokeReason));
        return record;
    }

    /**
     * 核验电子防伪码或交易哈希，返回 {valid, msg, cert}。
     * 以链上存证为唯一依据：先按防伪码（大小写敏感）或交易哈希定位链上凭证，再由排放编号关联认证记录；
     * 链上状态优先——REVOKED / INVALID 均判为无效，但仍回传 cert 供前端展示失效原因。
     * 未命中时不抛异常，统一以 valid=false + msg 表达，便于公共端直接展示。
     *
     * @param hash 电子防伪码或交易哈希
     * @return {valid: Boolean, msg: String, cert: CertificationRecord（可能为 null）}
     */
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
            // 作废由「删除排放记录」触发：链上只把存证标记为 INVALID，业务库认证记录已被删除，
            // 因此 cert 可能为 null，前端仅能展示链上失效结论
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

    /**
     * 认证通过：生成或更新认证记录（编号与排放记录、报告记录一致，即三表同号），并在事务提交后写链。
     * 已存在认证记录时复用原编号更新（例如驳回后重新申请再通过），不新建编号；
     * 合规位掩码由链上合约判定，作为合规快照随存证一起上链。
     * 链上写入注册在事务提交后执行，事务回滚不会在链上留下孤儿存证；
     * 写链失败按退避重试，最终失败生成 chain_failed 告警，本地事务不受影响。
     *
     * @param report 已审核通过的报告记录（需含 emissionId、企业、报告期、排放量、防伪码、审核人）
     * @return 已落库的认证记录（链上凭证可能在提交后才写入）
     * @throws RuntimeException 本地落库失败时向上抛出并触发事务回滚
     */
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
            // 已有认证记录（如驳回后重新申请）：沿用原编号与防伪码更新，保证三表同号不被破坏
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

    /** 按排放编号反查认证记录（排放编号为空返回 null）；三表同号因此等价于按记录主键查找 */
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

    /**
     * 示例证书的链上信息回填（带 60 秒进程内缓存）。
     *
     * 单次链读约 2 秒，3 张示例证书即使并行也要约 2 秒；示例编号固定不变，
     * 其防伪码/交易哈希/区块高度属于不可变展示字段，因此短 TTL 缓存不会影响状态核验：
     * 真正的证书状态查询（by-fingerprint / 监管端列表）仍实时回链。
     *
     * 并发保护：多个请求同时过期时只允许一个线程刷新，其余线程复用刷新结果。
     *
     * @param records 待回填的示例认证记录
     */
    private void fillSampleChainInfo(List<CertificationRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Map<String, ChainCertificate> chainInfo = currentSampleChainInfo();
        for (CertificationRecord record : records) {
            // 链上以认证编号为唯一键（record.id），不依赖 emission_id 与 id 恰好相等
            applyChainInfo(record, chainInfo.get(record.getId()));
        }
    }

    /**
     * 读取示例证书的链上信息，命中 60 秒缓存时不再回链。
     *
     * @return 认证编号 → 链上凭证映射；链不可达时抛出链层异常
     */
    private Map<String, ChainCertificate> currentSampleChainInfo() {
        long now = System.currentTimeMillis();
        Map<String, ChainCertificate> cached = sampleChainCache;
        if (!cached.isEmpty() && now - sampleChainCachedAt < SAMPLE_CHAIN_CACHE_TTL_MS) {
            return cached;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (!sampleChainCache.isEmpty() && now - sampleChainCachedAt < SAMPLE_CHAIN_CACHE_TTL_MS) {
                return sampleChainCache;
            }
            Map<String, ChainCertificate> loaded = chainGateway.certificatesByEmissionIds(SAMPLE_CERT_IDS);
            sampleChainCache = loaded;
            sampleChainCachedAt = now;
            return loaded;
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
}
