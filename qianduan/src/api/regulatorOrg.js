/**
 * @file 监管机构接口（监管端）
 */
import request from '@/utils/request'

// 监管机构列表（可按 name 过滤）
export const getRegulatorOrganizations = (params) => request.get('/regulator-organizations', { params })
// 新增监管机构
export const createRegulatorOrganization = (data) => request.post('/regulator-organizations', data)
// 更新监管机构
export const updateRegulatorOrganization = (id, data) => request.put(`/regulator-organizations/${id}`, data)
// 删除监管机构
export const deleteRegulatorOrganization = (id) => request.delete(`/regulator-organizations/${id}`)
