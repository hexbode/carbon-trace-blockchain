package com.carbon.trace.controller;

import com.carbon.trace.common.ApiException;
import com.carbon.trace.common.LoginUser;
import com.carbon.trace.common.UserContext;
import com.carbon.trace.service.StatsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 统计数据接口（供各端首页图表使用）
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    // ---------- 监管端 ----------

    @GetMapping("/regulator/overview")
    public Map<String, Object> regulatorOverview() {
        requireRole("regulator");
        return statsService.regulatorOverview();
    }

    @GetMapping("/regulator/industry-emission")
    public List<Map<String, Object>> regulatorIndustryEmission() {
        requireRole("regulator");
        return statsService.regulatorIndustryEmission();
    }

    @GetMapping("/regulator/cert-status")
    public Map<String, Object> regulatorCertStatus() {
        requireRole("regulator");
        return statsService.regulatorCertStatus();
    }

    @GetMapping("/regulator/industry-summary")
    public List<Map<String, Object>> regulatorIndustrySummary() {
        requireRole("regulator");
        return statsService.regulatorIndustrySummary();
    }

    @GetMapping("/regulator/industry-ranking")
    public List<Map<String, Object>> regulatorIndustryRanking() {
        requireRole("regulator");
        return statsService.regulatorIndustryRanking();
    }

    @GetMapping("/regulator/enterprise-ranking")
    public List<Map<String, Object>> regulatorEnterpriseRanking() {
        requireRole("regulator");
        return statsService.regulatorEnterpriseRanking();
    }

    @GetMapping("/regulator/enterprise-trend")
    public Map<String, Object> regulatorEnterpriseTrend() {
        requireRole("regulator");
        return statsService.regulatorEnterpriseTrend();
    }

    // ---------- 企业端 ----------

    @GetMapping("/enterprise/overview")
    public Map<String, Object> enterpriseOverview(@RequestParam(required = false) String enterpriseId) {
        requireRole("enterprise");
        return statsService.enterpriseOverview(enterpriseId);
    }

    @GetMapping("/enterprise/trend")
    public Map<String, Object> enterpriseTrend(@RequestParam(required = false) String enterpriseId) {
        requireRole("enterprise");
        return statsService.enterpriseTrend(enterpriseId);
    }

    @GetMapping("/enterprise/source-share")
    public List<Map<String, Object>> enterpriseSourceShare(@RequestParam(required = false) String enterpriseId) {
        requireRole("enterprise");
        return statsService.enterpriseSourceShare(enterpriseId);
    }

    // ---------- 审核端 ----------

    @GetMapping("/auditor/overview")
    public Map<String, Object> auditorOverview() {
        requireRole("auditor");
        return statsService.auditorOverview();
    }

    @GetMapping("/auditor/recent7days")
    public Map<String, Object> auditorRecent7Days() {
        requireRole("auditor");
        return statsService.auditorRecent7Days();
    }

    @GetMapping("/auditor/today-list")
    public List<Map<String, Object>> auditorTodayList() {
        requireRole("auditor");
        return statsService.auditorTodayList();
    }

    @GetMapping("/auditor/today-summary")
    public Map<String, Object> auditorTodaySummary() {
        requireRole("auditor");
        return statsService.auditorTodaySummary();
    }

    @GetMapping("/auditor/pending-queue")
    public List<Map<String, Object>> auditorPendingQueue(@RequestParam(required = false) Integer limit) {
        requireRole("auditor");
        return statsService.auditorPendingQueue(limit);
    }

    /**
     * 角色限制：各端统计仅对应端角色（及系统管理员）可访问，跨端访问返回 403。
     */
    private void requireRole(String expected) {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!expected.equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权访问该端统计数据");
        }
    }
}
