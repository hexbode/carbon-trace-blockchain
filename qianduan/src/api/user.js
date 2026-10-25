/**
 * @file 用户管理接口
 */
import request from '@/utils/request'

// 用户列表（可按 username、role 过滤）
export const getUsers = (params) => request.get('/users', { params })
// 当前登录用户的个人资料（个人中心用，任意角色可查本人）
export const getMyProfile = () => request.get('/users/me')
// 新增用户
export const createUser = (data) => request.post('/users', data)
// 更新用户
export const updateUser = (id, data) => request.put(`/users/${id}`, data)
// 删除用户
export const deleteUser = (id) => request.delete(`/users/${id}`)
// 重置密码为 123456
export const resetUserPassword = (id) => request.post(`/users/${id}/reset-password`)
// 修改密码（校验旧密码）
export const changeUserPassword = (id, data) => request.put(`/users/${id}/password`, data)
// 获取账号设置
export const getUserSettings = (id) => request.get(`/users/${id}/settings`)
// 更新账号设置
export const updateUserSettings = (id, data) => request.put(`/users/${id}/settings`, data)
