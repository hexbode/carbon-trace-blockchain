package com.carbon.trace.chain.stub;

import com.carbon.trace.chain.ChainGateway;
import com.carbon.trace.chain.model.ChainBlockView;
import com.carbon.trace.chain.model.ChainCertCount;
import com.carbon.trace.chain.model.ChainCertStatus;
import com.carbon.trace.chain.model.ChainCertificate;
import com.carbon.trace.chain.model.CertifyCommand;
import com.carbon.trace.chain.model.ComplianceInput;
import com.carbon.trace.chain.model.ComplianceResult;
import com.carbon.trace.chain.support.AmountCodec;
import com.carbon.trace.common.ApiException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 离线开发桩实现。
 *
 * 这不是"保留旧链"：它没有区块、没有挖矿、没有哈希链、不落盘，仅用内存 Map 保存凭证，
 * 目的是在本机没有 WeBASE 时让应用可启动、接口回归可跑、单测可跑。
 * 一旦 carbon.chain.mode=webase，本实现不参与装配。
 */
@Component
@ConditionalOnProperty(name = "carbon.chain.mode", havingValue = "stub", matchIfMissing = true)
public class StubChainGateway implements ChainGateway {

    private static final Logger log = LoggerFactory.getLogger(StubChainGateway.class);

    /** certId -> 凭证 */
    private final Map<String, ChainCertificate> certs = new ConcurrentHashMap<>();

    /** fingerprint（大小写敏感精确匹配）-> certId */
    private final Map<String, String> certIdByFingerprint = new ConcurrentHashMap<>();

    /** 归一化 txHash（小写、去 0x）-> certId */
    private final Map<String, String> certIdByTxHash = new ConcurrentHashMap<>();

    /** 合规镜像状态 */
    private final Map<String, Long> annualQuota = new ConcurrentHashMap<>();
    private final Map<String, Long> yearTotal = new ConcurrentHashMap<>();
    private final Map<String, Long> periodTotal = new ConcurrentHashMap<>();
    private final Map<String, Long> lastFactor = new ConcurrentHashMap<>();

    private volatile int thresholdBp = 2000;
    private volatile int lateReportMonths = 1;

    /** 启动提示：显式声明 stub 无区块/无挖矿/不落盘，避免开发环境被误当作真实链 */
    @PostConstruct
    public void banner() {
        log.warn("============================================================");
        log.warn("  当前链运行模式 = stub（离线开发桩）：无区块、无挖矿、不落盘、重启即清空。");
        log.warn("  这不是真实区块链，仅供本机开发与接口回归使用。");
        log.warn("  生产/演示部署请设置 carbon.chain.mode=webase 并配置 WeBASE-Front。");
        log.warn("============================================================");
    }

    /** 认证登记：内存覆盖式写凭证；若同一编号已被 REVOKED 则拒绝重复认证（模拟合约终局约束） */
    @Override
    public ChainCertificate certify(CertifyCommand command) {
        ChainCertificate existing = certs.get(command.getCertId());
        if (existing != null && existing.getStatus() == ChainCertStatus.REVOKED) {
            throw new ApiException(HttpStatus.CONFLICT, "该证书已被监管机构撤销，不可重复认证");
        }
        ChainCertificate cert = existing == null ? new ChainCertificate() : existing;
        cert.setExists(true);
        cert.setStatus(ChainCertStatus.VALID);
        cert.setCertId(command.getCertId());
        cert.setEmissionId(command.getCertId());
        cert.setEnterpriseId(command.getEnterpriseId());
        cert.setEnterpriseName(command.getEnterpriseName());
        cert.setPeriod(command.getPeriod());
        cert.setAmount(command.getAmount());
        cert.setFingerprint(command.getFingerprint());
        cert.setAuditor(command.getAuditor());
        cert.setReason(null);
        cert.setComplianceFlags(command.getComplianceFlags());
        cert.setCreatedAt(System.currentTimeMillis() / 1000);
        cert.setCreatedBlock(0L);
        cert.setBlockIndex(0L);
        // 明显非真实哈希，避免在开发环境被误当作链上存证
        cert.setTxHash("stub-" + command.getCertId());

        certs.put(cert.getCertId(), cert);
        certIdByFingerprint.put(command.getFingerprint(), cert.getCertId());
        certIdByTxHash.put(normalize(cert.getTxHash()), cert.getCertId());
        return cert;
    }

