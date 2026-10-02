/**
 * @file 企业接口
 */
import request from '@/utils/request'

// 企业列表（可按 name、industry 过滤）
export const getEnterprises = (params) => request.get('/enterprises', { params })
// 行业字典（去重）
export const getEnterpriseIndustries = () => request.get('/enterprises/industries')
// 企业详情
export const getEnterprise = (id) => request.get(`/enterprises/${id}`)
// 新增企业
export const createEnterprise = (data) => request.post('/enterprises', data)
// 更新企业
export const updateEnterprise = (id, data) => request.put(`/enterprises/${id}`, data)
// 删除企业
export const deleteEnterprise = (id) => request.delete(`/enterprises/${id}`)
