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
 *
 * 依赖：{@link StatsService}（只读聚合查询；本控制器只做角色判断与透传，不做计算、不落库）。
 * 安全边界与实现现状：
 *   1. 角色判断只比较 role 字符串并由 admin 兜底放行；未登录请求会先被 AuthInterceptor 拦为 401；
 *   2. 企业端三个接口未校验 enterpriseId 归属，且不会自动回填登录企业（传 null 时按 null 查询，通常返回空数据），
 *      因此企业账号既可读到自己未传参的空结果，也可显式传入他企业编号读取对方统计；
 *      若需严格隔离，应在调用前用 UserScopes.scopedEnterpriseId 覆盖入参；
 *   3. 所有接口只读，不写库、不写链；但会全表扫描业务数据，数据量增长时响应时间随之上升。
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    /** 构造注入统计服务；控制器无状态 */
    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    // ---------- 监管端 ----------

    /**
     * GET /api/stats/regulator/overview —— 监管端总数概览。
     *
     * 权限：仅 regulator / admin。
     * 返回：企业数、排放总量、认证数等首页头部指标（Map）。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/regulator/overview")
    public Map<String, Object> regulatorOverview() {
        requireRole("regulator");
        return statsService.regulatorOverview();
    }

    /**
     * GET /api/stats/regulator/industry-emission —— 监管端行业排放分布。
     *
     * 权限：仅 regulator / admin。
     * 返回：List&lt;Map&gt;，每项含行业与排放量，供饼图 / 柱图。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/regulator/industry-emission")
    public List<Map<String, Object>> regulatorIndustryEmission() {
        requireRole("regulator");
        return statsService.regulatorIndustryEmission();
    }

    /**
     * GET /api/stats/regulator/cert-status —— 监管端认证状态分布。
     *
     * 权限：仅 regulator / admin。
     * 返回：各状态证书数量统计（Map）。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/regulator/cert-status")
    public Map<String, Object> regulatorCertStatus() {
        requireRole("regulator");
        return statsService.regulatorCertStatus();
    }

    /**
     * GET /api/stats/regulator/industry-summary —— 监管端行业汇总。
     *
     * 权限：仅 regulator / admin。
     * 返回：List&lt;Map&gt;，行业维度的多指标汇总表。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/regulator/industry-summary")
    public List<Map<String, Object>> regulatorIndustrySummary() {
        requireRole("regulator");
        return statsService.regulatorIndustrySummary();
    }

    /**
     * GET /api/stats/regulator/industry-ranking —— 监管端行业排名。
     *
     * 权限：仅 regulator / admin。
     * 返回：List&lt;Map&gt;，按排放量等指标排序的行业列表。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/regulator/industry-ranking")
    public List<Map<String, Object>> regulatorIndustryRanking() {
        requireRole("regulator");
        return statsService.regulatorIndustryRanking();
    }

    /**
     * GET /api/stats/regulator/enterprise-ranking —— 监管端企业排名。
     *
     * 权限：仅 regulator / admin。
     * 返回：List&lt;Map&gt;，按排放量等指标排序的企业列表。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/regulator/enterprise-ranking")
    public List<Map<String, Object>> regulatorEnterpriseRanking() {
        requireRole("regulator");
        return statsService.regulatorEnterpriseRanking();
    }

    /**
     * GET /api/stats/regulator/enterprise-trend —— 监管端企业排放趋势。
     *
     * 权限：仅 regulator / admin。
     * 返回：时间序列数据（Map）。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/regulator/enterprise-trend")
    public Map<String, Object> regulatorEnterpriseTrend() {
        requireRole("regulator");
        return statsService.regulatorEnterpriseTrend();
    }

    // ---------- 企业端 ----------

    /**
     * GET /api/stats/enterprise/overview —— 企业端概览。
     *
     * 权限：仅 enterprise / admin。
     * 参数：enterpriseId 选填（当前实现不会回填登录企业，缺省时按 null 查询，通常返回空数据）。
     * 返回：{totalEmission, certifiedCount, pendingCount, certCount}。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/enterprise/overview")
    public Map<String, Object> enterpriseOverview(@RequestParam(required = false) String enterpriseId) {
        requireRole("enterprise");
        return statsService.enterpriseOverview(enterpriseId);
    }

    /**
     * GET /api/stats/enterprise/trend —— 企业端排放趋势。
     *
     * 权限：仅 enterprise / admin。
     * 参数：enterpriseId 选填（语义同上，缺省时按 null 查询）。
     * 返回：{months[], data[]}，月份轴取自该企业真实报告期（无记录时回退最近 12 个月）。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/enterprise/trend")
    public Map<String, Object> enterpriseTrend(@RequestParam(required = false) String enterpriseId) {
        requireRole("enterprise");
        return statsService.enterpriseTrend(enterpriseId);
    }

    /**
     * GET /api/stats/enterprise/source-share —— 企业端排放源占比。
     *
     * 权限：仅 enterprise / admin。
     * 参数：enterpriseId 选填（语义同上，缺省时按 null 查询）。
     * 返回：List&lt;Map&gt;，每项 {name, value}；源名称为空的记录归入「其他」。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/enterprise/source-share")
    public List<Map<String, Object>> enterpriseSourceShare(@RequestParam(required = false) String enterpriseId) {
        requireRole("enterprise");
        return statsService.enterpriseSourceShare(enterpriseId);
    }

    // ---------- 审核端 ----------

    /**
     * GET /api/stats/auditor/overview —— 审核端概览。
     *
     * 权限：仅 auditor / admin。
     * 返回：待审 / 已审核数量等审核工作台指标（Map）。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/auditor/overview")
    public Map<String, Object> auditorOverview() {
        requireRole("auditor");
        return statsService.auditorOverview();
    }

    /**
     * GET /api/stats/auditor/recent7days —— 审核端近 7 日审核趋势。
     *
     * 权限：仅 auditor / admin。
     * 返回：时间序列数据（Map）。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/auditor/recent7days")
    public Map<String, Object> auditorRecent7Days() {
        requireRole("auditor");
        return statsService.auditorRecent7Days();
    }

    /**
     * GET /api/stats/auditor/today-list —— 审核端今日待处理清单。
     *
     * 权限：仅 auditor / admin。
     * 返回：List&lt;Map&gt;，当日待处理报告 / 排放记录。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/auditor/today-list")
    public List<Map<String, Object>> auditorTodayList() {
        requireRole("auditor");
        return statsService.auditorTodayList();
    }

    /**
     * GET /api/stats/auditor/today-summary —— 审核端今日审核汇总。
     *
     * 权限：仅 auditor / admin。
     * 返回：当日审核数量汇总（Map）。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/auditor/today-summary")
    public Map<String, Object> auditorTodaySummary() {
        requireRole("auditor");
        return statsService.auditorTodaySummary();
    }

    /**
     * GET /api/stats/auditor/pending-queue —— 审核端待审队列。
     *
     * 权限：仅 auditor / admin。
     * 参数：limit 选填，为 null 或 ≤ 0 时使用默认上限 6。
     * 返回：List&lt;Map&gt;，待认证的报告 / 排放记录。
     * 错误码：401 未登录；403 跨端访问；500 未知异常。链上写：无。
     */
    @GetMapping("/auditor/pending-queue")
    public List<Map<String, Object>> auditorPendingQueue(@RequestParam(required = false) Integer limit) {
        requireRole("auditor");
        return statsService.auditorPendingQueue(limit);
    }

    /**
     * 角色限制：各端统计仅对应端角色（及系统管理员）可访问，跨端访问返回 403。
     *
     * @param expected 期望的角色编码（regulator / enterprise / auditor）
     * @throws ApiException 403 角色不匹配且非 admin
     */
    private void requireRole(String expected) {
        LoginUser current = UserContext.get();
        String role = current == null ? null : current.getRole();
        if (!expected.equals(role) && !"admin".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "无权访问该端统计数据");
        }
    }
}
