/**
 * @file Axios 请求封装
 * @description 创建项目统一的 HTTP 请求实例，配置 baseURL 与超时时长，
 *              并通过请求/响应拦截器统一注入鉴权令牌、处理 401 未授权、
 *              错误提示与跳转登录等通用逻辑。
 * @module 网络请求工具
 */

// 引入 axios 作为 HTTP 客户端
import axios from 'axios'
// 引入 Element Plus 的全局消息提示组件
import { ElMessage } from 'element-plus'
// 引入路由实例，用于在鉴权失败时跳转登录页
import router from '@/router'

// 创建 axios 实例：baseURL 统一前缀 /api（由 Vite 代理转发到后端），超时 15 秒
const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// ============ 请求拦截器 ============
// 在请求头中自动携带 localStorage 中的登录令牌，格式：Bearer <token>
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('carbon_token')
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// ============ 响应拦截器 ============
// 成功：直接返回 response.data，剥离 axios 包装层
// 失败：401 清除登录信息并跳转登录页；其它错误统一弹出错误消息
request.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      // 令牌过期或未授权：清理本地登录态并跳转登录页
      localStorage.removeItem('carbon_token')
      localStorage.removeItem('carbon_user')
      ElMessage.error('登录已过期，请重新登录')
      router.push('/login')
    } else {
      // 其它 HTTP 错误：展示后端返回的错误信息或默认提示
      ElMessage.error(error.response?.data?.message || '请求失败')
    }
    return Promise.reject(error)
  }
)

export default request
