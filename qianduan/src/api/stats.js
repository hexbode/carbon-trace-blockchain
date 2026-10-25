/**
 * @file 统计数据接口（各端首页图表 / 指标）
 * @description 用途：监管端、企业端、审核端首页的概览指标、分布图与趋势图数据。
 *              依赖：@/utils/request（统一 axios 实例，自动注入 JWT 并统一处理 401）。
 *              导出：监管端 7 个、企业端 3 个、审核端 5 个统计查询函数。
 *              对应后端：StatsController（@RequestMapping("/api/stats")）；
 *                        各端接口仅对应端角色及 admin 可访问，跨端调用返回 403。
 */
import request from '@/utils/request'

// ---------- 监管端（仅 regulator / admin） ----------

/**
 * 监管端总数概览
 *
 * 接口：GET /stats/regulator/overview
 * @returns {Promise<Object>} 企业数、排放总量、认证数等首页头部指标
 * @permission 仅 regulator / admin
 */
export const getRegulatorOverview = () => request.get('/stats/regulator/overview')

/**
 * 监管端行业排放分布
 *
 * 接口：GET /stats/regulator/industry-emission
 * @returns {Promise<Array>} 每项含行业与排放量，供饼图/柱图
 * @permission 仅 regulator / admin
 */
export const getRegulatorIndustryEmission = () => request.get('/stats/regulator/industry-emission')

/**
 * 监管端认证状态分布
 *
 * 接口：GET /stats/regulator/cert-status
 * @returns {Promise<Object>} 各状态证书数量统计
 * @permission 仅 regulator / admin
 */
export const getRegulatorCertStatus = () => request.get('/stats/regulator/cert-status')

/**
 * 监管端行业汇总
 *
 * 接口：GET /stats/regulator/industry-summary
 * @returns {Promise<Array>} 行业维度的多指标汇总表
 * @permission 仅 regulator / admin
 */
export const getRegulatorIndustrySummary = () => request.get('/stats/regulator/industry-summary')

/**
 * 监管端行业排名
 *
 * 接口：GET /stats/regulator/industry-ranking
 * @returns {Promise<Array>} 按排放量等指标排序的行业列表
 * @permission 仅 regulator / admin
 */
export const getRegulatorIndustryRanking = () => request.get('/stats/regulator/industry-ranking')

/**
 * 监管端企业排名
 *
 * 接口：GET /stats/regulator/enterprise-ranking
 * @returns {Promise<Array>} 按排放量等指标排序的企业列表
 * @permission 仅 regulator / admin
 */
export const getRegulatorEnterpriseRanking = () => request.get('/stats/regulator/enterprise-ranking')

/**
 * 监管端企业排放趋势
 *
 * 接口：GET /stats/regulator/enterprise-trend
 * @returns {Promise<Object>} 时间序列数据
 * @permission 仅 regulator / admin
 */
export const getRegulatorEnterpriseTrend = () => request.get('/stats/regulator/enterprise-trend')

// ---------- 企业端（仅 enterprise / admin） ----------

/**
 * 企业端概览
 *
 * 接口：GET /stats/enterprise/overview
 * @param {Object} [params] 查询参数 { enterpriseId }；后端不会自动回填登录企业，缺省时按 null 查询
 * @returns {Promise<Object>} { totalEmission, certifiedCount, pendingCount, certCount }
 * @permission 仅 enterprise / admin
 */
export const getEnterpriseOverview = (params) => request.get('/stats/enterprise/overview', { params })

/**
 * 企业端排放趋势
 *
 * 接口：GET /stats/enterprise/trend
 * @param {Object} [params] 查询参数 { enterpriseId }
 * @returns {Promise<Object>} { months[], data[] }，月份轴取自该企业真实报告期
 * @permission 仅 enterprise / admin
 */
export const getEnterpriseTrend = (params) => request.get('/stats/enterprise/trend', { params })

/**
 * 企业端排放源占比
 *
 * 接口：GET /stats/enterprise/source-share
 * @param {Object} [params] 查询参数 { enterpriseId }
 * @returns {Promise<Array>} 每项 { name, value }；源名称为空的记录归入「其他」
 * @permission 仅 enterprise / admin
 */
export const getEnterpriseSourceShare = (params) => request.get('/stats/enterprise/source-share', { params })

// ---------- 审核端（仅 auditor / admin） ----------

/**
 * 审核端概览
 *
 * 接口：GET /stats/auditor/overview
 * @returns {Promise<Object>} 待审/已审核数量等审核工作台指标
 * @permission 仅 auditor / admin
 */
export const getAuditorOverview = () => request.get('/stats/auditor/overview')

/**
 * 审核端近 7 日审核趋势
 *
 * 接口：GET /stats/auditor/recent7days
 * @returns {Promise<Object>} 时间序列数据
 * @permission 仅 auditor / admin
 */
export const getAuditorRecent7Days = () => request.get('/stats/auditor/recent7days')

/**
 * 审核端今日待处理清单
 *
 * 接口：GET /stats/auditor/today-list
 * @returns {Promise<Array>} 当日待处理报告/排放记录
 * @permission 仅 auditor / admin
 */
export const getAuditorTodayList = () => request.get('/stats/auditor/today-list')

/**
 * 审核端今日审核汇总
 *
 * 接口：GET /stats/auditor/today-summary
 * @returns {Promise<Object>} 当日审核数量汇总
 * @permission 仅 auditor / admin
 */
export const getAuditorTodaySummary = () => request.get('/stats/auditor/today-summary')

/**
 * 审核端待审队列
 *
 * 接口：GET /stats/auditor/pending-queue
 * @param {Object} [params] 查询参数 { limit }；为 null 或 ≤0 时后端使用默认上限 6
 * @returns {Promise<Array>} 待认证的报告/排放记录
 * @permission 仅 auditor / admin
 */
export const getAuditorPendingQueue = (params) => request.get('/stats/auditor/pending-queue', { params })
