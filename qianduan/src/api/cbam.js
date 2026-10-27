/**
 * @file CBAM 合规接口
 * @description 产品目录、生产装置、产品级隐含排放、核查机构/报告与年度申报包。
 *              对应后端：CbamController（@RequestMapping("/api/cbam")）。
 */
import request from '@/utils/request'

// ==================== 产品目录 ====================
export const getCbamGoods = (params) => request.get('/cbam/goods', { params })
export const createCbamGoods = (data) => request.post('/cbam/goods', data)
export const updateCbamGoods = (id, data) => request.put(`/cbam/goods/${id}`, data)
export const deleteCbamGoods = (id) => request.delete(`/cbam/goods/${id}`)

// ==================== 生产装置 ====================
export const getCbamInstallations = (params) => request.get('/cbam/installations', { params })
export const createCbamInstallation = (data) => request.post('/cbam/installations', data)
export const updateCbamInstallation = (id, data) => request.put(`/cbam/installations/${id}`, data)
export const deleteCbamInstallation = (id) => request.delete(`/cbam/installations/${id}`)

// ==================== 产品级排放 ====================
export const getCbamEmissions = (params) => request.get('/cbam/emissions', { params })
export const createCbamEmission = (data) => request.post('/cbam/emissions', data)
export const updateCbamEmission = (id, data) => request.put(`/cbam/emissions/${id}`, data)
export const deleteCbamEmission = (id) => request.delete(`/cbam/emissions/${id}`)

// ==================== 核查机构与报告 ====================
export const getCbamVerifiers = () => request.get('/cbam/verifiers')
export const createCbamVerifier = (data) => request.post('/cbam/verifiers', data)
export const getCbamVerificationReports = (params) => request.get('/cbam/verification-reports', { params })
export const createCbamVerificationReport = (data) => request.post('/cbam/verification-reports', data)

// ==================== 年度申报包 ====================
export const generateCbamDeclaration = (data) => request.post('/cbam/declarations/generate', data)
export const getCbamDeclarations = (params) => request.get('/cbam/declarations', { params })
export const getCbamDeclaration = (id) => request.get(`/cbam/declarations/${id}`)
export const exportCbamDeclaration = (id) => request.get(`/cbam/declarations/${id}/export`, { responseType: 'blob' })
export const submitCbamDeclaration = (id) => request.post(`/cbam/declarations/${id}/submit`)
export const reviewCbamDeclaration = (id, data) => request.post(`/cbam/declarations/${id}/review`, data)
export const getCbamStats = (params) => request.get('/cbam/stats', { params })
