/**
 * @file 审核机构接口
 */
import request from '@/utils/request'

// 机构列表（可按 name 过滤）
export const getAuditOrganizations = (params) => request.get('/audit-organizations', { params })
// 新增机构
export const createAuditOrganization = (data) => request.post('/audit-organizations', data)
// 更新机构
export const updateAuditOrganization = (id, data) => request.put(`/audit-organizations/${id}`, data)
// 删除机构
export const deleteAuditOrganization = (id) => request.delete(`/audit-organizations/${id}`)
