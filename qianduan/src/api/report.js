/**
 * @file 报告提交记录接口
 */
import request from '@/utils/request'

// 报告列表（可按 enterpriseId、status、keyword 过滤）
export const getReportRecords = (params) => request.get('/report-records', { params })
// 报告详情
export const getReportRecord = (id) => request.get(`/report-records/${id}`)
// 审核报告：data = { result: 'certified'|'rejected', opinion }
export const auditReport = (id, data) => request.put(`/report-records/${id}/audit`, data)
// 企业重新申请认证（已驳回报告改回 pending）
export const applyReport = (id) => request.put(`/report-records/${id}/apply`)
