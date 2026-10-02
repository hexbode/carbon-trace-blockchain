/**
 * @file 公共查询端接口（无需登录）
 */
import request from '@/utils/request'

// 企业列表
export const getPublicEnterprises = () => request.get('/public/enterprises')
// 企业排放概览
export const getPublicEnterpriseEmissions = () => request.get('/public/enterprise-emissions')
// 二维码验证：按电子防伪码返回证书信息
export const qrResolve = (params) => request.get('/public/qr-resolve', { params })
// 固定示例证书（3 张演示证书，无参数、不可遍历）
export const getSampleCerts = () => request.get('/public/sample-certs')
