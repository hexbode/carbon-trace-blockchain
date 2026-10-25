package com.carbon.trace.service;

import java.util.List;
import java.util.Map;

/**
 * 统计服务（供各端首页图表使用）。
 * 按角色聚合监管端、企业端、审核端、公共端的统计口径：监管全局概览、行业排放分布、认证状态、
 * 企业排行与趋势、审核员待办等，均以 Map/List&lt;Map&gt; 形式返回，由对应 Controller 直接透出。
 * 只读服务：仅查询业务库并组装展示结构，不落库、不写链，方法无事务语义。
 * 权限：Controller 按端限制角色（regulator / enterprise / auditor，admin 均可访问）。
 */
public interface StatsService {

    /**
     * 监管端总览指标（企业数、排放总量、认证数等首页头部指标）。
     * 权限：仅 regulator / admin。
     *
     * @return 指标 Map
     */
    Map<String, Object> regulatorOverview();

    /**
     * 监管端按行业聚合的排放量，供饼图/柱图展示。
     * 权限：仅 regulator / admin。
     *
     * @return 行业排放列表，每项含行业与排放量等字段
     */
    List<Map<String, Object>> regulatorIndustryEmission();

    /**
     * 监管端认证状态分布（各状态证书数量）。
     * 权限：仅 regulator / admin。
     *
     * @return 状态分布 Map
     */
    Map<String, Object> regulatorCertStatus();

    /**
     * 监管端行业汇总（行业维度的多指标汇总表）。
     * 权限：仅 regulator / admin。
     *
     * @return 行业汇总列表
     */
    List<Map<String, Object>> regulatorIndustrySummary();

    /**
     * 监管端行业排行（按排放量等指标排序）。
     * 权限：仅 regulator / admin。
     *
     * @return 行业排行列表
     */
    List<Map<String, Object>> regulatorIndustryRanking();

    /**
     * 监管端企业排行（按排放量等指标排序）。
     * 权限：仅 regulator / admin。
     *
     * @return 企业排行列表
     */
    List<Map<String, Object>> regulatorEnterpriseRanking();

    /**
     * 监管端企业排放趋势（时间序列数据）。
     * 权限：仅 regulator / admin。
     *
     * @return 趋势数据 Map（通常含 periods 与 datasets）
     */
    Map<String, Object> regulatorEnterpriseTrend();

    /**
     * 企业端总览指标。
     * 权限：仅 enterprise / admin。
     *
     * @param enterpriseId 企业编号
     * @return 指标 Map
     */
    Map<String, Object> enterpriseOverview(String enterpriseId);

    /**
     * 企业端排放趋势（时间序列）。
     * 权限：仅 enterprise / admin。
     *
     * @param enterpriseId 企业编号
     * @return 趋势数据 Map
     */
    Map<String, Object> enterpriseTrend(String enterpriseId);

    /**
     * 企业端排放源占比。
     * 权限：仅 enterprise / admin。
     *
     * @param enterpriseId 企业编号
     * @return 排放源占比列表，每项含排放源与占比/排放量
     */
    List<Map<String, Object>> enterpriseSourceShare(String enterpriseId);

    /**
     * 审核端总览指标（待审/已审核数量等审核工作台指标）。
     * 权限：仅 auditor / admin。
     *
     * @return 指标 Map
     */
    Map<String, Object> auditorOverview();

    /**
     * 审核端近 7 天审核趋势（时间序列）。
     * 权限：仅 auditor / admin。
     *
     * @return 趋势数据 Map
     */
    Map<String, Object> auditorRecent7Days();

    /**
     * 审核端今日待办列表。
     * 权限：仅 auditor / admin。
     *
     * @return 待办列表
     */
    List<Map<String, Object>> auditorTodayList();

    /**
     * 审核端今日审核汇总。
     * 权限：仅 auditor / admin。
     *
     * @return 汇总 Map
     */
    Map<String, Object> auditorTodaySummary();

    /**
     * 审核端待认证队列（按数量上限截断）。
     * 权限：仅 auditor / admin。
     *
     * @param limit 返回条数上限；为 null 或小于等于 0 时使用默认上限（6）
     * @return 待认证报告/排放记录列表
     */
    List<Map<String, Object>> auditorPendingQueue(Integer limit);

    /**
     * 公共端企业排放概览（对外公开的减排成效展示）。
     * 权限：公共接口，免登录（由 Controller/拦截器放行）。
     *
     * @return 企业排放概览列表
     */
    List<Map<String, Object>> publicEnterpriseEmissions();
}
