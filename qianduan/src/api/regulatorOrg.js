/**
 * @file 监管机构接口（监管端）
 * @description 用途：监管机构档案的查询与维护，供监管端账号管理关联所属机构。
 *              依赖：@/utils/request（统一 axios 实例，自动注入 JWT 并统一处理 401）。
 *              导出：getRegulatorOrganizations、createRegulatorOrganization、
 *                    updateRegulatorOrganization、deleteRegulatorOrganization。
 *              对应后端：RegulatorOrganizationController（@RequestMapping("/api/regulator-organizations")）。
 */
import request from '@/utils/request'

/**
 * 监管机构列表（可按 name 过滤）
 *
 * 接口：GET /regulator-organizations
 * @param {Object} [params] 查询参数 { name: 机构名模糊关键字 }
 * @returns {Promise<Array>} 监管机构列表，按编号升序
 * @permission 仅 regulator / admin（与其他字典不同，查询也不对企业端/审核端开放）
 */
export const getRegulatorOrganizations = (params) => request.get('/regulator-organizations', { params })

/**
 * 新增监管机构
 *
 * 接口：POST /regulator-organizations
 * @param {Object} data 机构实体；名称、所属区域、联系人、联系电话、办公地址、监管职责均为必填
 * @returns {Promise<Object>} 落库后的监管机构
 * @permission 仅 regulator / admin
 */
export const createRegulatorOrganization = (data) => request.post('/regulator-organizations', data)

/**
 * 更新监管机构
 *
 * 接口：PUT /regulator-organizations/{id}
 * @param {string} id 机构编号（以路径为准，覆盖请求体中的 id）
 * @param {Object} data 待更新字段，null 字段保持原值
 * @returns {Promise<Object>} 更新后回查的监管机构
 * @permission 仅 regulator / admin
 */
export const updateRegulatorOrganization = (id, data) => request.put(`/regulator-organizations/${id}`, data)

/**
 * 删除监管机构
 *
 * 接口：DELETE /regulator-organizations/{id}
 * @param {string} id 机构编号
 * @returns {Promise<Object>} 空对象；被监管员账号 orgId 引用时返回 400
 * @permission 仅 regulator / admin
 */
export const deleteRegulatorOrganization = (id) => request.delete(`/regulator-organizations/${id}`)
