/**
 * @file 碳排放因子库接口
 */
import request from '@/utils/request'

// 因子列表（可按 category、keyword 过滤）
export const getEmissionFactors = (params) => request.get('/emission-factors', { params })
// 新增因子
export const createEmissionFactor = (data) => request.post('/emission-factors', data)
// 更新因子
export const updateEmissionFactor = (id, data) => request.put(`/emission-factors/${id}`, data)
// 删除因子
export const deleteEmissionFactor = (id) => request.delete(`/emission-factors/${id}`)
