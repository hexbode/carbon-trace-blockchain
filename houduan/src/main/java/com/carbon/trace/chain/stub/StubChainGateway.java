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
 *
 * 与真实链的差异（阅读/调试时必须知道）：
 *  - txHash 是「stub- + 认证编号」的伪哈希，区块高度恒为 0，{@link #latestBlockNumber()} 恒为 -1；
 *  - 认证是覆盖式写入：同一编号再次认证会直接刷新为 VALID（真实链仅允许首次登记）；
 *   仅当编号已处于 REVOKED 时才拒绝，用于模拟「撤销即终局」；
 *  - 合规判定在本地按合约规则复刻（见 {@link #checkAll}），用于离线回归，不能替代真实的链上判定；
 *  - 数据保存在 {@link ConcurrentHashMap} 中，重启即清空；多实例部署时各实例数据互不可见。
 *
 * 线程安全：仅使用并发 Map 与 volatile 标量，读多写少场景下无需额外加锁；
 * 但「读-改-写」状态（如 revoke）不是原子操作，桩模式只用于开发，可接受该弱一致性。
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

    /**
     * 认证登记：内存覆盖式写凭证；若同一编号已被 REVOKED 则拒绝重复认证（模拟合约终局约束）。
     *
     * 与真实链的差异：已作废（INVALID）的编号在这里会被重新置为 VALID，而真实链会拒绝；
     * 该差异仅在开发环境可见，不应据此推断生产行为。
     *
     * @param command 认证要素；{@code certId} 为 Map 主键，不能为 null
     * @return 写入后的凭证（与内部保存的是同一可变对象，调用方不应修改）
     * @throws com.carbon.trace.common.ApiException 该编号已被撤销（409）时抛出
     */
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
        // 桩没有区块概念，高度固定 0，仅占位保证前端字段不为空
        cert.setCreatedBlock(0L);
        cert.setBlockIndex(0L);
        // 明显非真实哈希，避免在开发环境被误当作链上存证
        cert.setTxHash("stub-" + command.getCertId());

        certs.put(cert.getCertId(), cert);
        // 两条索引与凭证主表同步维护；注意此处写入的是原始防伪码，未 trim（见 findByFingerprint 的说明）
        certIdByFingerprint.put(command.getFingerprint(), cert.getCertId());
        certIdByTxHash.put(normalize(cert.getTxHash()), cert.getCertId());
        return cert;
    }

    /**
     * 监管撤销：仅 VALID → REVOKED，凭证不存在时报 404（模拟合约 revert）。
     *
     * 重复撤销不会报错，只覆盖撤销原因；状态保持 REVOKED 不变（幂等）。
     *
     * @param certId 认证编号
     * @param reason 撤销原因，可为 null
     * @throws com.carbon.trace.common.ApiException 凭证不存在（404）时抛出
     */
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

    /**
     * 作废标记：链上不可删除，仅把状态改为 INVALID；未认证过的记录静默忽略（与合约一致）。
     *
     * 已 REVOKED 的存证不会被改为 INVALID（撤销优先，状态不可回退），但撤销原因会被本次调用覆盖。
     *
     * @param emissionId 排放记录编号（=认证编号）
     * @param reason     作废原因，可为 null
     */
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

    /**
     * 按电子防伪码精确匹配（去首尾空白、大小写敏感，与 webase 实现口径一致）。
     *
     * 易错点：写入侧（{@link #certify}）并未对防伪码 trim，若入参带首尾空白，
     * 写入的键与查询时的键不一致，会表现为「刚认证却查不到」。调用方应先规范化防伪码。
     *
     * @param fingerprint 电子防伪码，可为 null
     * @return 命中凭证；入参为 null、未命中或凭证已被移除时返回空 Optional
     */
    @Override
    public Optional<ChainCertificate> findByFingerprint(String fingerprint) {
        if (fingerprint == null) {
            return Optional.empty();
        }
        String certId = certIdByFingerprint.get(fingerprint.trim());
        return certId == null ? Optional.empty() : Optional.ofNullable(certs.get(certId));
    }

    /**
     * 按认证编号查询；编号为空返回空。
     *
     * @param certId 认证编号，可为 null
     * @return 命中凭证或空 Optional
     */
    @Override
    public Optional<ChainCertificate> findByCertId(String certId) {
        return certId == null ? Optional.empty() : Optional.ofNullable(certs.get(certId));
    }

    /**
     * 排放记录编号与认证编号同号，桩模式下与按认证编号查询等价。
     *
     * @param emissionId 排放记录编号，可为 null
     * @return 命中凭证或空 Optional
     */
    @Override
    public Optional<ChainCertificate> findByEmissionId(String emissionId) {
        return findByCertId(emissionId);
    }

    /**
     * 按归一化交易哈希反查；桩模式下的哈希为「stub-编号」伪哈希。
     *
     * @param txHash 交易哈希，大小写与 0x 前缀不敏感
     * @return 命中凭证；入参为 null、未命中或凭证不存在时返回空 Optional
     */
    @Override
    public Optional<ChainCertificate> findByTxHash(String txHash) {
        if (txHash == null) {
            return Optional.empty();
        }
        String certId = certIdByTxHash.get(normalize(txHash));
        return certId == null ? Optional.empty() : Optional.ofNullable(certs.get(certId));
    }

    /**
     * 兼容旧语义：先按防伪码后按交易哈希，与 webase 实现口径一致。
     *
     * @param fingerprintOrTxHash 电子防伪码或交易哈希
     * @return 命中的凭证；两者均未命中时返回空 Optional
     */
    @Override
    public Optional<ChainCertificate> findAny(String fingerprintOrTxHash) {
        Optional<ChainCertificate> byFingerprint = findByFingerprint(fingerprintOrTxHash);
        return byFingerprint.isPresent() ? byFingerprint : findByTxHash(fingerprintOrTxHash);
    }

    /**
     * 按编号集合批量取凭证；未命中的编号不出现在结果中。
     *
     * @param emissionIds 排放记录编号集合，可为 null
     * @return 编号 → 凭证的映射，恒不为 null
     */
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

    /**
     * 遍历内存凭证按状态计数（total/valid/revoked/invalid）。
     *
     * 与真实链的计数器语义一致：total 含已作废，四项满足 total = valid + revoked + invalid。
     *
     * @return 计数快照，恒不为 null
     */
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

    /**
     * 桩模式没有区块，恒返回 -1（与接口约定「无区块返回 -1」一致）。
     *
     * @return 恒为 -1
     */
    @Override
    public long latestBlockNumber() {
        return -1L;
    }

    /**
     * 桩模式下以内存凭证数近似交易数（仅用于开发展示）。
     *
     * 该值不区分状态，也不等于区块内交易数，不能用于任何对账口径。
     *
     * @return 当前内存凭证数量
     */
    @Override
    public long chainTxCount() {
        return certs.size();
    }

    /**
     * 桩模式没有区块，区块浏览恒为空。
     *
     * @param limit 入参被忽略
     * @return 空的可变列表（调用方可直接追加，不会影响内部状态）
     */
    @Override
    public List<ChainBlockView> listRecentBlocks(int limit) {
        // 桩模式没有区块，区块浏览为空
        return new ArrayList<>();
    }

    // ---------------- 合规判定（与合约同规则的本地实现，便于离线回归） ----------------

    /**
     * 合规判定：本地按与合约相同的规则逐条复刻，便于离线回归比对两者结果一致。
     *
     * 规则与判定顺序必须与 CarbonCompliance.checkAll 保持一致，否则离线回归会失真：
     *  1. 配额：年累计（优先取链上镜像）严格大于配额即超限；配额为 0 视为未配置，不判定；
     *  2. 环比：上期大于 0 时才计算，波动绝对值严格大于阈值才命中（等于阈值算通过）；
     *  3. 活动数据：定标后为 0 即命中；
     *  4. 因子：链上已有上次因子且与本次不同即命中（首次填报不判定）；
     *  5. 迟报：报告期早于「当前月份 - 迟报月数」即命中。
     *
     * 返回 {@code onChain=true} 表示「按链上规则判定」，不代表真的发生了链调用（桩模式无链）。
     *
     * @param input 判定输入，金额字段为十进制原值
     * @return 判定结果，flags 为规则位掩码
     */
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
        // 链上已同步的年累计优先于调用方传入值：与合约「以链上状态为准」的口径一致
        if (mirrored != null && mirrored != 0L) {
            yearToDateScaled = mirrored;
        }
        result.setYearToDate(AmountCodec.fromScaled(yearToDateScaled));
        // 配额为 0 表示未配置，此时不做超限判定，避免把所有企业误判为超限
        if (quotaScaled != 0L && yearToDateScaled > quotaScaled) {
            result.setFlags(result.getFlags() | ComplianceResult.FLAG_QUOTA);
        }

        long current = AmountCodec.toScaled(input.getCurrentTotal());
        long previous = AmountCodec.toScaled(input.getPreviousTotal());
        // 无上期（previous<=0）时环比无意义，不设置 deltaBp，也不命中环比规则
        if (previous > 0) {
            long deltaBp = deltaBp(current, previous);
            result.setDeltaBp(deltaBp);
            // 严格大于阈值才命中；等于阈值视为在允许范围内
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
        // 首次填报（链上无历史因子）不判定因子一致性，避免首条数据被判为异常
        if (prevFactorScaled != 0L && prevFactorScaled != factorScaled) {
            result.setFlags(result.getFlags() | ComplianceResult.FLAG_FACTOR);
        }

        if (isLate(input.getPeriodYyyymm(), input.getCurrentYyyymm())) {
            result.setFlags(result.getFlags() | ComplianceResult.FLAG_LATE);
        }
        return result;
    }

    /**
     * 设置企业某年度配额（内存镜像，定标后存储）。
     *
     * @param enterpriseId 企业编号
     * @param year         年份
     * @param quota        配额，null 按 0 处理（0 表示未配置）
     */
    @Override
    public void setAnnualQuota(String enterpriseId, int year, BigDecimal quota) {
        annualQuota.put(key(enterpriseId, String.valueOf(year)), AmountCodec.toScaled(quota));
    }

    /**
     * 查询企业某年度配额；未配置返回 0。
     *
     * @param enterpriseId 企业编号
     * @param year         年份
     * @return 配额，恒不为 null
     */
    @Override
    public BigDecimal annualQuotaOf(String enterpriseId, int year) {
        Long value = annualQuota.get(key(enterpriseId, String.valueOf(year)));
        return AmountCodec.fromScaled(value == null ? 0L : value);
    }

    /**
     * 同步企业某报告期合计排放量（内存镜像，供环比规则使用）。
     *
     * 注意：当前 {@link #checkAll} 并未回读该镜像（上期值由调用方传入），本方法仅为与真实链接口保持一致。
     *
     * @param enterpriseId 企业编号
     * @param period       报告期 YYYY-MM
     * @param amount       报告期排放量合计
     */
    @Override
    public void syncPeriodTotal(String enterpriseId, String period, BigDecimal amount) {
        periodTotal.put(key(enterpriseId, period), AmountCodec.toScaled(amount));
    }

    /**
     * 同步企业某年度累计排放量（内存镜像，供配额规则使用）。
     *
     * @param enterpriseId 企业编号
     * @param year         年份
     * @param amount       年度累计排放量
     */
    @Override
    public void syncYearTotal(String enterpriseId, int year, BigDecimal amount) {
        yearTotal.put(key(enterpriseId, String.valueOf(year)), AmountCodec.toScaled(amount));
    }

    /**
     * 记录某企业某排放源最近一次排放因子（内存镜像，供因子一致性规则使用）。
     *
     * @param enterpriseId 企业编号
     * @param sourceCode   排放源编码
     * @param factor       排放因子，null 按 0 处理
     */
    @Override
    public void rememberFactor(String enterpriseId, String sourceCode, BigDecimal factor) {
        lastFactor.put(key(enterpriseId, sourceCode), AmountCodec.factorToScaled(factor));
    }

    /**
     * 设置环比波动阈值（基点，内存镜像）。
     *
     * 桩模式不做合法性校验（真实链合约会校验 0 &lt; bp &le; 10000）。
     *
     * @param bp 阈值基点
     */
    @Override
    public void setFluctuationThresholdBp(int bp) {
        this.thresholdBp = bp;
    }

    /**
     * 查询环比波动阈值（基点）。
     *
     * @return 当前阈值，默认 2000（即 20%）
     */
    @Override
    public int fluctuationThresholdBp() {
        return thresholdBp;
    }

    /**
     * 设置迟报判定月数（内存镜像）。
     *
     * @param months 月数，默认 1
     */
    @Override
    public void setLateReportMonths(int months) {
        this.lateReportMonths = months;
    }

    /**
     * 查询迟报判定月数。
     *
     * @return 当前判定月数
     */
    @Override
    public int lateReportMonths() {
        return lateReportMonths;
    }

    /**
     * 桩模式恒不可用（不含真实链探活逻辑）。
     *
     * 该返回值会让健康检查与前端明确提示「未连接真实链」，不要为了「看起来正常」改成 true。
     *
     * @return 恒为 false
     */
    @Override
    public boolean available() {
        return false;
    }

    /**
     * 运行模式标识。
     *
     * @return 恒为 {@code "stub"}
     */
    @Override
    public String mode() {
        return "stub";
    }

    // ---------------- 内部工具 ----------------

    /**
     * 拼接复合键：企业编号 + 分隔符 + 分类键，防止不同企业同一维度串号。
     *
     * 使用 {@code |} 作为分隔符，因此企业编号本身不能包含该字符。
     *
     * @param a 企业编号
     * @param b 年份/报告期/排放源编码等维度键
     * @return 复合键
     */
    private static String key(String a, String b) {
        return a + "|" + b;
    }

    /**
     * 交易哈希归一化：去空白、转小写、去 0x 前缀（与 webase 实现保持一致）。
     *
     * @param value 原始哈希，不能为 null
     * @return 归一化后的哈希
     */
    private static String normalize(String value) {
        String v = value.trim().toLowerCase();
        return v.startsWith("0x") ? v.substring(2) : v;
    }

    /**
     * 环比基点，四舍五入到 0.1%，与合约 _deltaBp 口径一致。
     *
     * 计算式为 {@code (current - previous) / previous * 100}，结果保留 1 位小数（即 0.1% 精度）；
     * 调用方需保证 previous &gt; 0，否则除零会抛异常。
     *
     * @param current  本期定标排放量
     * @param previous 上期定标排放量，必须大于 0
     * @return 环比基点（可为负）
     */
    private static long deltaBp(long current, long previous) {
        BigDecimal diff = BigDecimal.valueOf(current - previous);
        BigDecimal den = BigDecimal.valueOf(previous);
        return diff.multiply(BigDecimal.valueOf(100))
                .divide(den, 1, RoundingMode.HALF_UP)
                .longValue();
    }

    /**
     * 报告期是否早于「当前月份 - lateReportMonths」。
     *
     * 任一时间字段为 0（无效）时判定为不迟报；deadline 计算失败（月份越界）同样不判定，
     * 避免畸形数据触发误报。
     *
     * @param periodYyyymm  报告期 YYYYMM
     * @param currentYyyymm 当前月份 YYYYMM
     * @return true 表示构成迟报
     */
    private boolean isLate(long periodYyyymm, long currentYyyymm) {
        if (periodYyyymm == 0 || currentYyyymm == 0) {
            return false;
        }
        long deadline = minusMonths(currentYyyymm, lateReportMonths);
        return deadline != 0 && periodYyyymm < deadline;
    }

    /**
     * yyyymm（整数）减 months 个月：按月总量 year*12+(month-1) 换算后回算 yyyymm；
     * month 越界或结果早于第 0 个月时返回 0。
     *
     * 使用「月份序号」做减法可避免跨年借位错误（如 202401 减 1 个月应得 202312）。
     *
     * @param yyyymm 基准月份，格式 YYYYMM
     * @param months 需要减去的月数
     * @return 结果月份 YYYYMM；无法计算时返回 0
     */
    private static long minusMonths(long yyyymm, int months) {
        long year = yyyymm / 100;
        long month = yyyymm % 100;
        if (month == 0 || month > 12) {
            return 0;
        }
        long total = year * 12 + (month - 1);
        // 结果早于公元 0 年 1 月时视为无效，返回 0 让调用方跳过判定
        if (total < months) {
            return 0;
        }
        total -= months;
        return (total / 12) * 100 + (total % 12) + 1;
    }
}
