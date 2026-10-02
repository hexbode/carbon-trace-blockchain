/**
 * @file 认证记录接口
 */
import request from '@/utils/request'

// 认证列表（可按 enterpriseId、status、keyword 过滤）
export const getCertifications = (params) => request.get('/certifications', { params })
// 认证详情
export const getCertification = (id) => request.get(`/certifications/${id}`)
// 按电子防伪码精确查询（认证编号不对外提供查询）
export const getCertificationByFingerprint = (fingerprint) => request.get(`/certifications/by-fingerprint/${fingerprint}`)
// 校验防伪码 / 交易哈希：data = { hash }
export const verifyCertification = (data) => request.post('/certifications/verify', data)
// 撤销认证：data = { reason, detail }
export const revokeCertification = (id, data) => request.post(`/certifications/${id}/revoke`, data)
