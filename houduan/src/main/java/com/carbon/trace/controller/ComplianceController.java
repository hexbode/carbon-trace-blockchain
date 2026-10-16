package com.carbon.trace.controller;

import com.carbon.trace.chain.model.ComplianceResult;
import com.carbon.trace.chain.support.ComplianceFlags;
import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.service.ComplianceService;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合规判定接口：判定由链上智能合约执行，此处负责聚合入参、解读结果与治理参数管理。
 */
@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private final ComplianceService complianceService;

    public ComplianceController(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    /**
     * 试算判定（不落库）：body {enterpriseId, period, sourceCode, activityData, factor}
     */
    @PostMapping("/check")
    public Map<String, Object> check(@RequestBody Map<String, Object> body) {
        String enterpriseId = str(body.get("enterpriseId"));
        String period = str(body.get("period"));
        String sourceCode = str(body.get("sourceCode"));
        if (!StringUtils.hasText(enterpriseId) || !StringUtils.hasText(period)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "企业编号与报告期不能为空");
        }
        ComplianceResult result = complianceService.evaluateForCheck(
                enterpriseId, period, sourceCode, decimal(body.get("activityData")), decimal(body.get("factor")));
        return describe(result);
    }

    /** 证书的链上合规快照 */
    @GetMapping("/certificate/{certId}")
    public Map<String, Object> certificate(@PathVariable String certId) {
        Map<String, Object> result = describeFlags(complianceService.certificateFlags(certId));
        result.put("certId", certId);
        return result;
    }

    @GetMapping("/quota")
    public Map<String, Object> quota(@RequestParam String enterpriseId, @RequestParam int year) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enterpriseId", enterpriseId);
        result.put("year", year);
        result.put("quota", complianceService.quotaOf(enterpriseId, year));
        return result;
    }

    /** 设置年度配额（合约写）：body {enterpriseId, year, quota} */
    @PutMapping("/quota")
    public Map<String, Object> setQuota(@RequestBody Map<String, Object> body) {
        requireRegulatorRole();
        String enterpriseId = str(body.get("enterpriseId"));
        BigDecimal quota = decimal(body.get("quota"));
        Integer year = integer(body.get("year"));
        if (!StringUtils.hasText(enterpriseId) || year == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "企业编号与年份不能为空");
        }
        complianceService.setQuota(enterpriseId, year, quota);
        return quota(enterpriseId, year);
    }

    @GetMapping("/threshold")
    public Map<String, Object> threshold() {
        return describeThreshold();
    }

    /** 修改可治理参数（合约写）：body {thresholdBp?, lateReportMonths?} */
    @PutMapping("/threshold")
    public Map<String, Object> setThreshold(@RequestBody Map<String, Object> body) {
        requireRegulatorRole();
        Integer bp = integer(body.get("thresholdBp"));
        Integer months = integer(body.get("lateReportMonths"));
        if (bp == null && months == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "环比阈值与迟报月数至少提供一项");
        }
        if (bp != null) {
            complianceService.setFluctuationThresholdBp(bp);
        }
        if (months != null) {
            complianceService.setLateReportMonths(months);
        }
        return describeThreshold();
    }

    // ---------------- 内部 ----------------

    private Map<String, Object> describeThreshold() {
        Map<String, Object> result = new LinkedHashMap<>();
        int bp = complianceService.fluctuationThresholdBp();
        result.put("thresholdBp", bp);
        result.put("thresholdPercent", percentOf(bp));
        result.put("lateReportMonths", complianceService.lateReportMonths());
        return result;
    }

    private Map<String, Object> describe(ComplianceResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flags", result.getFlags());
        body.put("onChain", result.isOnChain());
        body.put("compliant", result.isOnChain() ? result.isCompliant() : null);
        body.put("notice", result.getNotice());
        body.put("deltaBp", result.getDeltaBp());
        body.put("deltaPercent", percentOf(result.getDeltaBp()));
        body.put("previousFactor", result.getPreviousFactor());
        body.put("quota", result.getQuota());
        body.put("yearToDate", result.getYearToDate());
        body.put("thresholdBp", result.getThresholdBp());
        body.put("thresholdPercent", percentOf(result.getThresholdBp()));
        body.put("items", items(result.getFlags()));
        return body;
    }

    private Map<String, Object> describeFlags(int flags) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flags", flags);
        body.put("compliant", flags < 0 ? null : ComplianceFlags.compliant(flags));
        body.put("items", items(flags));
        return body;
    }

    private List<Map<String, Object>> items(int flags) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ComplianceFlags.Rule rule : ComplianceFlags.rules(flags)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rule", rule.rule());
            item.put("type", rule.type());
            item.put("typeLabel", rule.typeLabel());
            item.put("level", rule.level());
            items.add(item);
        }
        return items;
    }

    /** 基点 → 百分比文本（2000 → "20.00%"） */
    private static String percentOf(long bp) {
        return BigDecimal.valueOf(bp).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP) + "%";
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value)).intValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 治理参数修改仅监管端（regulator）与系统管理员（admin）可执行 */
    private void requireRegulatorRole() {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!"regulator".equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权修改链上合规治理参数");
        }
    }
}
