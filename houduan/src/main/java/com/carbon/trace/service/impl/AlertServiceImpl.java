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

    /** 按级别、状态过滤查询告警，按触发时间倒序 */
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

    /** 按编号查询告警，不存在抛 404 */
    @Override
    public Alert getById(String id) {
        Alert alert = alertMapper.selectById(id);
        if (alert == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "告警不存在");
        }
        return alert;
    }

    /** 处理告警：状态置为 resolved */
    @Override
    public Alert resolve(String id) {
        Alert alert = getById(id);
        alert.setStatus("resolved");
        alertMapper.updateById(alert);
        return alert;
    }

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

    @Override
    public void removeByEnterpriseAndPeriod(String enterpriseId, String period) {
        if (!StringUtils.hasText(enterpriseId) || !StringUtils.hasText(period)) {
            return;
        }
        alertMapper.delete(new LambdaQueryWrapper<Alert>()
                .eq(Alert::getEnterpriseId, enterpriseId)
                .eq(Alert::getPeriod, period));
    }

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
     */
    private void save(String enterpriseId, String enterpriseName, String period,
                      String type, String typeLabel, String level, String message) {
        Long exists = alertMapper.selectCount(new LambdaQueryWrapper<Alert>()
                .eq(Alert::getEnterpriseId, enterpriseId)
                .eq(Alert::getPeriod, period)
                .eq(Alert::getType, type)
                .eq(Alert::getLevel, level));
        if (exists != null && exists > 0) {
            return;
        }

        Alert alert = new Alert();
        alert.setId(nextAlertId());
        alert.setEnterpriseId(enterpriseId);
        alert.setEnterpriseName(enterpriseName);
        alert.setType(type);
        alert.setTypeLabel(typeLabel);
        alert.setLevel(level);
        alert.setMessage(message);
        alert.setPeriod(period);
        alert.setStatus("pending");
        alert.setCreatedAt(LocalDateTime.now());
        alertMapper.insert(alert);
    }

    /** 该企业早于本报告期、且最近的一个报告期（用于环比对比与文案展示） */
    private String previousPeriodOf(EmissionRecord record) {
        List<EmissionRecord> history = emissionRecordMapper.selectList(new LambdaQueryWrapper<EmissionRecord>()
                .eq(EmissionRecord::getEnterpriseId, record.getEnterpriseId())
                .lt(EmissionRecord::getPeriod, record.getPeriod())
                .orderByDesc(EmissionRecord::getPeriod));
        return history.isEmpty() ? null : history.get(0).getPeriod();
    }

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

    /** 生成告警编号：ALT + 3 位序号；基于总数递增，并循环跳过已被占用的编号 */
    private String nextAlertId() {
        long seq = alertMapper.selectCount(null);
        String id;
        do {
            seq++;
            id = "ALT" + String.format("%03d", seq);
        } while (alertMapper.selectById(id) != null);
        return id;
    }
}
