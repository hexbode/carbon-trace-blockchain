/**
 * @file 碳排放数据记录 / 排放源接口
 * @description 用途：企业排放数据填报、记录查询与删除，以及排放源字典读取。
 *              依赖：@/utils/request（统一 axios 实例，自动注入 JWT 并统一处理 401）。
 *              导出：getEmissionRecords、getEmissionRecord、createEmissionRecord、
 *                    deleteEmissionRecord、getEmissionSources。
 *              对应后端：EmissionRecordController（/api/emission-records）、
 *                        EmissionSourceController（/api/emission-sources）。
 */
import request from '@/utils/request'

/**
 * 排放记录列表（可按 enterpriseId、period、sourceCode、status、keyword 过滤）
 *
 * 接口：GET /emission-records
 * @param {Object} [params] 查询参数 { enterpriseId, period, sourceCode, status, keyword }
 * @returns {Promise<Array>} 排放记录列表，按提交时间倒序；企业端强制只返回本企业数据
 * @permission 登录即可；enterprise 角色按令牌归属过滤
 */
export const getEmissionRecords = (params) => request.get('/emission-records', { params })

/**
 * 排放记录详情
 *
 * 接口：GET /emission-records/{id}
 * @param {string} id 排放记录编号
 * @returns {Promise<Object>} 排放记录实体
 * @permission 登录即可；enterprise 角色仅可查看本企业记录，越权返回 403
 */
export const getEmissionRecord = (id) => request.get(`/emission-records/${id}`)

/**
 * 新增排放记录（后端自动计算排放量与电子防伪码）
 *
 * 接口：POST /emission-records
 * @param {Object} data 请求体 { enterpriseId, enterpriseName?, period, sourceCode, activityData }；
 *                      排放量由服务端按“活动数据 × 排放因子”计算，不接受前端传入
 * @returns {Promise<Object>} 落库后的排放记录（含自动编号、电子防伪码，并同步生成同号报告记录）
 * @permission 仅 enterprise / admin；企业端 enterpriseId 必须与令牌中的企业编号一致
 */
export const createEmissionRecord = (data) => request.post('/emission-records', data)

/**
 * 删除排放记录
 *
 * 接口：DELETE /emission-records/{id}
 * @param {string} id 排放记录编号
 * @returns {Promise<Object>} 空对象；链上存证仅标记作废，不做物理删除
 * @permission 仅 enterprise / admin；企业端仅可删除本企业数据
 */
export const deleteEmissionRecord = (id) => request.delete(`/emission-records/${id}`)

/**
 * 排放源字典
 *
 * 接口：GET /emission-sources
 * @returns {Promise<Array>} 全部排放源列表，供填报表单下拉选择
 * @permission 登录即可
 */
export const getEmissionSources = () => request.get('/emission-sources')
