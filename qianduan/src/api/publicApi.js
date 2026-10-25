/**
 * @file 公共查询端接口（无需登录）
 * @description 用途：面向社会公众的企业信息、排放概览、二维码证书解析与示例证书查询。
 *              依赖：@/utils/request（统一 axios 实例；公共接口不依赖 JWT，但响应拦截器仍统一处理错误）。
 *              导出：getPublicEnterprises、getPublicEnterpriseEmissions、qrResolve、getSampleCerts。
 *              对应后端：PublicController（@RequestMapping("/api/public")，整体位于拦截器白名单）。
 */
import request from '@/utils/request'

/**
 * 企业列表
 *
 * 接口：GET /public/enterprises
 * @returns {Promise<Array>} 全部企业列表，按企业编号升序（实体字段原样返回）
 * @permission 无需登录
 */
export const getPublicEnterprises = () => request.get('/public/enterprises')

/**
 * 企业排放概览
 *
 * 接口：GET /public/enterprise-emissions
 * @returns {Promise<Array>} 各企业排放总量等公开聚合指标，供公共端图表展示
 * @permission 无需登录
 */
export const getPublicEnterpriseEmissions = () => request.get('/public/enterprise-emissions')

/**
 * 二维码验证：按电子防伪码返回证书信息
 *
 * 接口：GET /public/qr-resolve
 * @param {Object} params 查询参数 { fingerprint: 12 位电子防伪码 }（必填）
 * @returns {Promise<Object>} 以链上存证为准的认证记录；防伪码缺失返回 400，查不到返回 404
 * @permission 无需登录
 */
export const qrResolve = (params) => request.get('/public/qr-resolve', { params })

/**
 * 固定示例证书（3 张演示证书，无参数、不可遍历）
 *
 * 接口：GET /public/sample-certs
 * @returns {Promise<Array>} 固定返回 CR000001~CR000003 三条演示数据，接口无可枚举参数
 * @permission 无需登录
 */
export const getSampleCerts = () => request.get('/public/sample-certs')
