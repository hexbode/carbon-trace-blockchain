/**
 * @file 合规判定接口
 * @description 判定由链上智能合约（CarbonCompliance）执行，后端负责聚合入参与解读结果
 */
import request from '@/utils/request'

// 试算判定（不落库）：data = { enterpriseId, period, sourceCode, activityData, factor }
export const checkCompliance = (data) => request.post('/compliance/check', data)
// 证书的链上合规快照
export const getCertificateCompliance = (certId) => request.get(`/compliance/certificate/${certId}`)
// 查年度配额：params = { enterpriseId, year }
export const getQuota = (params) => request.get('/compliance/quota', { params })
// 设置年度配额（监管端）：data = { enterpriseId, year, quota }
export const setQuota = (data) => request.put('/compliance/quota', data)
// 查可治理参数（环比阈值基点、迟报月数）
export const getThreshold = () => request.get('/compliance/threshold')
// 修改可治理参数（监管端）：data = { thresholdBp?, lateReportMonths? }
export const setThreshold = (data) => request.put('/compliance/threshold', data)
// 公共端：按电子防伪码取链上合规判定快照（免登录）
export const getPublicCompliance = (fingerprint) => request.get(`/public/compliance/${fingerprint}`)
