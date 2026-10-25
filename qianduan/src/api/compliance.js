/**
 * @file 合规判定接口
 * @description 判定由链上智能合约（CarbonCompliance）执行，后端负责聚合入参与解读结果。
 *              用途：合规试算、证书合规快照、年度配额与治理阈值读写、公共端合规查询。
 *              依赖：@/utils/request（统一 axios 实例，自动注入 JWT 并统一处理 401）。
 *              导出：checkCompliance、getCertificateCompliance、getQuota、setQuota、
 *                    getThreshold、setThreshold、getPublicCompliance。
 *              对应后端：ComplianceController（@RequestMapping("/api/compliance")）；
 *                        公共端接口由 PublicController 提供（/api/public/compliance/{fingerprint}）。
 */
import request from '@/utils/request'

/**
 * 试算判定（不落库）：data = { enterpriseId, period, sourceCode, activityData, factor }
 *
 * 接口：POST /compliance/check
 * @param {Object} data 请求体，enterpriseId、period 必填，其余选填；试算结果不写库、不写链
 * @returns {Promise<Object>} { flags, onChain, compliant, notice, deltaBp, deltaPercent,
 *          previousFactor, quota, yearToDate, thresholdBp, thresholdPercent, items[] }
 * @permission 登录即可
 */
export const checkCompliance = (data) => request.post('/compliance/check', data)

/**
 * 证书的链上合规快照
 *
 * 接口：GET /compliance/certificate/{certId}
 * @param {string} certId 认证编号
 * @returns {Promise<Object>} { certId, flags, compliant, items[] }；链上无此编号时按 flags=0 返回
 * @permission 登录即可
 */
export const getCertificateCompliance = (certId) => request.get(`/compliance/certificate/${certId}`)

/**
 * 查年度配额：params = { enterpriseId, year }
 *
 * 接口：GET /compliance/quota
 * @param {Object} params 查询参数 { enterpriseId, year }，均为必填
 * @returns {Promise<Object>} { enterpriseId, year, quota }
 * @permission 登录即可（后端未按企业归属限制）
 */
export const getQuota = (params) => request.get('/compliance/quota', { params })

/**
 * 设置年度配额（监管端）：data = { enterpriseId, year, quota }
 *
 * 接口：PUT /compliance/quota
 * @param {Object} data 请求体 { enterpriseId, year, quota }，年份需在 2000~2100、配额非负
 * @returns {Promise<Object>} 写入后回查的 { enterpriseId, year, quota }
 * @permission 仅 regulator / admin；直接写合约，失败即整体失败
 */
export const setQuota = (data) => request.put('/compliance/quota', data)

/**
 * 查可治理参数（环比阈值基点、迟报月数）
 *
 * 接口：GET /compliance/threshold
 * @returns {Promise<Object>} { thresholdBp, thresholdPercent, lateReportMonths }（基点 2500 表示 25%）
 * @permission 登录即可
 */
export const getThreshold = () => request.get('/compliance/threshold')

/**
 * 修改可治理参数（监管端）：data = { thresholdBp?, lateReportMonths? }
 *
 * 接口：PUT /compliance/threshold
 * @param {Object} data 请求体，两项至少提供一项；均提供时按“先阈值、后月数”发起两次链上写
 * @returns {Promise<Object>} 写入后回查的 { thresholdBp, thresholdPercent, lateReportMonths }
 * @permission 仅 regulator / admin
 */
export const setThreshold = (data) => request.put('/compliance/threshold', data)

/**
 * 公共端：按电子防伪码取链上合规判定快照（免登录）
 *
 * 接口：GET /public/compliance/{fingerprint}
 * @param {string} fingerprint 电子防伪码
 * @returns {Promise<Object>} { fingerprint, flags, compliant, items[{rule, typeLabel, level}] }；
 *          链上查不到该防伪码时 flags=0、compliant=true（不返回 404）
 * @permission 无需登录（/api/public/** 整体位于后端拦截器白名单）
 */
export const getPublicCompliance = (fingerprint) => request.get(`/public/compliance/${fingerprint}`)
