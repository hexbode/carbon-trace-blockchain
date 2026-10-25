/**
 * @file 审核机构接口
 * @description 用途：审核机构档案的查询与维护（监管端机构管理页使用）。
 *              依赖：@/utils/request（统一 axios 实例，自动注入 JWT 并统一处理 401）。
 *              导出：getAuditOrganizations、createAuditOrganization、updateAuditOrganization、deleteAuditOrganization。
 *              对应后端：AuditOrganizationController（@RequestMapping("/api/audit-organizations")）。
 */
import request from '@/utils/request'

/**
 * 机构列表（可按 name 过滤）
 *
 * 接口：GET /audit-organizations
 * @param {Object} [params] 查询参数 { name: 机构名模糊关键字 }
 * @returns {Promise<Array>} 审核机构列表，按编号升序
 * @permission 登录即可
 */
export const getAuditOrganizations = (params) => request.get('/audit-organizations', { params })

/**
 * 新增机构
 *
 * 接口：POST /audit-organizations
 * @param {Object} data 审核机构实体；id、status、createdAt 缺省时由后端补全
 * @returns {Promise<Object>} 落库后的审核机构
 * @permission 仅 regulator / admin
 */
export const createAuditOrganization = (data) => request.post('/audit-organizations', data)

/**
 * 更新机构
 *
 * 接口：PUT /audit-organizations/{id}
 * @param {string} id 机构编号（以路径为准，覆盖请求体中的 id）
 * @param {Object} data 待更新字段，null 字段保持原值
 * @returns {Promise<Object>} 更新后回查的审核机构
 * @permission 仅 regulator / admin
 */
export const updateAuditOrganization = (id, data) => request.put(`/audit-organizations/${id}`, data)

/**
 * 删除机构
 *
 * 接口：DELETE /audit-organizations/{id}
 * @param {string} id 机构编号
 * @returns {Promise<Object>} 空对象；被审核员账号引用时返回 400
 * @permission 仅 regulator / admin
 */
export const deleteAuditOrganization = (id) => request.delete(`/audit-organizations/${id}`)
