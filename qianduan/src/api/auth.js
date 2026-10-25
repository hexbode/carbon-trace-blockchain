/**
 * @file 鉴权接口
 * @description 用途：登录、获取当前登录用户、退出登录。
 *              依赖：@/utils/request（统一 axios 实例，自动注入 JWT 并统一处理 401）。
 *              导出：login、getCurrentUser、logout。
 *              对应后端：AuthController（@RequestMapping("/api/auth")）。
 */
import request from '@/utils/request'

/**
 * 登录：返回 { token, user }
 *
 * 接口：POST /auth/login（后端完整路径 /api/auth/login）
 * @param {Object} data 请求体 { username, password, role }，role 须与账号实际角色一致
 * @returns {Promise<{token: string, user: Object}>} JWT 令牌与用户信息
 * @permission 无需登录（登录接口位于后端拦截器白名单）
 */
export const login = (data) => request.post('/auth/login', data)

/**
 * 当前登录用户
 *
 * 接口：GET /auth/me
 * @returns {Promise<Object>} 当前用户 { username, name, role, enterpriseId, orgId }
 * @permission 需登录；401 表示令牌缺失或已失效
 */
export const getCurrentUser = () => request.get('/auth/me')

/**
 * 退出登录
 *
 * 接口：POST /auth/logout
 * @returns {Promise<Object>} 空对象；JWT 无状态，服务端不吊销令牌，前端须自行清理本地登录态
 * @permission 需登录
 */
export const logout = () => request.post('/auth/logout')
