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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;

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

    public ComplianceServiceImpl(ChainGateway chainGateway,
                                 ChainProperties properties,
                                 EmissionRecordMapper emissionRecordMapper) {
        this.chainGateway = chainGateway;
        this.properties = properties;
        this.emissionRecordMapper = emissionRecordMapper;
    }

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
                fallback.setOnChain(false);
                fallback.setFlags(ComplianceResult.FLAGS_UNKNOWN);
                fallback.setNotice("链上合规判定不可用，已回退本地规则：" + e.getMessage());
                return fallback;
            }
            throw e;
        }
    }

    @Override
    public ComplianceResult evaluateForRecord(EmissionRecord record) {
        return evaluate(buildInput(record.getEnterpriseId(), record.getPeriod(), record.getSourceCode(),
                record.getActivityData(), record.getEmissionFactor(), null));
    }

    @Override
    public ComplianceResult evaluateByEmissionId(String emissionId) {
        EmissionRecord record = emissionRecordMapper.selectById(emissionId);
        if (record == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "排放记录不存在：" + emissionId);
        }
        return evaluateForRecord(record);
    }

    @Override
    public ComplianceResult evaluateForCheck(String enterpriseId, String period, String sourceCode,
                                             BigDecimal activityData, BigDecimal factor) {
        // 试算场景记录尚未入库，把本次填报量计入本期与年度合计
        BigDecimal amount = null;
        if (activityData != null && factor != null) {
            amount = activityData.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        }
        return evaluate(buildInput(enterpriseId, period, sourceCode, activityData, factor, amount));
    }

    @Override
    public int flagsForCertify(String emissionId) {
        ComplianceResult result = evaluateByEmissionId(emissionId);
        return Math.max(0, result.getFlags());
    }

    @Override
    public int certificateFlags(String certId) {
        return chainGateway.findByCertId(certId)
                .map(cert -> cert.getComplianceFlags())
                .orElse(0);
    }

    @Override
    public int flagsForFingerprint(String fingerprint) {
        return chainGateway.findByFingerprint(fingerprint)
                .map(cert -> cert.getComplianceFlags())
                .orElse(0);
    }

    @Override
    public void syncMirrors(EmissionRecord record) {
        if (record == null || !StringUtils.hasText(record.getEnterpriseId())
                || !StringUtils.hasText(record.getPeriod())) {
            return;
        }
        syncTotals(record.getEnterpriseId(), record.getPeriod());
        if (record.getEmissionFactor() != null && StringUtils.hasText(record.getSourceCode())) {
            chainGateway.rememberFactor(record.getEnterpriseId(), record.getSourceCode(), record.getEmissionFactor());
        }
    }

    @Override
    public void syncMirrors(String enterpriseId, String period) {
        if (!StringUtils.hasText(enterpriseId) || !StringUtils.hasText(period)) {
            return;
        }
        syncTotals(enterpriseId, period);
    }

    /** 重算并覆盖链上的本期合计与年度合计镜像 */
    private void syncTotals(String enterpriseId, String period) {
        int year = yearOf(period);
        chainGateway.syncPeriodTotal(enterpriseId, period, sumAmount(enterpriseId, period));
        chainGateway.syncYearTotal(enterpriseId, year, sumAmountInYear(enterpriseId, year));
    }

    @Override
    public BigDecimal quotaOf(String enterpriseId, int year) {
        return chainGateway.annualQuotaOf(enterpriseId, year);
    }

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
    }

    @Override
    public int fluctuationThresholdBp() {
        return chainGateway.fluctuationThresholdBp();
    }

    @Override
    public void setFluctuationThresholdBp(int bp) {
        if (bp <= 0 || bp > 10000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "环比阈值非法（需大于 0 且不超过 10000 基点）");
        }
        chainGateway.setFluctuationThresholdBp(bp);
    }

    @Override
    public int lateReportMonths() {
        return chainGateway.lateReportMonths();
    }

    @Override
    public void setLateReportMonths(int months) {
        if (months < 0 || months > 24) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "迟报月数非法（0~24）");
        }
        chainGateway.setLateReportMonths(months);
    }

    // ---------------- 内部：输入聚合 ----------------

    /**
     * 组装判定输入。
     * @param extraAmount 尚未入库的增量（填报试算用）；已入库的场景传 null，合计由数据库聚合
     */
    private ComplianceInput buildInput(String enterpriseId, String period, String sourceCode,
                                       BigDecimal activityData, BigDecimal factor, BigDecimal extraAmount) {
        ComplianceInput input = new ComplianceInput();
        input.setEnterpriseId(enterpriseId);
        input.setPeriod(period);
        input.setSourceCode(sourceCode);
        input.setActivityData(activityData);
        input.setFactor(factor);
        input.setPeriodYyyymm(AmountCodec.toYyyymm(period));
        input.setYear(yearOf(period));

        YearMonth now = YearMonth.now();
        input.setCurrentYyyymm(now.getYear() * 100L + now.getMonthValue());

        BigDecimal currentTotal = sumAmount(enterpriseId, period);
        BigDecimal yearToDate = sumAmountInYear(enterpriseId, input.getYear());
        if (extraAmount != null) {
            currentTotal = currentTotal.add(extraAmount);
            yearToDate = yearToDate.add(extraAmount);
        }
        input.setCurrentTotal(currentTotal);
        input.setYearToDate(yearToDate);

        String previousPeriod = previousPeriodOf(enterpriseId, period);
        input.setPreviousTotal(previousPeriod == null
                ? BigDecimal.ZERO
                : sumAmount(enterpriseId, previousPeriod));
        return input;
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

    /** 该企业早于本报告期、且最近的一个报告期 */
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

    private BigDecimal sumAmount(String enterpriseId, String period) {
        if (!StringUtils.hasText(period)) {
            return BigDecimal.ZERO;
        }
        return sumOf(emissionRecordMapper.selectList(new LambdaQueryWrapper<EmissionRecord>()
                .eq(EmissionRecord::getEnterpriseId, enterpriseId)
                .eq(EmissionRecord::getPeriod, period)));
    }

    private BigDecimal sumAmountInYear(String enterpriseId, int year) {
        List<EmissionRecord> records = emissionRecordMapper.selectList(new LambdaQueryWrapper<EmissionRecord>()
                .eq(EmissionRecord::getEnterpriseId, enterpriseId)
                .likeRight(EmissionRecord::getPeriod, year + "-"));
        return sumOf(records);
    }

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
