package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.chain.ChainGateway;
import com.carbon.trace.chain.config.ChainProperties;
import com.carbon.trace.chain.model.ComplianceInput;
import com.carbon.trace.chain.model.ComplianceResult;
import com.carbon.trace.chain.support.AmountCodec;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.entity.EmissionRecord;
import com.carbon.trace.mapper.EmissionRecordMapper;
import com.carbon.trace.service.ComplianceService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 合规判定服务实现。
 *
 * 判定逻辑（配额、环比、漏填、因子不符、迟报）由智能合约执行；本服务只负责：
 *  1. 从业务库聚合判定所需输入（链上无法自行聚合业务数据，这是必要的输入边界）；
 *  2. 调用链网关并解读结果；
 *  3. 链不可用时按配置回退（把判定交回 {@code AlertServiceImpl} 的本地规则）。
 */
@Service
public class ComplianceServiceImpl implements ComplianceService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceServiceImpl.class);

    private final ChainGateway chainGateway;
    private final ChainProperties properties;
    private final EmissionRecordMapper emissionRecordMapper;

    /** 合规快照缓存 TTL：快照在认证时写入、之后不随排放数据变化，短缓存可避免重复两次链调用 */
    private static final long FLAGS_CACHE_TTL_MS = 60_000L;
    /** 防伪码 → 合规快照位掩码（进程内缓存） */
    private final Map<String, FlagsEntry> fingerprintFlagsCache = new ConcurrentHashMap<>();

    /** 合规快照缓存项：位掩码 + 写入时间（毫秒） */
    private record FlagsEntry(int flags, long cachedAt) {
    }

    /**
     * 合规链调用并发执行器（3 线程）：
     *  - 本期合计、年度合计、因子记忆是三条互不依赖的链写，串行会让一次填报额外等待 3 次链调用；
     *  - 阈值与迟报月数是一对独立的治理参数读取，并行可把首次读取从两次链调用压到一次。
     * 并发写已在本环境验证（同一账户 3 笔交易均成功）；线程数保持较小，避免打满 WeBASE-Front。
     */
    private final ExecutorService complianceChainExecutor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "compliance-chain-call");
        thread.setDaemon(true);
        return thread;
    });

    /** 治理参数快照缓存 TTL（30 秒）：阈值/迟报月数只在监管端手动修改时变化 */
    private static final long THRESHOLD_CACHE_TTL_MS = 30_000L;
    private volatile ThresholdSnapshot thresholdSnapshot;

    /** 配额缓存 TTL（30 秒）：写入配额后立即失效，避免读到旧值 */
    private static final long QUOTA_CACHE_TTL_MS = 30_000L;
    private final Map<String, QuotaEntry> quotaCache = new ConcurrentHashMap<>();

    /** 认证编号 → 合规快照位掩码缓存；快照只在上链时写入，60 秒内复用 */
    private final Map<String, FlagsEntry> certificateFlagsCache = new ConcurrentHashMap<>();

    /** 治理参数快照：环比阈值（基点）+ 迟报月数 + 缓存时间 */
    private record ThresholdSnapshot(int thresholdBp, int lateReportMonths, long cachedAt) {
    }

    /** 配额缓存项：配额值 + 缓存时间（毫秒） */
    private record QuotaEntry(BigDecimal quota, long cachedAt) {
    }

    public ComplianceServiceImpl(ChainGateway chainGateway,
                                 ChainProperties properties,
                                 EmissionRecordMapper emissionRecordMapper) {
        this.chainGateway = chainGateway;
        this.properties = properties;
        this.emissionRecordMapper = emissionRecordMapper;
    }

    /**
     * 直接按入参执行合规判定。
     *
     * 五类规则（年度配额、环比波动阈值、活动数据为 0、排放因子不符、迟报）全部由链上合约
     * CarbonCompliance.checkAll 判定，本方法不实现任何规则，只把入参交给网关并标记判定来源。
     * 返回的 flags 为可组合位掩码（1 配额 / 2 环比 / 4 活动数据 / 8 因子 / 16 迟报），
     * 级别与中文文案由调用方（AlertServiceImpl + ComplianceFlags）按位生成。
     *
     * 回退边界：链调用抛 RuntimeException 且 carbon.chain.compliance.fallback-to-java=true 时，
     * 返回 onChain=false、flags=-1（FLAGS_UNKNOWN）与 notice，由告警服务改走本地规则；
     * 未开启回退则原样抛出，避免链故障时给出“看似合规”的结论。
     *
     * @param input 判定入参（企业、报告期、活动数据、因子及本期/上期/年度合计，由后端聚合）
     * @return 判定结果；回退时为无结论结果（flags=-1、onChain=false）
     * @throws RuntimeException 链不可用且未开启回退时透传网关异常
     */
    @Override
    public ComplianceResult evaluate(ComplianceInput input) {
        try {
            ComplianceResult result = chainGateway.checkAll(input);
            result.setOnChain(true);
            return result;
        } catch (RuntimeException e) {
            if (properties.getCompliance().isFallbackToJava()) {
                log.warn("链上合规判定不可用，已回退本地规则：{}", e.getMessage());
                ComplianceResult fallback = new ComplianceResult();
                // 回退结果用哨兵 -1 表示「无结论」：既区别于 0（无规则命中），也提示调用方切本地规则
                fallback.setOnChain(false);
                fallback.setFlags(ComplianceResult.FLAGS_UNKNOWN);
                fallback.setNotice("链上合规判定不可用，已回退本地规则：" + e.getMessage());
                return fallback;
            }
            throw e;
        }
    }

    /**
     * 按已入库的排放记录判定。
     *
     * 该记录的排放量已计入数据库合计，因此不额外传入增量（extraAmount=null），
     * 本期与年度合计完全由数据库聚合，避免与记录自带的 amount 重复计数。
     *
     * @param record 已入库的排放记录（需含企业编号、报告期、排放源、活动数据与因子）
     * @return 判定结果，回退语义同 {@link #evaluate}
     */
    @Override
    public ComplianceResult evaluateForRecord(EmissionRecord record) {
        return evaluate(buildInput(record.getEnterpriseId(), record.getPeriod(), record.getSourceCode(),
                record.getActivityData(), record.getEmissionFactor(), null));
    }

    /**
     * 按排放记录编号判定：先查库，再复用 {@link #evaluateForRecord}。
     *
     * 记录不存在时视为调用方引用了无效编号，直接抛 404 而不是返回“合规”。
     *
     * @param emissionId 排放记录编号
     * @return 判定结果
     * @throws ApiException 记录不存在时抛 404
     */
    @Override
    public ComplianceResult evaluateByEmissionId(String emissionId) {
        EmissionRecord record = emissionRecordMapper.selectById(emissionId);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "排放记录不存在：" + emissionId);
        }
        return evaluateForRecord(record);
    }

    /**
     * 填报试算：判定尚未入库的填报数据，不写库、不产生告警。
     *
     * 本次排放量 = 活动数据 × 因子（四舍五入保留 2 位），作为增量同时计入本期合计与年度累计，
     * 让配额与环比规则按“本次填报后”的口径判定；活动数据或因子缺失时无法核算增量，amount 传 null，
     * 其中活动数据缺失在链上按 0 编码，会命中「活动数据为 0」规则。
     *
     * @param enterpriseId 企业编号
     * @param period       报告期 YYYY-MM
     * @param sourceCode   排放源编码（因子一致性规则使用）
     * @param activityData 本次活动数据
     * @param factor       本次核算使用的排放因子
     * @return 判定结果，回退语义同 {@link #evaluate}
     */
    @Override
    public ComplianceResult evaluateForCheck(String enterpriseId, String period, String sourceCode,
                                             BigDecimal activityData, BigDecimal factor) {
        // 试算场景记录尚未入库，把本次填报量计入本期与年度合计
        BigDecimal amount = null;
        // 任一入参缺失即无法核算增量，amount 保持 null；活动数据缺失会以 0 上链，从而命中「活动数据为 0」
        if (activityData != null && factor != null) {
            amount = activityData.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        }
        return evaluate(buildInput(enterpriseId, period, sourceCode, activityData, factor, amount));
    }

    /**
     * 取认证上链时使用的合规位掩码。
     *
     * 判定不可用（flags=-1）时按 0 处理：负值无法作为合约的无符号快照入参，
     * 也不能因链上判定故障阻断认证存证；但 0 在此语境只表示“无告警位”，并不代表判定已通过。
     *
     * @param emissionId 排放记录编号
     * @return 非负位掩码（1/2/4/8/16 的组合）；判定不可用时为 0
     * @throws ApiException 记录不存在时抛 404
     */
    @Override
    public int flagsForCertify(String emissionId) {
        ComplianceResult result = evaluateByEmissionId(emissionId);
        return Math.max(0, result.getFlags());
    }

    /**
     * 读证书上链时的合规快照位掩码。
     *
     * 快照固定为认证时刻的判定结果，之后数据变化不会重算；未找到证书时返回 0，
     * 与“无规则命中”同值，调用方无法据此区分“合规”与“无快照”。
     *
     * @param certId 认证编号
     * @return 位掩码（1/2/4/8/16 组合）；证书不存在返回 0
     */
    @Override
    public int certificateFlags(String certId) {
        if (!StringUtils.hasText(certId)) {
            return 0;
        }
        long now = System.currentTimeMillis();
        FlagsEntry cached = certificateFlagsCache.get(certId);
        if (cached != null && now - cached.cachedAt() < FLAGS_CACHE_TTL_MS) {
            return cached.flags();
        }
        // 快照位掩码不在证书 12 元组中，必须单独调用合约 complianceOf
        int flags = chainGateway.complianceFlagsOf(certId);
        certificateFlagsCache.put(certId, new FlagsEntry(flags, now));
        return flags;
    }

    /**
     * 按电子防伪码读取证书的合规快照位掩码。
     *
     * 防伪码匹配大小写敏感（与链上网关口径一致）；未命中返回 0，
     * 同样与“无规则命中”同值，需结合证书是否存在解读。
     *
     * @param fingerprint 电子防伪码
     * @return 位掩码（1/2/4/8/16 组合）；未命中返回 0
     */
    @Override
    public int flagsForFingerprint(String fingerprint) {
        if (!StringUtils.hasText(fingerprint)) {
            return 0;
        }
        String key = fingerprint.trim();
        long now = System.currentTimeMillis();
        FlagsEntry cached = fingerprintFlagsCache.get(key);
        if (cached != null && now - cached.cachedAt() < FLAGS_CACHE_TTL_MS) {
            return cached.flags();
        }
        // 先按防伪码定位认证编号（链上大小写敏感），再单独读取该编号的合规快照
        int flags = chainGateway.findByFingerprint(key)
                .map(cert -> chainGateway.complianceFlagsOf(cert.getCertId()))
                .orElse(0);
        fingerprintFlagsCache.put(key, new FlagsEntry(flags, now));
        return flags;
    }

    /**
     * 填报/新增记录后同步链上镜像：本期合计、年度合计与最近使用的排放因子。
     *
     * 链上无法自行聚合业务库数据，而配额规则依赖年度合计、环比规则依赖上一报告期合计、
     * 因子一致性规则依赖该企业该排放源的最近因子，因此需要在写库后回写这些镜像。
     * 企业编号或报告期为空时无法定位镜像键，直接跳过；因子或排放源缺失时只同步合计、不记忆因子。
     * 调用方应在事务提交后调用，避免事务回滚留下错误的链上镜像。
     *
     * @param record 新增的排放记录；为 null 或缺少企业/报告期时不执行任何同步
     */
    @Override
    public void syncMirrors(EmissionRecord record) {
        if (record == null || !StringUtils.hasText(record.getEnterpriseId())
                || !StringUtils.hasText(record.getPeriod())) {
            return;
        }
        List<Runnable> writes = new ArrayList<>(3);
        addTotalWrites(writes, record.getEnterpriseId(), record.getPeriod());
        if (record.getEmissionFactor() != null && StringUtils.hasText(record.getSourceCode())) {
            writes.add(() -> chainGateway.rememberFactor(record.getEnterpriseId(), record.getSourceCode(),
                    record.getEmissionFactor()));
        }
        runWrites(writes);
    }

    /**
     * 记录被删除后重算链上合计镜像（本期与年度），不涉及因子记忆。
     *
     * 删除后若不重算，链上仍保留删除前的合计（偏高），会导致后续配额/环比误判；
     * 因此这里按数据库当前数据全量覆盖；企业编号或报告期为空时无法定位镜像键，直接跳过。
     *
     * @param enterpriseId 企业编号
     * @param period       报告期 YYYY-MM
     */
    @Override
    public void syncMirrors(String enterpriseId, String period) {
        if (!StringUtils.hasText(enterpriseId) || !StringUtils.hasText(period)) {
            return;
        }
        List<Runnable> writes = new ArrayList<>(2);
        addTotalWrites(writes, enterpriseId, period);
        runWrites(writes);
    }

    /**
     * 把「本期合计 / 年度合计」两条链写加入任务列表。
     *
     * 合计值先从数据库算好再提交，避免并发线程里再做数据库聚合；
     * 两条写操作分别覆盖不同合约键，彼此无顺序依赖。
     *
     * @param writes       待执行写任务列表（就地追加）
     * @param enterpriseId 企业编号
     * @param period       报告期 YYYY-MM
     */
    private void addTotalWrites(List<Runnable> writes, String enterpriseId, String period) {
        int year = yearOf(period);
        BigDecimal periodTotal = sumAmount(enterpriseId, period);
        BigDecimal yearTotal = sumAmountInYear(enterpriseId, year);
        writes.add(() -> chainGateway.syncPeriodTotal(enterpriseId, period, periodTotal));
        writes.add(() -> chainGateway.syncYearTotal(enterpriseId, year, yearTotal));
    }

    /**
     * 并发执行一组链写，等待全部结束后再返回。
     *
     * 失败语义与串行版一致：任一条失败即抛出第一个异常，由 {@link com.carbon.trace.service.ChainSyncService}
     * 对整组任务重试（镜像写是全量覆盖，重放幂等）；已经成功的写不会回滚，与原有串行实现的部分成功行为一致。
     *
     * @param writes 待执行写任务
     */
    private void runWrites(List<Runnable> writes) {
        if (writes.isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>(writes.size());
        for (Runnable write : writes) {
            futures.add(CompletableFuture.runAsync(write, complianceChainExecutor));
        }
        RuntimeException failure = null;
        for (CompletableFuture<Void> future : futures) {
            try {
                future.join();
            } catch (CompletionException e) {
                if (failure == null) {
                    failure = e.getCause() instanceof RuntimeException runtime ? runtime : e;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** 容器关闭时停止合规链调用线程池，避免应用停止后仍有残留写链任务 */
    @PreDestroy
    public void shutdownChainExecutor() {
        complianceChainExecutor.shutdown();
    }

    /**
     * 读取企业某年度配额（链上治理参数）。
     *
     * 配额 0 表示该年度未配置，合约据此跳过配额规则（而不是把配额当成 0 吨）。
     *
     * @param enterpriseId 企业编号
     * @param year         年度（公历年）
     * @return 年度配额（tCO₂）；未配置返回 0
     */
    @Override
    public BigDecimal quotaOf(String enterpriseId, int year) {
        String key = enterpriseId + "|" + year;
        long now = System.currentTimeMillis();
        QuotaEntry cached = quotaCache.get(key);
        if (cached != null && now - cached.cachedAt() < QUOTA_CACHE_TTL_MS) {
            return cached.quota();
        }
        BigDecimal quota = chainGateway.annualQuotaOf(enterpriseId, year);
        quotaCache.put(key, new QuotaEntry(quota, now));
        return quota;
    }

    /**
     * 设置企业某年度的链上配额（治理参数，写合约）。
     *
     * 前置校验企业编号非空、年份在 2000~2100、配额非空且非负：
     * 链上写入不易回滚，非法值必须在入口拦截；配额 0 表示该年度未配置，等价于关闭配额规则。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param enterpriseId 企业编号
     * @param year         年度（2000~2100）
     * @param quota        年度配额（tCO₂，≥ 0）
     * @throws ApiException 企业编号为空、年份越界、配额为 null 或负数时抛 400
     * @throws RuntimeException 链写入失败时由网关抛出，本方法不吞异常，避免前端误以为已生效
     */
    @Override
    public void setQuota(String enterpriseId, int year, BigDecimal quota) {
        if (!StringUtils.hasText(enterpriseId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "企业编号不能为空");
        }
        if (year < 2000 || year > 2100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "年份非法");
        }
        if (quota == null || quota.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "配额必须为非负数");
        }
        chainGateway.setAnnualQuota(enterpriseId, year, quota);
        // 写成功后立即清缓存，避免随后的回查读到旧配额
        quotaCache.remove(enterpriseId + "|" + year);
    }

    /**
     * 读取环比波动告警阈值（单位：基点，1bp = 0.01%，2500 即 25%）。
     *
     * 阈值配置在合约中：webase 网关读链，stub 网关读内存；默认 2000（±20%）。
     *
     * @return 阈值基点；具体取值以链上治理参数为准
     */
    @Override
    public int fluctuationThresholdBp() {
        return thresholds().thresholdBp();
    }

    /**
     * 设置环比波动告警阈值（基点，1bp = 0.01%），写合约。
     *
     * 校验范围 (0, 10000]：0 会让任意波动都命中告警，超过 10000（100%）则阈值失去约束意义。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param bp 阈值基点，需大于 0 且不超过 10000
     * @throws ApiException 越界时抛 400
     * @throws RuntimeException 链写入失败时由网关抛出
     */
    @Override
    public void setFluctuationThresholdBp(int bp) {
        if (bp <= 0 || bp > 10000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "环比阈值非法（需大于 0 且不超过 10000 基点）");
        }
        chainGateway.setFluctuationThresholdBp(bp);
        // 治理参数成对缓存，任一参数变更都必须整体失效，避免阈值与迟报月数不同步
        thresholdSnapshot = null;
    }

    /**
     * 读取迟报判定月数阈值：报告期早于「当前月往前推 N 个月」即判迟报。
     *
     * 默认 1：当月至上月不判迟报，两个月前及更早的报告期视为迟报。
     *
     * @return 月数阈值
     */
    @Override
    public int lateReportMonths() {
        return thresholds().lateReportMonths();
    }

    /**
     * 设置迟报判定月数阈值（0~24），写合约。
     *
     * 0 表示报告期只要早于当前月即算迟报；上限 24 约束治理参数范围，避免误配置把历史数据整体判为迟报。
     *
     * 权限：控制器仅允许 regulator / admin 调用。
     *
     * @param months 月数阈值（0~24）
     * @throws ApiException 越界时抛 400
     * @throws RuntimeException 链写入失败时由网关抛出
     */
    @Override
    public void setLateReportMonths(int months) {
        if (months < 0 || months > 24) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "迟报月数非法（0~24）");
        }
        chainGateway.setLateReportMonths(months);
        // 治理参数成对缓存，任一参数变更都必须整体失效
        thresholdSnapshot = null;
    }

    /**
     * 读取治理参数快照（环比阈值 + 迟报月数）。
     *
     * 两项参数相互独立，首次读取时并行请求链，再一起写入 30 秒缓存；
     * 控制器先后调用 fluctuationThresholdBp() / lateReportMonths() 时第二次直接命中缓存。
     *
     * @return 治理参数快照；链不可达时抛出链层异常
     */
    private ThresholdSnapshot thresholds() {
        long now = System.currentTimeMillis();
        ThresholdSnapshot cached = thresholdSnapshot;
        if (cached != null && now - cached.cachedAt() < THRESHOLD_CACHE_TTL_MS) {
            return cached;
        }
        CompletableFuture<Integer> bpFuture = CompletableFuture.supplyAsync(
                chainGateway::fluctuationThresholdBp, complianceChainExecutor);
        CompletableFuture<Integer> monthsFuture = CompletableFuture.supplyAsync(
                chainGateway::lateReportMonths, complianceChainExecutor);
        ThresholdSnapshot snapshot = new ThresholdSnapshot(join(bpFuture), join(monthsFuture), now);
        thresholdSnapshot = snapshot;
        return snapshot;
    }

    /** 等待并发任务结果并展开异常，保持与串行实现一致的错误语义 */
    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    // ---------------- 内部：输入聚合 ----------------

    /**
     * 组装判定输入：把数据库中的合计与本次填报数据整理成合约需要的形态。
     *
     * @param enterpriseId 企业编号
     * @param period       报告期 YYYY-MM
     * @param sourceCode   排放源编码
     * @param activityData 本次活动数据
     * @param factor       本次核算因子
     * @param extraAmount  尚未入库的增量（填报试算用）；已入库的场景传 null，合计由数据库聚合
     */
    private ComplianceInput buildInput(String enterpriseId, String period, String sourceCode,
                                       BigDecimal activityData, BigDecimal factor, BigDecimal extraAmount) {
        ComplianceInput input = new ComplianceInput();
        input.setEnterpriseId(enterpriseId);
        input.setPeriod(period);
        input.setSourceCode(sourceCode);
        input.setActivityData(activityData);
        input.setFactor(factor);
        // 合约按整数月比较；格式非法时编码为 0，交由合约按“未提供报告期”处理
        input.setPeriodYyyymm(AmountCodec.toYyyymm(period));
        input.setYear(yearOf(period));

        YearMonth now = YearMonth.now();
        // 当前月由后端传入（合约不取系统时间），迟报规则据此判定，保证结果可复现
        input.setCurrentYyyymm(now.getYear() * 100L + now.getMonthValue());

        BigDecimal currentTotal = sumAmount(enterpriseId, period);
        BigDecimal yearToDate = sumAmountInYear(enterpriseId, input.getYear());
        if (extraAmount != null) {
            // 试算增量必须同时计入两处，否则配额与环比会看到不一致的口径
            currentTotal = currentTotal.add(extraAmount);
            yearToDate = yearToDate.add(extraAmount);
        }
        input.setCurrentTotal(currentTotal);
        input.setYearToDate(yearToDate);

        String previousPeriod = previousPeriodOf(enterpriseId, period);
        // 没有上一报告期时上期按 0，合约对 0 上期不做环比判定（而非视为无限波动）
        input.setPreviousTotal(previousPeriod == null
                ? BigDecimal.ZERO
                : sumAmount(enterpriseId, previousPeriod));
        return input;
    }

    /** 从报告期 YYYY-MM 取前 4 位作为年度；为空或非数字时回落到系统当前年份，保证聚合不中断 */
    private static int yearOf(String period) {
        if (period != null && period.length() >= 4) {
            try {
                return Integer.parseInt(period.substring(0, 4));
            } catch (NumberFormatException ignored) {
                // 落到系统当前年份
            }
        }
        return YearMonth.now().getYear();
    }

    /**
     * 该企业早于本报告期、且最近的一个报告期（字符串比较，要求 period 为定长 YYYY-MM）。
     *
     * 取全部历史后按报告期倒序取第一条；返回 null 表示无上期，环比规则据此不判定。
     */
    private String previousPeriodOf(String enterpriseId, String period) {
        if (!StringUtils.hasText(period)) {
            return null;
        }
        List<EmissionRecord> history = emissionRecordMapper.selectList(new LambdaQueryWrapper<EmissionRecord>()
                .eq(EmissionRecord::getEnterpriseId, enterpriseId)
                .lt(EmissionRecord::getPeriod, period)
                .orderByDesc(EmissionRecord::getPeriod));
        return history.isEmpty() ? null : history.get(0).getPeriod();
    }

    /** 某企业某报告期的排放量合计（报告期为空返回 0） */
    private BigDecimal sumAmount(String enterpriseId, String period) {
        if (!StringUtils.hasText(period)) {
            return BigDecimal.ZERO;
        }
        return sumOf(emissionRecordMapper.selectList(new LambdaQueryWrapper<EmissionRecord>()
                .eq(EmissionRecord::getEnterpriseId, enterpriseId)
                .eq(EmissionRecord::getPeriod, period)));
    }

    /**
     * 某企业某年度（报告期前缀 'yyyy-'）的排放量合计。
     *
     * 用 likeRight 前缀匹配而非解析月份，符合 YYYY-MM 格式的期数据都会计入；
     * 报告期格式不规范时可能漏计，写入侧已统一为 YYYY-MM。
     */
    private BigDecimal sumAmountInYear(String enterpriseId, int year) {
        List<EmissionRecord> records = emissionRecordMapper.selectList(new LambdaQueryWrapper<EmissionRecord>()
                .eq(EmissionRecord::getEnterpriseId, enterpriseId)
                .likeRight(EmissionRecord::getPeriod, year + "-"));
        return sumOf(records);
    }

    /** 对记录列表累加 amount（null 视作 0） */
    private static BigDecimal sumOf(List<EmissionRecord> records) {
        BigDecimal total = BigDecimal.ZERO;
        for (EmissionRecord record : records) {
            if (record.getAmount() != null) {
                total = total.add(record.getAmount());
            }
        }
        return total;
    }
}
