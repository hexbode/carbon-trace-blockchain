/**
 * @file 用户管理接口
 * @description 用途：账号增删改查、密码重置/修改、账号设置维护与个人资料查询。
 *              依赖：@/utils/request（统一 axios 实例，自动注入 JWT 并统一处理 401）。
 *              导出：getUsers、getMyProfile、createUser、updateUser、deleteUser、
 *                    resetUserPassword、changeUserPassword、getUserSettings、updateUserSettings。
 *              对应后端：UserController（@RequestMapping("/api/users")）。
 */
import request from '@/utils/request'

/**
 * 用户列表（可按 username、role 过滤）
 *
 * 接口：GET /users
 * @param {Object} [params] 查询参数 { username: 账号模糊关键字, role: 角色标识 }
 * @returns {Promise<Array>} 用户列表；密码字段为 WRITE_ONLY，不会回显
 * @permission 仅 regulator / admin
 */
export const getUsers = (params) => request.get('/users', { params })

/**
 * 当前登录用户的个人资料（个人中心用，任意角色可查本人）
 *
 * 接口：GET /users/me
 * @returns {Promise<Object>} 当前用户实体；按令牌中的 username 查询，不接受 id 参数
 * @permission 任意已登录角色
 */
export const getMyProfile = () => request.get('/users/me')

/**
 * 新增用户
 *
 * 接口：POST /users
 * @param {Object} data 用户实体；username、role 必填，password 缺省时写入初始密码 123456 的哈希
 * @returns {Promise<Object>} 落库后的用户（含自增 id，不含密码）
 * @permission 仅 regulator / admin
 */
export const createUser = (data) => request.post('/users', data)

/**
 * 更新用户
 *
 * 接口：PUT /users/{id}
 * @param {number} id 目标用户主键
 * @param {Object} data 待更新字段；普通角色仅能改本人展示字段，role/status/enterpriseId/orgId 仅监管/管理员可改
 * @returns {Promise<Object>} 更新后重新读取的用户
 * @permission regulator / admin 可改任意用户；其他角色仅能改本人（否则 403）
 */
export const updateUser = (id, data) => request.put(`/users/${id}`, data)

/**
 * 删除用户
 *
 * 接口：DELETE /users/{id}
 * @param {number} id 目标用户主键
 * @returns {Promise<Object>} 空对象；内置 admin 账号禁止删除
 * @permission 仅 regulator / admin
 */
export const deleteUser = (id) => request.delete(`/users/${id}`)

/**
 * 重置密码为 123456
 *
 * 接口：POST /users/{id}/reset-password
 * @param {number} id 目标用户主键
 * @returns {Promise<Object>} 重置后的用户（密码不回显）
 * @permission 仅 regulator / admin
 */
export const resetUserPassword = (id) => request.post(`/users/${id}/reset-password`)

/**
 * 修改密码（校验旧密码）
 *
 * 接口：PUT /users/{id}/password
 * @param {number} id 目标用户主键
 * @param {Object} data 请求体 { oldPassword, newPassword }，新密码去空白后需不少于 6 位
 * @returns {Promise<Object>} 空对象
 * @permission 本人或 regulator / admin
 */
export const changeUserPassword = (id, data) => request.put(`/users/${id}/password`, data)

/**
 * 获取账号设置
 *
 * 接口：GET /users/{id}/settings
 * @param {number} id 目标用户主键
 * @returns {Promise<Object>} 4 个布尔开关（提醒/订阅类偏好）；未初始化时返回默认值
 * @permission 本人或 regulator / admin
 */
export const getUserSettings = (id) => request.get(`/users/${id}/settings`)

/**
 * 更新账号设置
 *
 * 接口：PUT /users/{id}/settings
 * @param {number} id 目标用户主键
 * @param {Object} data 4 个布尔开关字段，未提交的字段保持原值
 * @returns {Promise<Object>} 更新后的账号设置
 * @permission 本人或 regulator / admin
 */
export const updateUserSettings = (id, data) => request.put(`/users/${id}/settings`, data)
