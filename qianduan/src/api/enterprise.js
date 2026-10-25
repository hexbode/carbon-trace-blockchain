/**
 * @file 企业接口
 * @description 用途：企业档案查询、行业字典读取，以及监管端企业档案维护。
 *              依赖：@/utils/request（统一 axios 实例，自动注入 JWT 并统一处理 401）。
 *              导出：getEnterprises、getEnterpriseIndustries、getEnterprise、
 *                    createEnterprise、updateEnterprise、deleteEnterprise。
 *              对应后端：EnterpriseController（@RequestMapping("/api/enterprises")）。
 */
import request from '@/utils/request'

/**
 * 企业列表（可按 name、industry 过滤）
 *
 * 接口：GET /enterprises
 * @param {Object} [params] 查询参数 { name: 企业名模糊关键字, industry: 行业精确值 }
 * @returns {Promise<Array>} 企业列表，按企业编号升序
 * @permission 登录即可（后端未按企业归属限制）
 */
export const getEnterprises = (params) => request.get('/enterprises', { params })

/**
 * 行业字典（去重）
 *
 * 接口：GET /enterprises/industries
 * @returns {Promise<Array<string>>} 去重升序的行业名称列表，无数据时为空数组
 * @permission 登录即可
 */
export const getEnterpriseIndustries = () => request.get('/enterprises/industries')

/**
 * 企业详情
 *
 * 接口：GET /enterprises/{id}
 * @param {string} id 企业编号（ENT 开头）
 * @returns {Promise<Object>} 企业实体；不存在时返回 404
 * @permission 登录即可
 */
export const getEnterprise = (id) => request.get(`/enterprises/${id}`)

/**
 * 新增企业
 *
 * 接口：POST /enterprises
 * @param {Object} data 企业实体；name、industry、creditCode 为必填，id、status、注册时间由后端补全
 * @returns {Promise<Object>} 落库后的企业
 * @permission 仅 regulator / admin
 */
export const createEnterprise = (data) => request.post('/enterprises', data)

/**
 * 更新企业
 *
 * 接口：PUT /enterprises/{id}
 * @param {string} id 企业编号（以路径为准，覆盖请求体中的 id）
 * @param {Object} data 待更新字段，null 字段保持原值
 * @returns {Promise<Object>} 更新后回查的企业
 * @permission 仅 regulator / admin
 */
export const updateEnterprise = (id, data) => request.put(`/enterprises/${id}`, data)

/**
 * 删除企业
 *
 * 接口：DELETE /enterprises/{id}
 * @param {string} id 企业编号
 * @returns {Promise<Object>} 空对象；被用户账号或排放记录引用时返回 400
 * @permission 仅 regulator / admin
 */
export const deleteEnterprise = (id) => request.delete(`/enterprises/${id}`)
