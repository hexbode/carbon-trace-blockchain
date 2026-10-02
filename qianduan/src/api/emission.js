/**
 * @file 碳排放数据记录 / 排放源接口
 */
import request from '@/utils/request'

// 排放记录列表（可按 enterpriseId、period、sourceCode、status、keyword 过滤）
export const getEmissionRecords = (params) => request.get('/emission-records', { params })
// 排放记录详情
export const getEmissionRecord = (id) => request.get(`/emission-records/${id}`)
// 新增排放记录（后端自动计算排放量与电子防伪码）
export const createEmissionRecord = (data) => request.post('/emission-records', data)
// 删除排放记录
export const deleteEmissionRecord = (id) => request.delete(`/emission-records/${id}`)
// 排放源字典
export const getEmissionSources = () => request.get('/emission-sources')
