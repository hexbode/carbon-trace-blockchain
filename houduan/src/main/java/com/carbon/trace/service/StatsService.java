package com.carbon.trace.service;

import java.util.List;
import java.util.Map;

/**
 * 统计服务（供各端首页图表使用）
 */
public interface StatsService {

    Map<String, Object> regulatorOverview();

    List<Map<String, Object>> regulatorIndustryEmission();

    Map<String, Object> regulatorCertStatus();

    List<Map<String, Object>> regulatorIndustrySummary();

    List<Map<String, Object>> regulatorIndustryRanking();

    List<Map<String, Object>> regulatorEnterpriseRanking();

    Map<String, Object> regulatorEnterpriseTrend();

    Map<String, Object> enterpriseOverview(String enterpriseId);

    Map<String, Object> enterpriseTrend(String enterpriseId);

    List<Map<String, Object>> enterpriseSourceShare(String enterpriseId);

    Map<String, Object> auditorOverview();

    Map<String, Object> auditorRecent7Days();

    List<Map<String, Object>> auditorTodayList();

    Map<String, Object> auditorTodaySummary();

    List<Map<String, Object>> auditorPendingQueue(Integer limit);

    /** 公共端企业排放概览 */
    List<Map<String, Object>> publicEnterpriseEmissions();
}
