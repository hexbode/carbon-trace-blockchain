package com.carbon.trace.service;

import java.util.List;
import java.util.Map;

/**
 * 统计服务（供各端首页图表使用）。
 * 按角色聚合监管端、企业端、审核端、公共端的统计口径：监管全局概览、行业排放分布、认证状态、
 * 企业排行与趋势、审核员待办等，均以 Map/List&lt;Map&gt; 形式返回，由对应 Controller 直接透出。
 */
public interface StatsService {

    /** 监管端总览指标 */
    Map<String, Object> regulatorOverview();

    /** 监管端按行业聚合的排放量 */
    List<Map<String, Object>> regulatorIndustryEmission();

    /** 监管端认证状态分布 */
    Map<String, Object> regulatorCertStatus();

    /** 监管端行业汇总 */
    List<Map<String, Object>> regulatorIndustrySummary();

    /** 监管端行业排行 */
    List<Map<String, Object>> regulatorIndustryRanking();

    /** 监管端企业排行 */
    List<Map<String, Object>> regulatorEnterpriseRanking();

    /** 监管端企业排放趋势 */
    Map<String, Object> regulatorEnterpriseTrend();

    /** 企业端总览指标 */
    Map<String, Object> enterpriseOverview(String enterpriseId);

    /** 企业端排放趋势 */
    Map<String, Object> enterpriseTrend(String enterpriseId);

    /** 企业端排放源占比 */
    List<Map<String, Object>> enterpriseSourceShare(String enterpriseId);

    /** 审核端总览指标 */
    Map<String, Object> auditorOverview();

    /** 审核端近 7 天统计 */
    Map<String, Object> auditorRecent7Days();

    /** 审核端今日待办列表 */
    List<Map<String, Object>> auditorTodayList();

    /** 审核端今日汇总 */
    Map<String, Object> auditorTodaySummary();

    /** 审核端待认证队列（按数量上限截断） */
    List<Map<String, Object>> auditorPendingQueue(Integer limit);

    /** 公共端企业排放概览 */
    List<Map<String, Object>> publicEnterpriseEmissions();
}
