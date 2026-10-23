package com.carbon.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.trace.entity.Alert;
import com.carbon.trace.entity.CertificationRecord;
import com.carbon.trace.entity.EmissionRecord;
import com.carbon.trace.entity.Enterprise;
import com.carbon.trace.entity.ReportRecord;
import com.carbon.trace.mapper.AlertMapper;
import com.carbon.trace.mapper.CertificationRecordMapper;
import com.carbon.trace.mapper.EmissionRecordMapper;
import com.carbon.trace.mapper.EnterpriseMapper;
import com.carbon.trace.mapper.ReportRecordMapper;
import com.carbon.trace.service.StatsService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
// import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 统计服务实现（数据量小，直接在内存中聚合）。
 * 面向各端首页图表，按角色分四个区块（监管端/企业端/审核端/公共端）从各 Mapper 全量读取后在内存聚合，
 * 不写库、不写链；金额统一经 {@link #scale} 保留 2 位小数，占比经 {@link #percent} 保留 1 位小数。
 * 趋势类指标的月份轴由数据中实际出现过的报告期决定（无数据时回退最近 12 个月），不再硬编码年份。
 */
@Service
public class StatsServiceImpl implements StatsService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** 报告期格式：yyyy-MM（月份限定 01~12），与 emission_records.period 的存储口径一致 */
    private static final Pattern PERIOD_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private final EnterpriseMapper enterpriseMapper;
    private final EmissionRecordMapper emissionRecordMapper;
    private final CertificationRecordMapper certificationRecordMapper;
    private final ReportRecordMapper reportRecordMapper;
    private final AlertMapper alertMapper;

    public StatsServiceImpl(EnterpriseMapper enterpriseMapper,
                            EmissionRecordMapper emissionRecordMapper,
                            CertificationRecordMapper certificationRecordMapper,
                            ReportRecordMapper reportRecordMapper,
                            AlertMapper alertMapper) {
        this.enterpriseMapper = enterpriseMapper;
        this.emissionRecordMapper = emissionRecordMapper;
        this.certificationRecordMapper = certificationRecordMapper;
        this.reportRecordMapper = reportRecordMapper;
        this.alertMapper = alertMapper;
    }

    // ==================== 监管端 ====================

    @Override
    public Map<String, Object> regulatorOverview() {
        List<Enterprise> enterprises = enterpriseMapper.selectList(null);
        List<EmissionRecord> records = emissionRecordMapper.selectList(null);
        List<CertificationRecord> certs = certificationRecordMapper.selectList(null);
        List<Alert> alerts = alertMapper.selectList(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enterpriseCount", enterprises.size());
        result.put("totalEmission", sumAmount(records));
        result.put("certCount", certs.stream().filter(c -> "valid".equals(c.getStatus())).count());
        result.put("pendingAlerts", alerts.stream().filter(a -> "pending".equals(a.getStatus())).count());
        return result;
    }

    @Override
    public List<Map<String, Object>> regulatorIndustryEmission() {
        List<Enterprise> enterprises = enterpriseMapper.selectList(null);
        Map<String, Enterprise> enterpriseMap = toEnterpriseMap(enterprises);

        Map<String, BigDecimal> byIndustry = new LinkedHashMap<>();
        for (Enterprise enterprise : enterprises) {
            byIndustry.putIfAbsent(enterprise.getIndustry(), ZERO);
        }
        for (EmissionRecord record : emissionRecordMapper.selectList(null)) {
            Enterprise enterprise = enterpriseMap.get(record.getEnterpriseId());
            if (enterprise != null) {
                byIndustry.merge(enterprise.getIndustry(), nz(record.getAmount()), BigDecimal::add);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        byIndustry.forEach((industry, amount) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("industry", industry);
            item.put("amount", scale(amount));
            result.add(item);
        });
        return result;
    }

    @Override
    public Map<String, Object> regulatorCertStatus() {
        List<EmissionRecord> records = emissionRecordMapper.selectList(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("certified", countStatus(records, "certified"));
        result.put("pending", countStatus(records, "pending"));
        result.put("rejected", countStatus(records, "rejected"));
        return result;
    }

    @Override
    public List<Map<String, Object>> regulatorIndustrySummary() {
        List<Enterprise> enterprises = enterpriseMapper.selectList(null);
        Map<String, Enterprise> enterpriseMap = toEnterpriseMap(enterprises);

        Map<String, Integer> enterpriseCount = new LinkedHashMap<>();
        Map<String, BigDecimal> emission = new LinkedHashMap<>();
        Map<String, Integer> certCount = new LinkedHashMap<>();
        for (Enterprise enterprise : enterprises) {
            enterpriseCount.merge(enterprise.getIndustry(), 1, Integer::sum);
            emission.putIfAbsent(enterprise.getIndustry(), ZERO);
            certCount.putIfAbsent(enterprise.getIndustry(), 0);
        }
        for (EmissionRecord record : emissionRecordMapper.selectList(null)) {
            Enterprise enterprise = enterpriseMap.get(record.getEnterpriseId());
            if (enterprise != null) {
                emission.merge(enterprise.getIndustry(), nz(record.getAmount()), BigDecimal::add);
            }
        }
        for (CertificationRecord cert : certificationRecordMapper.selectList(null)) {
            Enterprise enterprise = enterpriseMap.get(cert.getEnterpriseId());
            if (enterprise != null && "valid".equals(cert.getStatus())) {
                certCount.merge(enterprise.getIndustry(), 1, Integer::sum);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (String industry : enterpriseCount.keySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("industry", industry);
            item.put("enterpriseCount", enterpriseCount.get(industry));
            item.put("emission", scale(emission.getOrDefault(industry, ZERO)));
            item.put("certCount", certCount.getOrDefault(industry, 0));
            result.add(item);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> regulatorIndustryRanking() {
        Map<String, BigDecimal> byIndustry = new LinkedHashMap<>();
        Map<String, Enterprise> enterpriseMap = toEnterpriseMap(enterpriseMapper.selectList(null));
        for (EmissionRecord record : emissionRecordMapper.selectList(null)) {
            Enterprise enterprise = enterpriseMap.get(record.getEnterpriseId());
            if (enterprise != null) {
                byIndustry.merge(enterprise.getIndustry(), nz(record.getAmount()), BigDecimal::add);
            }
        }
        BigDecimal grand = byIndustry.values().stream().reduce(ZERO, BigDecimal::add);
        return byIndustry.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("industry", entry.getKey());
                    item.put("total", scale(entry.getValue()));
                    item.put("percent", percent(entry.getValue(), grand));
                    return item;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> regulatorEnterpriseRanking() {
        List<Enterprise> enterprises = enterpriseMapper.selectList(null);
        Map<String, BigDecimal> emission = new LinkedHashMap<>();
        Map<String, Integer> certCount = new LinkedHashMap<>();
        for (Enterprise enterprise : enterprises) {
            emission.put(enterprise.getId(), ZERO);
            certCount.put(enterprise.getId(), 0);
        }
        for (EmissionRecord record : emissionRecordMapper.selectList(null)) {
            if (emission.containsKey(record.getEnterpriseId())) {
                emission.merge(record.getEnterpriseId(), nz(record.getAmount()), BigDecimal::add);
            }
        }
        for (CertificationRecord cert : certificationRecordMapper.selectList(null)) {
            if ("valid".equals(cert.getStatus()) && certCount.containsKey(cert.getEnterpriseId())) {
                certCount.merge(cert.getEnterpriseId(), 1, Integer::sum);
            }
        }
        BigDecimal grand = emission.values().stream().reduce(ZERO, BigDecimal::add);
        List<Enterprise> sorted = new ArrayList<>(enterprises);
        sorted.sort((a, b) -> emission.get(b.getId()).compareTo(emission.get(a.getId())));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Enterprise enterprise : sorted) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", enterprise.getName());
            item.put("industry", enterprise.getIndustry());
            item.put("total", scale(emission.get(enterprise.getId())));
            item.put("percent", percent(emission.get(enterprise.getId()), grand));
            item.put("certCount", certCount.getOrDefault(enterprise.getId(), 0));
            result.add(item);
        }
        return result;
    }

    @Override
    public Map<String, Object> regulatorEnterpriseTrend() {
        List<Enterprise> enterprises = enterpriseMapper.selectList(null);
        Map<String, Enterprise> enterpriseMap = toEnterpriseMap(enterprises);
        List<EmissionRecord> records = emissionRecordMapper.selectList(null);
        // 月份轴取自真实数据：报告期升序去重（没有数据时回退最近 12 个月）
        List<String> months = monthAxis(records.stream().map(EmissionRecord::getPeriod).toList());

        // 行业 -> 月份 -> 排放量
        Map<String, Map<String, BigDecimal>> seriesData = new LinkedHashMap<>();
        Map<String, BigDecimal> zeros = emptyMonthMap(months);
        for (Enterprise enterprise : enterprises) {
            seriesData.putIfAbsent(enterprise.getIndustry(), new LinkedHashMap<>(zeros));
        }
        for (EmissionRecord record : records) {
            Enterprise enterprise = enterpriseMap.get(record.getEnterpriseId());
            if (enterprise == null || record.getPeriod() == null || !months.contains(record.getPeriod())) {
                continue;
            }
            Map<String, BigDecimal> monthMap =
                    seriesData.computeIfAbsent(enterprise.getIndustry(), k -> new LinkedHashMap<>(zeros));
            monthMap.merge(record.getPeriod(), nz(record.getAmount()), BigDecimal::add);
        }

        List<Map<String, Object>> series = new ArrayList<>();
        seriesData.forEach((industry, monthMap) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", industry);
            item.put("data", months.stream().map(m -> scale(monthMap.getOrDefault(m, ZERO))).collect(Collectors.toList()));
            series.add(item);
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("months", months);
        result.put("series", series);
        return result;
    }

    // ==================== 企业端 ====================

    @Override
    public Map<String, Object> enterpriseOverview(String enterpriseId) {
        List<EmissionRecord> records = emissionRecordMapper.selectList(
                new LambdaQueryWrapper<EmissionRecord>().eq(EmissionRecord::getEnterpriseId, enterpriseId));
        List<CertificationRecord> certs = certificationRecordMapper.selectList(
                new LambdaQueryWrapper<CertificationRecord>()
                        .eq(CertificationRecord::getEnterpriseId, enterpriseId)
                        .eq(CertificationRecord::getStatus, "valid"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalEmission", sumAmount(records));
        result.put("certifiedCount", countStatus(records, "certified"));
        result.put("pendingCount", countStatus(records, "pending"));
        result.put("certCount", certs.size());
        return result;
    }

    @Override
    public Map<String, Object> enterpriseTrend(String enterpriseId) {
        List<EmissionRecord> records = emissionRecordMapper.selectList(
                new LambdaQueryWrapper<EmissionRecord>().eq(EmissionRecord::getEnterpriseId, enterpriseId));
        // 月份轴取自该企业真实报告期；没有记录时回退最近 12 个月
        List<String> months = monthAxis(records.stream().map(EmissionRecord::getPeriod).toList());
        Map<String, BigDecimal> monthMap = emptyMonthMap(months);
        for (EmissionRecord record : records) {
            if (record.getPeriod() != null && monthMap.containsKey(record.getPeriod())) {
                monthMap.merge(record.getPeriod(), nz(record.getAmount()), BigDecimal::add);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("months", months);
        result.put("data", months.stream().map(m -> scale(monthMap.getOrDefault(m, ZERO))).collect(Collectors.toList()));
        return result;
    }

    @Override
    public List<Map<String, Object>> enterpriseSourceShare(String enterpriseId) {
        List<EmissionRecord> records = emissionRecordMapper.selectList(
                new LambdaQueryWrapper<EmissionRecord>().eq(EmissionRecord::getEnterpriseId, enterpriseId));
        Map<String, BigDecimal> bySource = new LinkedHashMap<>();
        for (EmissionRecord record : records) {
            String name = record.getSourceName() == null ? "其他" : record.getSourceName();
            bySource.merge(name, nz(record.getAmount()), BigDecimal::add);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        bySource.forEach((name, value) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("value", scale(value));
            result.add(item);
        });
        return result;
    }

    // ==================== 审核端 ====================

    @Override
    public Map<String, Object> auditorOverview() {
        List<ReportRecord> reports = reportRecordMapper.selectList(null);
        LocalDate today = LocalDate.now();

        long pending = reports.stream().filter(r -> "pending".equals(r.getStatus())).count();
        long todayDone = reports.stream()
                .filter(r -> r.getAuditedAt() != null && r.getAuditedAt().toLocalDate().equals(today))
                .count();
        long totalDone = reports.stream()
                .filter(r -> "certified".equals(r.getStatus()) || "rejected".equals(r.getStatus()))
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", pending);
        result.put("todayDone", todayDone);
        result.put("totalDone", totalDone);
        return result;
    }

    @Override
    public Map<String, Object> auditorRecent7Days() {
        List<ReportRecord> reports = reportRecordMapper.selectList(null);
        LocalDate today = LocalDate.now();

        List<String> days = new ArrayList<>();
        List<Long> data = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            days.add(weekdayLabel(date.getDayOfWeek()));
            long count = reports.stream()
                    .filter(r -> r.getAuditedAt() != null && r.getAuditedAt().toLocalDate().equals(date))
                    .count();
            data.add(count);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", days);
        result.put("data", data);
        return result;
    }

    @Override
    public List<Map<String, Object>> auditorTodayList() {
        LocalDate today = LocalDate.now();
        List<ReportRecord> reports = reportRecordMapper.selectList(
                new LambdaQueryWrapper<ReportRecord>().orderByDesc(ReportRecord::getAuditedAt));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ReportRecord report : reports) {
            if (report.getAuditedAt() == null || !report.getAuditedAt().toLocalDate().equals(today)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", report.getId());
            item.put("enterpriseName", report.getEnterpriseName());
            item.put("period", report.getPeriod());
            item.put("sourceName", report.getSourceName());
            item.put("amount", report.getAmount());
            item.put("auditor", report.getAuditor());
            item.put("auditedAt", report.getAuditedAt());
            item.put("status", report.getStatus());
            result.add(item);
        }
        return result;
    }

    @Override
    public Map<String, Object> auditorTodaySummary() {
        LocalDate today = LocalDate.now();
        List<ReportRecord> reports = reportRecordMapper.selectList(null);
        long total = 0;
        long passed = 0;
        long rejected = 0;
        for (ReportRecord report : reports) {
            if (report.getAuditedAt() == null || !report.getAuditedAt().toLocalDate().equals(today)) {
                continue;
            }
            total++;
            if ("certified".equals(report.getStatus())) {
                passed++;
            } else if ("rejected".equals(report.getStatus())) {
                rejected++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("passed", passed);
        result.put("rejected", rejected);
        return result;
    }

    @Override
    public List<Map<String, Object>> auditorPendingQueue(Integer limit) {
        int size = (limit == null || limit <= 0) ? 6 : limit;
        List<ReportRecord> reports = reportRecordMapper.selectList(
                new LambdaQueryWrapper<ReportRecord>()
                        .eq(ReportRecord::getStatus, "pending")
                        .orderByAsc(ReportRecord::getSubmittedAt)
                        .last("limit " + size));
        List<Map<String, Object>> result = new ArrayList<>();
        for (ReportRecord report : reports) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", report.getId());
            item.put("emissionId", report.getEmissionId());
            item.put("enterpriseId", report.getEnterpriseId());
            item.put("enterpriseName", report.getEnterpriseName());
            item.put("period", report.getPeriod());
            item.put("sourceName", report.getSourceName());
            item.put("amount", report.getAmount());
            item.put("status", report.getStatus());
            item.put("fingerprint", report.getFingerprint());
            item.put("submitter", report.getSubmitter());
            item.put("submittedAt", report.getSubmittedAt());
            result.add(item);
        }
        return result;
    }

    // ==================== 公共端 ====================

    @Override
    public List<Map<String, Object>> publicEnterpriseEmissions() {
        List<Enterprise> enterprises = enterpriseMapper.selectList(null);
        List<EmissionRecord> records = emissionRecordMapper.selectList(null);
        List<CertificationRecord> certs = certificationRecordMapper.selectList(null);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Enterprise enterprise : enterprises) {
            List<EmissionRecord> own = records.stream()
                    .filter(r -> enterprise.getId().equals(r.getEnterpriseId()))
                    .collect(Collectors.toList());
            long certCount = certs.stream()
                    .filter(c -> enterprise.getId().equals(c.getEnterpriseId()) && "valid".equals(c.getStatus()))
                    .count();
            long pendingCount = own.stream()
                    .filter(r -> "pending".equals(r.getStatus()))
                    .count();
            String latestPeriod = own.stream()
                    .map(EmissionRecord::getPeriod)
                    .filter(Objects::nonNull)
                    .max(String::compareTo)
                    .orElse(null);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("enterpriseId", enterprise.getId());
            item.put("enterpriseName", enterprise.getName());
            item.put("industry", enterprise.getIndustry());
            item.put("totalEmission", sumAmount(own));
            item.put("certCount", certCount);
            item.put("pendingCount", pendingCount);
            item.put("latestPeriod", latestPeriod);
            result.add(item);
        }
        return result;
    }

    // ==================== 私有工具方法 ====================

    /** 企业编号 → 企业对象索引，供按企业归属聚合时快速反查 */
    private Map<String, Enterprise> toEnterpriseMap(List<Enterprise> enterprises) {
        Map<String, Enterprise> map = new LinkedHashMap<>();
        for (Enterprise enterprise : enterprises) {
            map.put(enterprise.getId(), enterprise);
        }
        return map;
    }

    /** 按给定月份轴生成初始值为 0 的月度桶 */
    private Map<String, BigDecimal> emptyMonthMap(List<String> months) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (String month : months) {
            map.put(month, ZERO);
        }
        return map;
    }

    /** 统计状态匹配的记录条数 */
    private long countStatus(List<EmissionRecord> records, String status) {
        return records.stream().filter(r -> status.equals(r.getStatus())).count();
    }

    /** 累加记录排放量并保留 2 位小数（null 视作 0） */
    private BigDecimal sumAmount(List<EmissionRecord> records) {
        BigDecimal total = records.stream()
                .map(EmissionRecord::getAmount)
                .filter(Objects::nonNull)
                .reduce(ZERO, BigDecimal::add);
        return scale(total);
    }

    /** null 归一为 0 */
    private BigDecimal nz(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    /** 金额口径：四舍五入保留 2 位小数 */
    private BigDecimal scale(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** 占比计算：保留 1 位小数的百分比；总数为 0 时返回 0.0 */
    private BigDecimal percent(BigDecimal value, BigDecimal grand) {
        if (grand == null || grand.compareTo(ZERO) == 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        return nz(value).multiply(BigDecimal.valueOf(100))
                .divide(grand, 1, RoundingMode.HALF_UP);
    }

    /** 星期枚举 → 中文标签（周日兜底） */
    private String weekdayLabel(DayOfWeek dayOfWeek) {
        switch (dayOfWeek) {
            case MONDAY: return "周一";
            case TUESDAY: return "周二";
            case WEDNESDAY: return "周三";
            case THURSDAY: return "周四";
            case FRIDAY: return "周五";
            case SATURDAY: return "周六";
            default: return "周日";
        }
    }

    /**
     * 趋势图月份轴：取数据中实际出现过的报告期（yyyy-MM）升序去重；
     * 一条数据都没有时回退到截至当前月的最近 12 个月，保证图表仍有坐标轴。
     */
    private static List<String> monthAxis(Collection<String> periods) {
        TreeSet<String> months = new TreeSet<>();
        if (periods != null) {
            for (String period : periods) {
                if (period != null && PERIOD_PATTERN.matcher(period.trim()).matches()) {
                    months.add(period.trim());
                }
            }
        }
        if (months.isEmpty()) {
            YearMonth current = YearMonth.now();
            for (int i = 11; i >= 0; i--) {
                months.add(current.minusMonths(i).toString());
            }
        }
        return new ArrayList<>(months);
    }
}
