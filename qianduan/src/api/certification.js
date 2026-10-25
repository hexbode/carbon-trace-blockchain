/**
 * @file 认证记录接口
 * @description 用途：认证记录查询、证书防伪码/交易哈希核验以及监管端撤销认证。
 *              依赖：@/utils/request（统一 axios 实例，自动注入 JWT 并统一处理 401）。
 *              导出：getCertifications、getCertification、getCertificationByFingerprint、
 *                    verifyCertification、revokeCertification。
 *              对应后端：CertificationController（@RequestMapping("/api/certifications")）。
 */
import request from '@/utils/request'

/**
 * 认证列表（可按 enterpriseId、status、keyword 过滤）
 *
 * 接口：GET /certifications
 * @param {Object} [params] 查询参数 { enterpriseId, status, keyword }
 * @returns {Promise<Array>} 认证记录列表；企业端调用时后端强制只返回本企业记录
 * @permission 登录即可；enterprise 角色按令牌归属过滤
 */
export const getCertifications = (params) => request.get('/certifications', { params })

/**
 * 认证详情
 *
 * 接口：GET /certifications/{id}
 * @param {string} id 认证编号（CR 开头）
 * @returns {Promise<Object>} 认证记录，含链上防伪码、交易哈希与区块信息
 * @permission 登录即可；enterprise 角色仅可查看本企业记录，越权返回 403
 */
export const getCertification = (id) => request.get(`/certifications/${id}`)

/**
 * 按电子防伪码精确查询（认证编号不对外提供查询）
 *
 * 接口：GET /certifications/by-fingerprint/{fingerprint}
 * @param {string} fingerprint 12 位电子防伪码
 * @returns {Promise<Object>} 以链上存证为准的认证记录；查不到返回 404
 * @permission 无需登录（位于后端拦截器白名单，供公众扫码核验）
 */
export const getCertificationByFingerprint = (fingerprint) => request.get(`/certifications/by-fingerprint/${fingerprint}`)

/**
 * 校验防伪码 / 交易哈希：data = { hash }
 *
 * 接口：POST /certifications/verify
 * @param {Object} data 请求体 { hash }，防伪码与交易哈希都通过该字段传入
 * @returns {Promise<{valid: boolean, msg: string, cert: Object|null}>} 核验结果；
 *          已撤销/作废的证书 valid=false 但仍返回 cert 供展示原因
 * @permission 无需登录（位于后端拦截器白名单）
 */
export const verifyCertification = (data) => request.post('/certifications/verify', data)

/**
 * 撤销认证：data = { reason, detail }
 *
 * 接口：POST /certifications/{id}/revoke
 * @param {string} id 认证编号
 * @param {Object} data 请求体 { reason 必填, detail 选填 }
 * @returns {Promise<Object>} 撤销后的认证记录（status=revoked，含撤销原因与时间）
 * @permission 仅 regulator / admin；链上状态置为 REVOKED 且不可逆
 */
export const revokeCertification = (id, data) => request.post(`/certifications/${id}/revoke`, data)
