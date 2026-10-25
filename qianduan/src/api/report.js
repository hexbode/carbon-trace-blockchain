/**
 * @file 报告提交记录接口
 * @description 用途：碳排放报告查询、审核端审核、企业端重新发起认证申请。
 *              依赖：@/utils/request（统一 axios 实例，自动注入 JWT 并统一处理 401）。
 *              导出：getReportRecords、getReportRecord、auditReport、applyReport。
 *              对应后端：ReportRecordController（@RequestMapping("/api/report-records")）。
 */
import request from '@/utils/request'

/**
 * 报告列表（可按 enterpriseId、status、keyword 过滤）
 *
 * 接口：GET /report-records
 * @param {Object} [params] 查询参数 { enterpriseId, status, keyword }
 * @returns {Promise<Array>} 报告记录列表，按提交时间倒序；企业端强制只返回本企业记录
 * @permission 登录即可；enterprise 角色按令牌归属过滤
 */
export const getReportRecords = (params) => request.get('/report-records', { params })

/**
 * 报告详情
 *
 * 接口：GET /report-records/{id}
 * @param {string} id 报告编号（与排放记录同号）
 * @returns {Promise<Object>} 报告记录实体
 * @permission 登录即可；enterprise 角色仅可查看本企业记录，越权返回 403
 */
export const getReportRecord = (id) => request.get(`/report-records/${id}`)

/**
 * 审核报告：data = { result: 'certified'|'rejected', opinion }
 *
 * 接口：PUT /report-records/{id}/audit
 * @param {string} id 报告编号
 * @param {Object} data 请求体 { result 必填, opinion 选填 }；审核人取自令牌，不接受前端传入
 * @returns {Promise<Object>} 审核后的报告记录（含审核人、审核时间、审核意见）；
 *          result=certified 时后端会在事务提交后生成认证记录并写链
 * @permission 仅 auditor / admin
 */
export const auditReport = (id, data) => request.put(`/report-records/${id}/audit`, data)

/**
 * 企业重新申请认证（已驳回报告改回 pending）
 *
 * 接口：PUT /report-records/{id}/apply
 * @param {string} id 报告编号
 * @returns {Promise<Object>} 状态改为 pending 的报告记录（审核人/时间/意见被清空）
 * @permission 仅 enterprise / admin；企业端仅限本企业报告
 */
export const applyReport = (id) => request.put(`/report-records/${id}/apply`)
