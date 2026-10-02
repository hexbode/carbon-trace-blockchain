package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.entity.Alert;
import com.carbon.trace.entity.EmissionRecord;
import com.carbon.trace.mapper.AlertMapper;
import com.carbon.trace.mapper.EmissionRecordMapper;
import com.carbon.trace.service.AlertService;
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
 *   <li>数据异常（高危）：本期排放量较上一报告期环比波动超过 ±20%；</li>
 *   <li>数据异常（低危）：本期活动数据为 0，疑似漏填；</li>
 *   <li>因子不符（中危）：本次核算所用排放因子与该企业同排放源历史使用值不一致；</li>
 *   <li>迟报（中危）：报告期早于当前月份 1 个月以上，属补报历史数据。</li>
 * </ol>
 */
@Service
public class AlertServiceImpl implements AlertService {

    /** 环比波动阈值（%）：超出即判定为数据异常 */
    private static final BigDecimal FLUCTUATION_THRESHOLD = new BigDecimal("20");

    /** 迟报判定：报告期早于当前月份超过该月数即视为迟报 */
    private static final long LATE_REPORT_MONTHS = 1;

    private final AlertMapper alertMapper;
    private final EmissionRecordMapper emissionRecordMapper;

    public AlertServiceImpl(AlertMapper alertMapper, EmissionRecordMapper emissionRecordMapper) {
        this.alertMapper = alertMapper;
        this.emissionRecordMapper = emissionRecordMapper;
    }

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

    @Override
    public Alert getById(String id) {
        Alert alert = alertMapper.selectById(id);
        if (alert == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "告警不存在");
        }
        return alert;
    }

    @Override
    public Alert resolve(String id) {
        Alert alert = getById(id);
        alert.setStatus("resolved");
        alertMapper.updateById(alert);
        return alert;
    }

    @Override
    public void evaluateOnEmissionCreate(EmissionRecord record) {
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

    /** 规则①：本期排放量较上一报告期环比波动超过阈值 → 高危数据异常 */
    private void checkFluctuation(EmissionRecord record) {
        BigDecimal currentTotal = sumAmount(record.getEnterpriseId(), record.getPeriod());

        // 取该企业早于本期、且最近的一个报告期作为对比基准
        List<EmissionRecord> history = emissionRecordMapper.selectList(new LambdaQueryWrapper<EmissionRecord>()
                .eq(EmissionRecord::getEnterpriseId, record.getEnterpriseId())
                .lt(EmissionRecord::getPeriod, record.getPeriod())
                .orderByDesc(EmissionRecord::getPeriod));
        if (history.isEmpty()) {
            return;
        }
        String previousPeriod = history.get(0).getPeriod();
        BigDecimal previousTotal = BigDecimal.ZERO;
        for (EmissionRecord item : history) {
            if (previousPeriod.equals(item.getPeriod()) && item.getAmount() != null) {
                previousTotal = previousTotal.add(item.getAmount());
            }
        }
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

    /** 规则②：活动数据为 0，疑似漏填 → 低危数据异常 */
    private void checkMissingActivityData(EmissionRecord record) {
        if (record.getActivityData() == null || record.getActivityData().compareTo(BigDecimal.ZERO) != 0) {
            return;
        }
        String message = String.format("%s月%s的活动数据为 0，可能存在漏填，请核实后重新填报。",
                record.getPeriod(), record.getSourceName());
        save(record, "data_anomaly", "数据异常", "low", message);
    }

    /** 规则③：本次核算所用排放因子与该企业同排放源历史使用值不一致 → 中危因子不符 */
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

    /** 规则④：报告期早于当前月份 1 个月以上 → 中危迟报（补报） */
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

    /**
     * 落库一条待处理告警。
     * 同一企业、同一报告期、同一类型且同一级别的告警只保留一条：
     * 既避免重复填报时堆叠完全相同的告警，又能保留同一期内不同级别的不同发现。
     */
    private void save(EmissionRecord record, String type, String typeLabel, String level, String message) {
        Long exists = alertMapper.selectCount(new LambdaQueryWrapper<Alert>()
                .eq(Alert::getEnterpriseId, record.getEnterpriseId())
                .eq(Alert::getPeriod, record.getPeriod())
                .eq(Alert::getType, type)
                .eq(Alert::getLevel, level));
        if (exists != null && exists > 0) {
            return;
        }

        Alert alert = new Alert();
        alert.setId(nextAlertId());
        alert.setEnterpriseId(record.getEnterpriseId());
        alert.setEnterpriseName(record.getEnterpriseName());
        alert.setType(type);
        alert.setTypeLabel(typeLabel);
        alert.setLevel(level);
        alert.setMessage(message);
        alert.setPeriod(record.getPeriod());
        alert.setStatus("pending");
        alert.setCreatedAt(LocalDateTime.now());
        alertMapper.insert(alert);
    }

    /** 生成告警编号：ALT + 3 位序号，跳过已占用的编号 */
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
