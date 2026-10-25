/**
 * @file 异常告警接口
 * @description 用途：查询合规判定产生的异常告警并标记处理。
 *              依赖：@/utils/request（统一 axios 实例，自动注入 JWT 并统一处理 401）。
 *              导出：getAlerts、getAlert、resolveAlert。
 *              对应后端：AlertController（@RequestMapping("/api/alerts")）。
 */
import request from '@/utils/request'

/**
 * 告警列表（可按 level、status 过滤）
 *
 * 接口：GET /alerts
 * @param {Object} [params] 查询参数 { level: 'high'|'medium'|'low', status: 'pending'|'resolved' }
 * @returns {Promise<Array>} 告警列表，按触发时间倒序
 * @permission 登录即可（企业端、审核端、监管端、管理员均可查看）
 */
export const getAlerts = (params) => request.get('/alerts', { params })

/**
 * 告警详情
 *
 * 接口：GET /alerts/{id}
 * @param {string} id 告警编号（ALT 开头）
 * @returns {Promise<Object>} 告警实体；不存在时返回 404
 * @permission 登录即可
 */
export const getAlert = (id) => request.get(`/alerts/${id}`)

/**
 * 处理告警
 *
 * 接口：PUT /alerts/{id}/resolve
 * @param {string} id 告警编号
 * @returns {Promise<Object>} 处理后的告警（含处理人与处理时间）
 * @permission 仅 regulator / admin
 */
export const resolveAlert = (id) => request.put(`/alerts/${id}/resolve`)
