/**
 * @file 碳排放因子库接口
 * @description 用途：排放因子（活动数据换算为碳排放量的系数）查询与监管端维护。
 *              依赖：@/utils/request（统一 axios 实例，自动注入 JWT 并统一处理 401）。
 *              导出：getEmissionFactors、createEmissionFactor、updateEmissionFactor、deleteEmissionFactor。
 *              对应后端：EmissionFactorController（@RequestMapping("/api/emission-factors")）。
 */
import request from '@/utils/request'

/**
 * 因子列表（可按 category、keyword 过滤）
 *
 * 接口：GET /emission-factors
 * @param {Object} [params] 查询参数 { category: 类别精确值, keyword: 排放源名称模糊关键字 }
 * @returns {Promise<Array>} 因子列表，按主键升序；停用因子也会返回
 * @permission 登录即可
 */
export const getEmissionFactors = (params) => request.get('/emission-factors', { params })

/**
 * 新增因子
 *
 * 接口：POST /emission-factors
 * @param {Object} data 因子实体；source、category、factor、unit 为必填，id/status/updatedAt 由后端补全
 * @returns {Promise<Object>} 落库后的因子
 * @permission 仅 regulator / admin
 */
export const createEmissionFactor = (data) => request.post('/emission-factors', data)

/**
 * 更新因子
 *
 * 接口：PUT /emission-factors/{id}
 * @param {number} id 因子主键（以路径为准，覆盖请求体中的 id）
 * @param {Object} data 待更新字段；updatedAt 由服务端强制刷新，null 字段保持原值
 * @returns {Promise<Object>} 更新后回查的因子
 * @permission 仅 regulator / admin
 */
export const updateEmissionFactor = (id, data) => request.put(`/emission-factors/${id}`, data)

/**
 * 删除因子
 *
 * 接口：DELETE /emission-factors/{id}
 * @param {number} id 因子主键
 * @returns {Promise<Object>} 空对象；历史排放记录保存的是因子数值快照，删除不影响已核算结果
 * @permission 仅 regulator / admin
 */
export const deleteEmissionFactor = (id) => request.delete(`/emission-factors/${id}`)
