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
 * 合规判定接口：排放合规性由链上智能合约执行判定，本控制器负责聚合入参、解读判定结果
 * 以及维护链上治理参数（年度配额与判定阈值）。
 *
 * 暴露接口：
 *   POST /api/compliance/check             试算判定（不落库），body {enterpriseId,period,sourceCode,activityData,factor}；
 *   GET  /api/compliance/certificate/{certId}  证书的链上合规快照；
 *   GET  /api/compliance/quota             查询企业某年度配额（参数 enterpriseId、year）；
 *   PUT  /api/compliance/quota             设置年度配额（写合约，body {enterpriseId,year,quota}）；
 *   GET  /api/compliance/threshold         查询治理参数（环比阈值、迟报月数）；
 *   PUT  /api/compliance/threshold         修改治理参数（写合约，body {thresholdBp?, lateReportMonths?}）。
 * 角色约定：查询登录即可；写合约（配额、阈值）仅 regulator / admin（requireRegulatorRole）。
 */
@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private final ComplianceService complianceService;

    public ComplianceController(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    /**
     * 试算判定（不落库）：body {enterpriseId, period, sourceCode, activityData, factor}。
     * 返回合规旗标、环比增量、配额与逐条规则明细；企业编号或报告期为空时返回 400。
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

    /** 按证书编号返回该证书上链时的合规判定快照（旗标 + 规则明细 + 是否合规） */
    @GetMapping("/certificate/{certId}")
    public Map<String, Object> certificate(@PathVariable String certId) {
        Map<String, Object> result = describeFlags(complianceService.certificateFlags(certId));
        result.put("certId", certId);
        return result;
    }

    /** 查询企业某年度碳排放配额：参数 enterpriseId、year，返回 {enterpriseId, year, quota} */
    @GetMapping("/quota")
    public Map<String, Object> quota(@RequestParam String enterpriseId, @RequestParam int year) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enterpriseId", enterpriseId);
        result.put("year", year);
        result.put("quota", complianceService.quotaOf(enterpriseId, year));
        return result;
    }

    /** 设置年度配额（写合约）：body {enterpriseId, year, quota}；仅监管/管理员；企业编号或年份缺失返回 400 */
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

    /** 查询链上治理参数：环比波动阈值（基点/百分比）与迟报月数 */
    @GetMapping("/threshold")
    public Map<String, Object> threshold() {
        return describeThreshold();
    }

    /** 修改可治理参数（写合约）：body {thresholdBp?, lateReportMonths?} 至少提供其一；仅监管/管理员 */
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

    /** 组装治理参数响应：环比阈值（基点 + 百分比文本）与迟报月数 */
    private Map<String, Object> describeThreshold() {
        Map<String, Object> result = new LinkedHashMap<>();
        int bp = complianceService.fluctuationThresholdBp();
        result.put("thresholdBp", bp);
        result.put("thresholdPercent", percentOf(bp));
        result.put("lateReportMonths", complianceService.lateReportMonths());
        return result;
    }

    /** 把 ComplianceResult 展开为响应 Map：旗标/是否合规/环比增量/配额/累计排放/阈值/规则明细等 */
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

    /** 把合规旗标 int 展开为响应 Map（flags 为负表示无有效快照，compliant 置 null） */
    private Map<String, Object> describeFlags(int flags) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flags", flags);
        body.put("compliant", flags < 0 ? null : ComplianceFlags.compliant(flags));
        body.put("items", items(flags));
        return body;
    }

    /** 把合规旗标展开为逐条规则明细（规则编码/类型/中文标签/级别），供前端列表展示 */
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

    /** 对象安全转 String：null 返回 null */
    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 对象安全转 BigDecimal：缺失或非法时返回 null（由调用方继续校验必填） */
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

    /** 对象安全转 Integer：缺失或非法时返回 null */
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
