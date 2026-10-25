package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.chain.model.ComplianceResult;
import com.carbon.trace.chain.support.ComplianceFlags;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.entity.Alert;
import com.carbon.trace.entity.EmissionRecord;
import com.carbon.trace.mapper.AlertMapper;
import com.carbon.trace.mapper.EmissionRecordMapper;
import com.carbon.trace.service.AlertService;
import com.carbon.trace.service.ComplianceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 异常告警服务实现
 *
 * <p>告警来源分两部分：数据库预置的示例告警，以及「写入时判定」——
 * 企业新增排放记录时，{@link #evaluateOnEmissionCreate} 会按下列规则即时判定并生成告警：</p>
 * <ol>
 *   <li>配额超限（高危）：本年度累计排放量超出链上年度配额；</li>
 *   <li>数据异常（高危）：本期排放量较上一报告期环比波动超过合约配置阈值（默认 ±20%）；</li>
 *   <li>数据异常（低危）：本期活动数据为 0，疑似漏填；</li>
 *   <li>因子不符（中危）：本次核算所用排放因子与该企业同排放源链上历史使用值不一致；</li>
 *   <li>迟报（中危）：报告期早于当前月份超过合约配置的月数（默认 1 个月）。</li>
 * </ol>
 *
 * <p>以上规则的判定由链上智能合约（CarbonCompliance.checkAll）执行，本类只负责按判定结果生成
 * 告警级别与中文文案；链上判定不可用时回退到本类内的本地规则实现（保证填报流程不中断）。</p>
 */
@Service
public class AlertServiceImpl implements AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertServiceImpl.class);

    /** 环比波动阈值（%）：本地回退规则使用，与合约默认阈值保持一致 */
    private static final BigDecimal FLUCTUATION_THRESHOLD = new BigDecimal("20");

    /** 迟报判定：报告期早于当前月份超过该月数即视为迟报（本地回退规则使用） */
    private static final long LATE_REPORT_MONTHS = 1;

    /**
     * 告警编号自增计数器：-1 表示尚未初始化，首次取号时以 alerts 表现有最大编号为起点。
     *
     * 不能用 selectCount 推算下一个编号：并发事务看不到对方尚未提交的插入，
     * 多个请求会反复算出同一编号并撞主键（实测并发填报时 ALT015 重复插入）。
     */
    private final java.util.concurrent.atomic.AtomicLong alertSeq =
            new java.util.concurrent.atomic.AtomicLong(-1L);

    /**
     * 取号、去重判定与插入的互斥锁。
     *
     * 告警编号是「查占用 + 取下一个」的读改写序列，多个请求并发填报时两个线程可能拿到同一个编号；
     * 去重判定也需要与插入保持原子，否则并发下会写入同一告警的多条副本。
     * 锁只保护极短的数据库写片段，跨实例场景由唯一键冲突重试兜底。
     */
    private final Object alertIdLock = new Object();

    private final AlertMapper alertMapper;
    private final EmissionRecordMapper emissionRecordMapper;
    private final ComplianceService complianceService;

    public AlertServiceImpl(AlertMapper alertMapper,
                            EmissionRecordMapper emissionRecordMapper,
                            ComplianceService complianceService) {
        this.alertMapper = alertMapper;
        this.emissionRecordMapper = emissionRecordMapper;
        this.complianceService = complianceService;
    }

    /**
     * 按级别、状态过滤查询告警，按触发时间倒序；参数为空表示该条件不限制。
     *
     * @param level  告警级别（high / medium / low），空则不筛选
     * @param status 处理状态（pending / resolved），空则不筛选
     * @return 告警列表
     */
    @Override
    public List<Alert> list(String level, String status) {
        LambdaQueryWrapper<Alert> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(level)) {
            wrapper.eq(Alert::getLevel, level);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(Alert::getStatus, status);
        }
        wrapper.orderByDesc(Alert::getCreatedAt);
        return alertMapper.selectList(wrapper);
    }

    /**
     * 按编号查询告警详情。
     *
     * @param id 告警编号（ALT 开头）
     * @return 告警记录
     * @throws ApiException 404：告警不存在
     */
    @Override
    public Alert getById(String id) {
        Alert alert = alertMapper.selectById(id);
        if (alert == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "告警不存在");
        }
        return alert;
    }

    /**
     * 处理告警：只把状态置为 resolved 并落库，保留级别、文案与触发时间作为处理留痕。
     *
     * @param id 告警编号
     * @return 更新后的告警
     * @throws ApiException 404：告警不存在
     */
    @Override
    public Alert resolve(String id) {
        Alert alert = getById(id);
        alert.setStatus("resolved");
        alertMapper.updateById(alert);
        return alert;
    }

    /**
     * 写入时判定：新增排放记录入库后调用，按合规规则生成待处理告警。
     * 判定优先交给链上合约（checkAll），返回的位掩码逐位映射为告警类型与级别；
     * 链上判定不可用时回退本地规则，保证填报主流程不因链故障中断。
     * 由于按「已入库」的合计判定，调用方必须在 insert 之后执行。
     *
     * @param record 已入库的排放记录（其排放量已计入本期/年度合计）
     */
    @Override
    public void evaluateOnEmissionCreate(EmissionRecord record) {
        ComplianceResult result = complianceService.evaluateForRecord(record);
        if (result.isOnChain()) {
            // 判定由合约执行：按命中的规则位生成告警（级别与文案由本类生成）
            for (ComplianceFlags.Rule rule : ComplianceFlags.rules(result.getFlags())) {
                save(record, rule.type(), rule.typeLabel(), rule.level(), messageOf(rule.rule(), record, result));
            }
            return;
        }
        // 链上判定不可用：回退本地规则，保证填报流程不中断（notice 已在日志中提示）
        log.warn("合规判定回退本地规则：{}", result.getNotice());
        checkFluctuation(record);
        checkMissingActivityData(record);
        checkFactorMismatch(record);
        checkLateReport(record);
    }

    /**
     * 删除排放记录时清理该企业该报告期下的全部告警（不限类型与级别），避免残留失效告警。
     * 任一参数为空立即返回：条件缺失时若继续删除会退化为按单条件甚至全表删除。
     *
     * @param enterpriseId 企业编号
     * @param period       报告期（yyyy-MM）
     */
    @Override
    public void removeByEnterpriseAndPeriod(String enterpriseId, String period) {
        if (!StringUtils.hasText(enterpriseId) || !StringUtils.hasText(period)) {
            return;
        }
        alertMapper.delete(new LambdaQueryWrapper<Alert>()
                .eq(Alert::getEnterpriseId, enterpriseId)
                .eq(Alert::getPeriod, period));
    }

    /**
     * 记录链上同步最终失败告警（type=chain_failed，level=high），纳入监管端待处理告警流。
     * 缺少企业或报告期时不满足告警表的列约束，只记错误日志、不落库。
     *
     * @param enterpriseId   企业编号，为空则不落库
     * @param enterpriseName 企业名称，可为空
     * @param period         报告期，为空则不落库
     * @param message        失败原因（含上链动作描述）
     */
    @Override
    public void reportChainFailure(String enterpriseId, String enterpriseName, String period, String message) {
        if (!StringUtils.hasText(enterpriseId) || !StringUtils.hasText(period)) {
            // 缺少企业与报告期时无法落到告警表（列约束），仅记录日志
            log.error("链上同步失败且缺少企业与报告期，无法生成告警：{}", message);
            return;
        }
        save(enterpriseId, enterpriseName, period, "chain_failed", "链上同步失败", "high", message);
    }

    /** 按规则生成中文文案（判定数值来自合约返回） */
    private String messageOf(String rule, EmissionRecord record, ComplianceResult result) {
        switch (rule) {
            case "quota":
                return String.format("%s 年度累计排放量 %s tCO₂ 已超出链上年度配额 %s tCO₂，请核查。",
                        yearOf(record.getPeriod()),
                        plain(result.getYearToDate()),
                        plain(result.getQuota()));
            case "fluctuation": {
                String previousPeriod = previousPeriodOf(record);
                long absBp = Math.abs(result.getDeltaBp());
                String direction = result.getDeltaBp() >= 0 ? "增长" : "下降";
                return String.format("%s月排放量较上一报告期（%s）环比%s %s%%，超出链上合约配置的波动阈值（±%s%%），请核查。",
                        record.getPeriod(),
                        previousPeriod == null ? "-" : previousPeriod,
                        direction,
                        percentText(absBp),
                        percentText(result.getThresholdBp()));
            }
            case "activity":
                return String.format("%s月%s的活动数据为 0，可能存在漏填，请核实后重新填报。",
                        record.getPeriod(), record.getSourceName());
            case "factor":
                return String.format("本期核算所用排放因子 %s 与链上记录的该企业历史使用值 %s 不一致，建议复核核算口径。",
                        plain(record.getEmissionFactor()), plain(result.getPreviousFactor()));
            case "late":
                return String.format("%s月排放数据未在截止日期前上报，属补报数据，请核查迟报原因。", record.getPeriod());
            default:
                return String.format("%s月排放数据判定异常（规则 %s），请核查。", record.getPeriod(), rule);
        }
    }

    /** 规则②（本地回退）：本期排放量较上一报告期环比波动超过阈值 → 高危数据异常 */
    private void checkFluctuation(EmissionRecord record) {
        BigDecimal currentTotal = sumAmount(record.getEnterpriseId(), record.getPeriod());
        String previousPeriod = previousPeriodOf(record);
        if (previousPeriod == null) {
            return;
        }
        // 当前记录已入库，本期合计包含自身；上一报告期没有有效排放时无法计算环比，跳过
        BigDecimal previousTotal = sumAmount(record.getEnterpriseId(), previousPeriod);
        if (previousTotal.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        BigDecimal delta = currentTotal.subtract(previousTotal)
                .multiply(new BigDecimal("100"))
                .divide(previousTotal, 1, RoundingMode.HALF_UP);
        if (delta.abs().compareTo(FLUCTUATION_THRESHOLD) <= 0) {
            return;
        }

        String direction = delta.signum() > 0 ? "增长" : "下降";
        String message = String.format("%s月排放量较上一报告期（%s）环比%s %s%%，超出正常波动阈值（±20%%），请核查。",
                record.getPeriod(), previousPeriod, direction, delta.abs().toPlainString());
        save(record, "data_anomaly", "数据异常", "high", message);
    }

    /** 规则③（本地回退）：活动数据为 0，疑似漏填 → 低危数据异常 */
    private void checkMissingActivityData(EmissionRecord record) {
        if (record.getActivityData() == null || record.getActivityData().compareTo(BigDecimal.ZERO) != 0) {
            return;
        }
        String message = String.format("%s月%s的活动数据为 0，可能存在漏填，请核实后重新填报。",
                record.getPeriod(), record.getSourceName());
        save(record, "data_anomaly", "数据异常", "low", message);
    }

    /** 规则④（本地回退）：本次核算所用排放因子与该企业同排放源历史使用值不一致 → 中危因子不符 */
    private void checkFactorMismatch(EmissionRecord record) {
        if (record.getEmissionFactor() == null) {
            return;
        }
        List<EmissionRecord> history = emissionRecordMapper.selectList(new LambdaQueryWrapper<EmissionRecord>()
                .eq(EmissionRecord::getEnterpriseId, record.getEnterpriseId())
                .eq(EmissionRecord::getSourceCode, record.getSourceCode())
                .ne(EmissionRecord::getId, record.getId())
                .orderByDesc(EmissionRecord::getCreatedAt));
        if (history.isEmpty()) {
            return;
        }
        // 以最近一次同企业同排放源的填报因子为基准（按创建时间倒序的第一条）
        BigDecimal previousFactor = history.get(0).getEmissionFactor();
        if (previousFactor == null || previousFactor.compareTo(record.getEmissionFactor()) == 0) {
            return;
        }
        String message = String.format("本期核算所用排放因子 %s 与该企业历史使用值 %s 不一致，建议复核核算口径。",
                record.getEmissionFactor().toPlainString(), previousFactor.toPlainString());
        save(record, "factor_mismatch", "因子不符", "medium", message);
    }

    /** 规则⑤（本地回退）：报告期早于当前月份超过阈值月数 → 中危迟报（补报） */
    private void checkLateReport(EmissionRecord record) {
        if (!StringUtils.hasText(record.getPeriod())) {
            return;
        }
        YearMonth periodMonth;
        try {
            periodMonth = YearMonth.parse(record.getPeriod());
        } catch (DateTimeParseException e) {
            // 报告期格式非法视为脏数据，跳过迟报判定，避免误报告警
            return;
        }
        if (!periodMonth.isBefore(YearMonth.now().minusMonths(LATE_REPORT_MONTHS))) {
            return;
        }
        String message = String.format("%s月排放数据未在截止日期前上报，属补报数据，请核查迟报原因。", record.getPeriod());
        save(record, "late_report", "迟报", "medium", message);
    }

    /** 统计某企业某报告期的排放量合计 */
    private BigDecimal sumAmount(String enterpriseId, String period) {
        List<EmissionRecord> records = emissionRecordMapper.selectList(new LambdaQueryWrapper<EmissionRecord>()
                .eq(EmissionRecord::getEnterpriseId, enterpriseId)
                .eq(EmissionRecord::getPeriod, period));
        BigDecimal total = BigDecimal.ZERO;
        for (EmissionRecord item : records) {
            if (item.getAmount() != null) {
                total = total.add(item.getAmount());
            }
        }
        return total;
    }

    /** 按排放记录落库告警 */
    private void save(EmissionRecord record, String type, String typeLabel, String level, String message) {
        save(record.getEnterpriseId(), record.getEnterpriseName(), record.getPeriod(),
                type, typeLabel, level, message);
    }

    /**
     * 落库一条待处理告警。
     * 同一企业、同一报告期、同一类型且同一级别的告警只保留一条：
     * 既避免重复填报时堆叠完全相同的告警，又能保留同一期内不同级别的不同发现。
     *
     * 去重判定、取号与插入必须在同一把锁内完成，否则并发填报会出现重复告警或同号主键冲突；
     * 锁只保护数据库写入的极短片段，不影响其它业务。
     */
    private void save(String enterpriseId, String enterpriseName, String period,
                      String type, String typeLabel, String level, String message) {
        Alert alert = new Alert();
        alert.setEnterpriseId(enterpriseId);
        alert.setEnterpriseName(enterpriseName);
        alert.setType(type);
        alert.setTypeLabel(typeLabel);
        alert.setLevel(level);
        alert.setMessage(message);
        alert.setPeriod(period);
        alert.setStatus("pending");
        alert.setCreatedAt(LocalDateTime.now());

        synchronized (alertIdLock) {
            Long exists = alertMapper.selectCount(new LambdaQueryWrapper<Alert>()
                    .eq(Alert::getEnterpriseId, enterpriseId)
                    .eq(Alert::getPeriod, period)
                    .eq(Alert::getType, type)
                    .eq(Alert::getLevel, level));
            if (exists != null && exists > 0) {
                return;
            }
            insertWithUniqueId(alert);
        }
    }

    /**
     * 取一个未被占用的编号并插入；若仍因唯一键冲突失败（多实例部署或外部写入），
     * 重新取号重试一次，避免整笔业务因告警落库失败而回滚。
     */
    private void insertWithUniqueId(Alert alert) {
        alert.setId(nextAlertId());
        try {
            alertMapper.insert(alert);
        } catch (DuplicateKeyException e) {
            log.warn("告警编号 {} 已被占用，重新取号后重试", alert.getId());
            alert.setId(nextAlertId());
            alertMapper.insert(alert);
        }
    }

    /** 该企业早于本报告期、且最近的一个报告期（用于环比对比与文案展示） */
    private String previousPeriodOf(EmissionRecord record) {
        List<EmissionRecord> history = emissionRecordMapper.selectList(new LambdaQueryWrapper<EmissionRecord>()
                .eq(EmissionRecord::getEnterpriseId, record.getEnterpriseId())
                .lt(EmissionRecord::getPeriod, record.getPeriod())
                .orderByDesc(EmissionRecord::getPeriod));
        return history.isEmpty() ? null : history.get(0).getPeriod();
    }

    /**
     * 从报告期（{@code YYYY-MM} 或 {@code YYYYMM}）中截取前 4 位解析年份，仅供告警文案展示使用。
     *
     * 解析失败（period 为 null、长度不足 4 位、或前 4 位不是数字）时不抛异常，而是回退为系统当前年份，
     * 保证文案生成始终可用；该结果不参与合规判定。
     *
     * @param period 报告期字符串，可为 null
     * @return 年份；无法解析时返回当前年份
     */
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

    /** 数值展示：去掉多余小数位 */
    private static String plain(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    /** 基点 → 百分比文本（2530 → "25.3"） */
    private static String percentText(long bp) {
        return BigDecimal.valueOf(bp).divide(new BigDecimal("100"), 1, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    /**
     * 生成告警编号：ALT + 3 位序号（超过 999 自动加宽）。
     * 使用进程内自增计数器取号，并跳过已被占用的编号。
     * 必须在持有 {@link #alertIdLock} 时调用，保证「取号 + 插入」对其他线程是原子的。
     */
    private String nextAlertId() {
        long seq = alertSeq.get();
        if (seq < 0) {
            alertSeq.compareAndSet(-1L, maxExistingAlertSeq());
            seq = alertSeq.get();
        }
        String id;
        do {
            seq = alertSeq.incrementAndGet();
            id = "ALT" + String.format("%03d", seq);
        } while (alertMapper.selectById(id) != null);
        return id;
    }

    /** alerts 表现有最大编号的序号（ALT### → 数字），无数据或格式异常返回 0 */
    private long maxExistingAlertSeq() {
        List<Alert> alerts = alertMapper.selectList(
                new LambdaQueryWrapper<Alert>().select(Alert::getId));
        long max = 0L;
        for (Alert alert : alerts) {
            String id = alert.getId();
            if (id == null || id.length() <= 3) {
                continue;
            }
            try {
                max = Math.max(max, Long.parseLong(id.substring(3)));
            } catch (NumberFormatException ignored) {
                // 非标准编号忽略，不影响取号
            }
        }
        return max;
    }
}
