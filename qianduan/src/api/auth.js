/**
 * @file 鉴权接口
 */
import request from '@/utils/request'

// 登录：返回 { token, user }
export const login = (data) => request.post('/auth/login', data)
// 当前登录用户
export const getCurrentUser = () => request.get('/auth/me')
// 退出登录
export const logout = () => request.post('/auth/logout')