    /** 监管撤销：仅 VALID → REVOKED，凭证不存在时报 404（模拟合约 revert） */
    @Override
    public void revoke(String certId, String reason) {
        ChainCertificate cert = certs.get(certId);
        if (cert == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "链上存证不存在，无法撤销");
        }
        if (cert.getStatus() == ChainCertStatus.VALID) {
            cert.setStatus(ChainCertStatus.REVOKED);
        }
        cert.setReason(reason);
    }

    /** 作废标记：链上不可删除，仅把状态改为 INVALID；未认证过的记录静默忽略（与合约一致） */
    @Override
    public void invalidateByEmissionId(String emissionId, String reason) {
        ChainCertificate cert = certs.get(emissionId);
        if (cert == null) {
            // 未认证过的排放记录：链上无存证，无需作废（与合约一致，不报错）
            return;
        }
        if (cert.getStatus() == ChainCertStatus.VALID) {
            cert.setStatus(ChainCertStatus.INVALID);
        }
        cert.setReason(reason);
    }

    /** 按电子防伪码精确匹配（去首尾空白、大小写敏感，与 webase 实现口径一致） */
    @Override
    public Optional<ChainCertificate> findByFingerprint(String fingerprint) {
        if (fingerprint == null) {
            return Optional.empty();
        }
        String certId = certIdByFingerprint.get(fingerprint.trim());
        return certId == null ? Optional.empty() : Optional.ofNullable(certs.get(certId));
    }

    /** 按认证编号查询；编号为空返回空 */
    @Override
    public Optional<ChainCertificate> findByCertId(String certId) {
        return certId == null ? Optional.empty() : Optional.ofNullable(certs.get(certId));
    }

    /** 排放记录编号与认证编号同号，桩模式下与按认证编号查询等价 */
    @Override
    public Optional<ChainCertificate> findByEmissionId(String emissionId) {
        return findByCertId(emissionId);
    }

    /** 按归一化交易哈希反查；桩模式下的哈希为「stub-编号」伪哈希 */
    @Override
    public Optional<ChainCertificate> findByTxHash(String txHash) {
        if (txHash == null) {
            return Optional.empty();
        }
        String certId = certIdByTxHash.get(normalize(txHash));
        return certId == null ? Optional.empty() : Optional.ofNullable(certs.get(certId));
    }

    /** 兼容旧语义：先按防伪码后按交易哈希，与 webase 实现口径一致 */
    @Override
    public Optional<ChainCertificate> findAny(String fingerprintOrTxHash) {
        Optional<ChainCertificate> byFingerprint = findByFingerprint(fingerprintOrTxHash);
        return byFingerprint.isPresent() ? byFingerprint : findByTxHash(fingerprintOrTxHash);
    }

    /** 按编号集合批量取凭证；未命中的编号不出现在结果中 */
    @Override
    public Map<String, ChainCertificate> certificatesByEmissionIds(Collection<String> emissionIds) {
        Map<String, ChainCertificate> result = new HashMap<>();
        if (emissionIds == null) {
            return result;
        }
        for (String emissionId : emissionIds) {
            ChainCertificate cert = certs.get(emissionId);
            if (cert != null) {
                result.put(emissionId, cert);
            }
        }
        return result;
    }

    /** 遍历内存凭证按状态计数（total/valid/revoked/invalid） */
    @Override
    public ChainCertCount countCertificates() {
        ChainCertCount count = new ChainCertCount();
        for (ChainCertificate cert : certs.values()) {
            count.setTotal(count.getTotal() + 1);
            switch (cert.getStatus()) {
                case VALID -> count.setValid(count.getValid() + 1);
                case REVOKED -> count.setRevoked(count.getRevoked() + 1);
                case INVALID -> count.setInvalid(count.getInvalid() + 1);
                default -> { }
            }
        }
        return count;
    }

    /** 桩模式没有区块，恒返回 -1（与接口约定「无区块返回 -1」一致） */
    @Override
    public long latestBlockNumber() {
        return -1L;
    }

    /** 桩模式下以内存凭证数近似交易数（仅用于开发展示） */
    @Override
    public long chainTxCount() {
        return certs.size();
    }

    /** 桩模式没有区块，区块浏览恒为空 */
    @Override
    public List<ChainBlockView> listRecentBlocks(int limit) {
        // 桩模式没有区块，区块浏览为空
        return new ArrayList<>();
    }

    // ---------------- 合规判定（与合约同规则的本地实现，便于离线回归） ----------------

    /** 合规判定：本地按与合约相同的规则逐条复刻，便于离线回归比对两者结果一致 */
    @Override
    public ComplianceResult checkAll(ComplianceInput input) {
        ComplianceResult result = new ComplianceResult();
        result.setOnChain(true);
        result.setFlags(0);
        result.setThresholdBp(thresholdBp);

        Long quota = annualQuota.get(key(input.getEnterpriseId(), String.valueOf(input.getYear())));
        long quotaScaled = quota == null ? 0L : quota;
        result.setQuota(AmountCodec.fromScaled(quotaScaled));

        long yearToDateScaled = AmountCodec.toScaled(input.getYearToDate());
        Long mirrored = yearTotal.get(key(input.getEnterpriseId(), String.valueOf(input.getYear())));
        if (mirrored != null && mirrored != 0L) {
            yearToDateScaled = mirrored;
        }
        result.setYearToDate(AmountCodec.fromScaled(yearToDateScaled));
        if (quotaScaled != 0L && yearToDateScaled > quotaScaled) {
            result.setFlags(result.getFlags() | ComplianceResult.FLAG_QUOTA);
        }

        long current = AmountCodec.toScaled(input.getCurrentTotal());
        long previous = AmountCodec.toScaled(input.getPreviousTotal());
        if (previous > 0) {
            long deltaBp = deltaBp(current, previous);
            result.setDeltaBp(deltaBp);
            if (Math.abs(deltaBp) > thresholdBp) {
                result.setFlags(result.getFlags() | ComplianceResult.FLAG_FLUCTUATION);
            }
        }

        if (AmountCodec.toScaled(input.getActivityData()) == 0L) {
            result.setFlags(result.getFlags() | ComplianceResult.FLAG_ACTIVITY);
        }

        Long prevFactor = lastFactor.get(key(input.getEnterpriseId(), input.getSourceCode()));
        long prevFactorScaled = prevFactor == null ? 0L : prevFactor;
        result.setPreviousFactor(AmountCodec.factorFromScaled(prevFactorScaled));
        long factorScaled = AmountCodec.factorToScaled(input.getFactor());
        if (prevFactorScaled != 0L && prevFactorScaled != factorScaled) {
            result.setFlags(result.getFlags() | ComplianceResult.FLAG_FACTOR);
        }

        if (isLate(input.getPeriodYyyymm(), input.getCurrentYyyymm())) {
            result.setFlags(result.getFlags() | ComplianceResult.FLAG_LATE);
        }
        return result;
    }

    /** 设置企业某年度配额（内存镜像，定标后存储） */
    @Override
    public void setAnnualQuota(String enterpriseId, int year, BigDecimal quota) {
        annualQuota.put(key(enterpriseId, String.valueOf(year)), AmountCodec.toScaled(quota));
    }

    /** 查询企业某年度配额；未配置返回 0 */
    @Override
    public BigDecimal annualQuotaOf(String enterpriseId, int year) {
        Long value = annualQuota.get(key(enterpriseId, String.valueOf(year)));
        return AmountCodec.fromScaled(value == null ? 0L : value);
    }

    /** 同步企业某报告期合计排放量（内存镜像，供环比规则使用） */
    @Override
    public void syncPeriodTotal(String enterpriseId, String period, BigDecimal amount) {
        periodTotal.put(key(enterpriseId, period), AmountCodec.toScaled(amount));
    }

    /** 同步企业某年度累计排放量（内存镜像，供配额规则使用） */
    @Override
    public void syncYearTotal(String enterpriseId, int year, BigDecimal amount) {
        yearTotal.put(key(enterpriseId, String.valueOf(year)), AmountCodec.toScaled(amount));
    }

    /** 记录某企业某排放源最近一次排放因子（内存镜像，供因子一致性规则使用） */
    @Override
    public void rememberFactor(String enterpriseId, String sourceCode, BigDecimal factor) {
        lastFactor.put(key(enterpriseId, sourceCode), AmountCodec.factorToScaled(factor));
    }

    /** 设置环比波动阈值（基点，内存镜像） */
    @Override
    public void setFluctuationThresholdBp(int bp) {
        this.thresholdBp = bp;
    }

    /** 查询环比波动阈值（基点） */
    @Override
    public int fluctuationThresholdBp() {
        return thresholdBp;
    }

    /** 设置迟报判定月数（内存镜像） */
    @Override
    public void setLateReportMonths(int months) {
        this.lateReportMonths = months;
    }

    /** 查询迟报判定月数 */
    @Override
    public int lateReportMonths() {
        return lateReportMonths;
    }

    /** 桩模式恒不可用（不含真实链探活逻辑） */
    @Override
    public boolean available() {
        return false;
    }

    /** 运行模式标识：恒为 stub */
    @Override
    public String mode() {
        return "stub";
    }

    // ---------------- 内部工具 ----------------

    /** 拼接复合键：企业编号 + 分隔符 + 分类键，防止不同企业同一维度串号 */
    private static String key(String a, String b) {
        return a + "|" + b;
    }

    /** 交易哈希归一化：去空白、转小写、去 0x 前缀（与 webase 实现保持一致） */
    private static String normalize(String value) {
        String v = value.trim().toLowerCase();
        return v.startsWith("0x") ? v.substring(2) : v;
    }

    /** 环比基点，四舍五入到 0.1%，与合约 _deltaBp 口径一致 */
    private static long deltaBp(long current, long previous) {
        BigDecimal diff = BigDecimal.valueOf(current - previous);
        BigDecimal den = BigDecimal.valueOf(previous);
        return diff.multiply(BigDecimal.valueOf(100))
                .divide(den, 1, RoundingMode.HALF_UP)
                .longValue();
    }

    /** 报告期是否早于「当前月份 - lateReportMonths」 */
    private boolean isLate(long periodYyyymm, long currentYyyymm) {
        if (periodYyyymm == 0 || currentYyyymm == 0) {
            return false;
        }
        long deadline = minusMonths(currentYyyymm, lateReportMonths);
        return deadline != 0 && periodYyyymm < deadline;
    }

    /** yyyymm（整数）减 months 个月：按月总量 year*12+(month-1) 换算后回算 yyyymm；month 越界或结果早于第 0 个月时返回 0 */
    private static long minusMonths(long yyyymm, int months) {
        long year = yyyymm / 100;
        long month = yyyymm % 100;
        if (month == 0 || month > 12) {
            return 0;
        }
        long total = year * 12 + (month - 1);
        if (total < months) {
            return 0;
        }
        total -= months;
        return (total / 12) * 100 + (total % 12) + 1;
    }
}
