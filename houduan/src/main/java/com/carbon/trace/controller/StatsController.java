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
 * 统计数据接口：为监管端 / 企业端 / 审核端首页图表提供聚合统计数据。
 *
 * 暴露接口（按角色分组的统计口径）：
 *   监管端：总数概览、行业排放、认证状态、行业汇总、行业排名、企业排名、企业趋势；
 *   企业端：企业概览、排放趋势、排放源占比（可用 enterpriseId 指定，缺省取登录企业）；
 *   审核端：审核概览、近 7 日、今日清单、今日汇总、待审队列。
 * 角色约定：各端接口仅对应端角色（及 admin）可访问，跨端调用由 requireRole 返回 403。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    // ---------- 监管端 ----------

    /** 监管端总数概览（企业数、排放总量、认证数等首页头部指标）；仅监管/管理员 */
    @GetMapping("/regulator/overview")
    public Map<String, Object> regulatorOverview() {
        requireRole("regulator");
        return statsService.regulatorOverview();
    }

    /** 监管端行业排放分布（按行业聚合排放量，供饼图/柱图）；仅监管/管理员 */
    @GetMapping("/regulator/industry-emission")
    public List<Map<String, Object>> regulatorIndustryEmission() {
        requireRole("regulator");
        return statsService.regulatorIndustryEmission();
    }

    /** 监管端认证状态分布（各状态证书数量统计）；仅监管/管理员 */
    @GetMapping("/regulator/cert-status")
    public Map<String, Object> regulatorCertStatus() {
        requireRole("regulator");
        return statsService.regulatorCertStatus();
    }

    /** 监管端行业汇总（行业维度的多指标汇总表）；仅监管/管理员 */
    @GetMapping("/regulator/industry-summary")
    public List<Map<String, Object>> regulatorIndustrySummary() {
        requireRole("regulator");
        return statsService.regulatorIndustrySummary();
    }

    /** 监管端行业排名（按排放量等指标排序）；仅监管/管理员 */
    @GetMapping("/regulator/industry-ranking")
    public List<Map<String, Object>> regulatorIndustryRanking() {
        requireRole("regulator");
        return statsService.regulatorIndustryRanking();
    }

    /** 监管端企业排名（按排放量等指标排序）；仅监管/管理员 */
    @GetMapping("/regulator/enterprise-ranking")
    public List<Map<String, Object>> regulatorEnterpriseRanking() {
        requireRole("regulator");
        return statsService.regulatorEnterpriseRanking();
    }

    /** 监管端企业排放趋势（时间序列数据）；仅监管/管理员 */
    @GetMapping("/regulator/enterprise-trend")
    public Map<String, Object> regulatorEnterpriseTrend() {
        requireRole("regulator");
        return statsService.regulatorEnterpriseTrend();
    }

    // ---------- 企业端 ----------

    /** 企业端概览：enterpriseId 缺省取当前登录企业，返回企业自己的头部指标 */
    @GetMapping("/enterprise/overview")
    public Map<String, Object> enterpriseOverview(@RequestParam(required = false) String enterpriseId) {
        requireRole("enterprise");
        return statsService.enterpriseOverview(enterpriseId);
    }

    /** 企业端排放趋势：enterpriseId 缺省取当前登录企业 */
    @GetMapping("/enterprise/trend")
    public Map<String, Object> enterpriseTrend(@RequestParam(required = false) String enterpriseId) {
        requireRole("enterprise");
        return statsService.enterpriseTrend(enterpriseId);
    }

    /** 企业端排放源占比：enterpriseId 缺省取当前登录企业 */
    @GetMapping("/enterprise/source-share")
    public List<Map<String, Object>> enterpriseSourceShare(@RequestParam(required = false) String enterpriseId) {
        requireRole("enterprise");
        return statsService.enterpriseSourceShare(enterpriseId);
    }

    // ---------- 审核端 ----------

    /** 审核端概览（待审/已审核数量等审核工作台指标）；仅审核端/管理员 */
    @GetMapping("/auditor/overview")
    public Map<String, Object> auditorOverview() {
        requireRole("auditor");
        return statsService.auditorOverview();
    }

    /** 审核端近 7 日审核趋势（时间序列）；仅审核端/管理员 */
    @GetMapping("/auditor/recent7days")
    public Map<String, Object> auditorRecent7Days() {
        requireRole("auditor");
        return statsService.auditorRecent7Days();
    }

    /** 审核端今日待处理清单；仅审核端/管理员 */
    @GetMapping("/auditor/today-list")
    public List<Map<String, Object>> auditorTodayList() {
        requireRole("auditor");
        return statsService.auditorTodayList();
    }

    /** 审核端今日审核汇总；仅审核端/管理员 */
    @GetMapping("/auditor/today-summary")
    public Map<String, Object> auditorTodaySummary() {
        requireRole("auditor");
        return statsService.auditorTodaySummary();
    }

    /** 审核端待审队列：可用 limit 限制返回条数；仅审核端/管理员 */
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
