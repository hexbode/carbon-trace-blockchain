/**
 * @file 异常告警接口
 */
import request from '@/utils/request'

// 告警列表（可按 level、status 过滤）
export const getAlerts = (params) => request.get('/alerts', { params })
// 告警详情
export const getAlert = (id) => request.get(`/alerts/${id}`)
// 处理告警
export const resolveAlert = (id) => request.put(`/alerts/${id}/resolve`)
