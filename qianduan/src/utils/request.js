/**
 * @file Axios 请求封装
 * @description 创建项目统一的 HTTP 请求实例，配置 baseURL 与超时时长，
 *              并通过请求/响应拦截器统一注入鉴权令牌、处理 401 未授权、
 *              错误提示与跳转登录等通用逻辑。
 * @module 网络请求工具
 *
 * 依赖：axios（HTTP 客户端）、element-plus 的 ElMessage（全局错误提示）、@/router（401 跳转）。
 * 导出：request（已配置拦截器的 axios 实例），所有 API 模块均基于它发起请求。
 */

// 引入 axios 作为 HTTP 客户端
import axios from 'axios'
// 引入 Element Plus 的全局消息提示组件
import { ElMessage } from 'element-plus'
// 引入路由实例，用于在鉴权失败时跳转登录页
import router from '@/router'

// 创建 axios 实例：baseURL 统一前缀 /api（由 Vite 代理转发到后端），超时 15 秒
// 各 API 模块只写业务路径（如 /auth/login），最终请求地址自动拼接为 /api/auth/login；
// 后端控制器统一以 /api 开头，因此前端无需重复书写前缀。
const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// ============ 请求拦截器 ============
// 在请求头中自动携带 localStorage 中的登录令牌，格式：Bearer <token>
// 令牌由登录接口签发并存入 carbon_token，刷新页面后仍可复用；
// 公共查询端虽不要求登录，但存在令牌时同样会携带，后端白名单接口会忽略该头。
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
// 因此业务代码拿到的是后端响应体本身，而不是 { data, status, headers } 结构。
request.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      // 令牌过期或未授权：清理本地登录态并跳转登录页
      // 直接操作 localStorage 而不经过 Pinia store，确保在 store 尚未初始化或
      // 组件外（如模块加载阶段）触发 401 时也能完成清理；
      // 路由使用 push 而非 replace，保留浏览器回退能力。
      localStorage.removeItem('carbon_token')
      localStorage.removeItem('carbon_user')
      ElMessage.error('登录已过期，请重新登录')
      router.push('/login')
    } else if (error.response?.status === 403 && error.response?.data?.code === 'PASSWORD_CHANGE_REQUIRED') {
      // 强制改密会话访问业务接口：后端返回专用错误码，前端统一跳转改密页
      ElMessage.warning(error.response?.data?.message || '首次登录需先修改密码')
      router.push('/change-password')
    } else {
      // 其它 HTTP 错误：展示后端返回的错误信息或默认提示
      // 后端统一错误体为 { message }，无该字段时退回通用文案。
      ElMessage.error(error.response?.data?.message || '请求失败')
    }
    // 继续向上抛出，调用方仍可通过 try/catch 针对具体错误做额外处理
    return Promise.reject(error)
  }
)

export default request
