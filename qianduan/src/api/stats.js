/**
 * @file 统计数据接口（各端首页图表 / 指标）
 */
import request from '@/utils/request'

// ---------- 监管端 ----------
export const getRegulatorOverview = () => request.get('/stats/regulator/overview')
export const getRegulatorIndustryEmission = () => request.get('/stats/regulator/industry-emission')
export const getRegulatorCertStatus = () => request.get('/stats/regulator/cert-status')
export const getRegulatorIndustrySummary = () => request.get('/stats/regulator/industry-summary')
export const getRegulatorIndustryRanking = () => request.get('/stats/regulator/industry-ranking')
export const getRegulatorEnterpriseRanking = () => request.get('/stats/regulator/enterprise-ranking')
export const getRegulatorEnterpriseTrend = () => request.get('/stats/regulator/enterprise-trend')

// ---------- 企业端 ----------
export const getEnterpriseOverview = (params) => request.get('/stats/enterprise/overview', { params })
export const getEnterpriseTrend = (params) => request.get('/stats/enterprise/trend', { params })
export const getEnterpriseSourceShare = (params) => request.get('/stats/enterprise/source-share', { params })

// ---------- 审核端 ----------
export const getAuditorOverview = () => request.get('/stats/auditor/overview')
export const getAuditorRecent7Days = () => request.get('/stats/auditor/recent7days')
export const getAuditorTodayList = () => request.get('/stats/auditor/today-list')
export const getAuditorTodaySummary = () => request.get('/stats/auditor/today-summary')
export const getAuditorPendingQueue = (params) => request.get('/stats/auditor/pending-queue', { params })
