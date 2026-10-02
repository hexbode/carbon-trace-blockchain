/**
 * @file 路由配置文件
 * @description 定义"碳链通"碳排放管理系统的全部前端路由，包含企业端、审核端、监管端
 *              三大业务端的页面跳转、侧边栏菜单配置以及全局路由守卫（登录校验 + 角色鉴权）。
 * @module 路由模块
 */

// 引入 vue-router 的路由创建方法与 Hash 模式历史记录
import { createRouter, createWebHashHistory } from 'vue-router'

// 各端菜单定义（用于侧边栏渲染与权限控制）
// key 为角色标识，items 为该角色可见的菜单项（路径、路由名、显示标题、图标）
export const menuConfig = {
  enterprise: {
    title: '企业端',
    items: [
      { path: '/enterprise/home', name: 'EnterpriseHome', title: '企业首页', icon: 'HomeFilled' },
      { path: '/enterprise/emission', name: 'EmissionEntry', title: '碳排放数据填报', icon: 'EditPen' },
      { path: '/enterprise/trend', name: 'EmissionTrend', title: '排放趋势统计', icon: 'TrendCharts' },
      { path: '/enterprise/records', name: 'ReportRecords', title: '报告提交记录', icon: 'Document' },
      { path: '/enterprise/apply', name: 'CertificationApply', title: '认证申请', icon: 'Promotion' },
      { path: '/enterprise/certificate', name: 'CertificateView', title: '认证证书查看', icon: 'Stamp' }
    ]
  },
  auditor: {
    title: '审核端',
    items: [
      { path: '/auditor/home', name: 'AuditorHome', title: '审核工作台', icon: 'HomeFilled' },
      { path: '/auditor/pending', name: 'PendingReports', title: '待审核报告', icon: 'DocumentChecked' },
      { path: '/auditor/today', name: 'TodayAudit', title: '今日审核', icon: 'Calendar' },
      { path: '/auditor/history', name: 'AuditHistory', title: '审核历史', icon: 'Clock' }
    ]
  },
  regulator: {
    title: '监管端',
    items: [
      { path: '/regulator/home', name: 'RegulatorHome', title: '监管总览', icon: 'HomeFilled' },
      { path: '/regulator/enterprise', name: 'EnterpriseManage', title: '企业管理', icon: 'OfficeBuilding' },
      { path: '/regulator/audit-org', name: 'AuditOrgManage', title: '审核机构管理', icon: 'School' },
      { path: '/regulator/factor', name: 'FactorManage', title: '碳排放因子管理', icon: 'Coin' },
      { path: '/regulator/revoke', name: 'CertificationRevoke', title: '认证管理', icon: 'CircleClose' },
      { path: '/regulator/industry-stats', name: 'IndustryStats', title: '行业碳排放统计', icon: 'DataLine' },
      { path: '/regulator/alerts', name: 'Alerts', title: '异常数据监控', icon: 'Warning' },
      { path: '/regulator/users', name: 'UserManage', title: '用户管理', icon: 'User' }
    ]
  }
}

// ============ 全部路由表 ============
// 包含登录页、公共查询端、企业端、审核端、监管端等所有路由配置
const routes = [
  // 登录页（独立路由，不进入主布局）
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录' }
  },
  // 公共查询端（无需登录，面向社会公众的认证查询入口）
  {
    path: '/public',
    component: () => import('@/views/PublicLayout.vue'),
    redirect: '/public/query',
    children: [
      { path: 'query', name: 'PublicQuery', component: () => import('@/views/public/CertQuery.vue'), meta: { title: '电子防伪码查询', icon: 'Search' } },
      { path: 'qr', name: 'PublicQr', component: () => import('@/views/public/QrVerify.vue'), meta: { title: '二维码验证', icon: 'FullScreen' } },
      { path: 'enterprise', name: 'PublicEnterprise', component: () => import('@/views/public/EnterpriseEmissionView.vue'), meta: { title: '企业碳排放信息', icon: 'OfficeBuilding' } },
      { path: 'validity', name: 'PublicValidity', component: () => import('@/views/public/CertValidity.vue'), meta: { title: '认证有效性验证', icon: 'CircleCheck' } }
    ]
  },
  // 企业端（含审核端、监管端，统一使用 Layout 主布局，默认重定向到企业首页）
  {
    path: '/',
    component: () => import('@/views/Layout.vue'),
    redirect: '/enterprise/home',
    children: [
      // 个人中心（所有登录角色通用，不出现在侧边栏）
      { path: 'profile', name: 'Profile', component: () => import('@/views/Profile.vue'), meta: { title: '个人中心', roles: ['enterprise', 'auditor', 'regulator', 'admin'] } },
      // ---- 企业端路由（role: enterprise）----
      { path: 'enterprise/home', name: 'EnterpriseHome', component: () => import('@/views/enterprise/EnterpriseHome.vue'), meta: { title: '企业首页', roles: ['enterprise'] } },
      { path: 'enterprise/emission', name: 'EmissionEntry', component: () => import('@/views/enterprise/EmissionEntry.vue'), meta: { title: '碳排放数据填报', roles: ['enterprise'] } },
      { path: 'enterprise/trend', name: 'EmissionTrend', component: () => import('@/views/enterprise/EmissionTrend.vue'), meta: { title: '排放趋势统计', roles: ['enterprise'] } },
      { path: 'enterprise/records', name: 'ReportRecords', component: () => import('@/views/enterprise/ReportRecords.vue'), meta: { title: '报告提交记录', roles: ['enterprise'] } },
      { path: 'enterprise/apply', name: 'CertificationApply', component: () => import('@/views/enterprise/CertificationApply.vue'), meta: { title: '认证申请', roles: ['enterprise'] } },
      { path: 'enterprise/certificate', name: 'CertificateView', component: () => import('@/views/enterprise/CertificateView.vue'), meta: { title: '认证证书查看', roles: ['enterprise'] } },
      // ---- 审核端路由（role: auditor）----
      { path: 'auditor/home', name: 'AuditorHome', component: () => import('@/views/auditor/AuditorHome.vue'), meta: { title: '审核工作台', roles: ['auditor'] } },
      { path: 'auditor/pending', name: 'PendingReports', component: () => import('@/views/auditor/PendingReports.vue'), meta: { title: '待审核报告', roles: ['auditor'] } },
      { path: 'auditor/today', name: 'TodayAudit', component: () => import('@/views/auditor/TodayAudit.vue'), meta: { title: '今日审核', roles: ['auditor'] } },
      { path: 'auditor/history', name: 'AuditHistory', component: () => import('@/views/auditor/AuditHistory.vue'), meta: { title: '审核历史', roles: ['auditor'] } },
      // ---- 监管端路由（role: regulator / admin）----
      { path: 'regulator/home', name: 'RegulatorHome', component: () => import('@/views/regulator/RegulatorHome.vue'), meta: { title: '监管总览', roles: ['regulator', 'admin'] } },
      { path: 'regulator/enterprise', name: 'EnterpriseManage', component: () => import('@/views/regulator/EnterpriseManage.vue'), meta: { title: '企业管理', roles: ['regulator', 'admin'] } },
      { path: 'regulator/audit-org', name: 'AuditOrgManage', component: () => import('@/views/regulator/AuditOrgManage.vue'), meta: { title: '审核机构管理', roles: ['regulator', 'admin'] } },
      { path: 'regulator/factor', name: 'FactorManage', component: () => import('@/views/regulator/FactorManage.vue'), meta: { title: '碳排放因子管理', roles: ['regulator', 'admin'] } },
      { path: 'regulator/revoke', name: 'CertificationRevoke', component: () => import('@/views/regulator/CertificationRevoke.vue'), meta: { title: '认证管理', roles: ['regulator', 'admin'] } },
      { path: 'regulator/industry-stats', name: 'IndustryStats', component: () => import('@/views/regulator/IndustryStats.vue'), meta: { title: '行业碳排放统计', roles: ['regulator', 'admin'] } },
      { path: 'regulator/alerts', name: 'Alerts', component: () => import('@/views/regulator/Alerts.vue'), meta: { title: '异常数据监控', roles: ['regulator', 'admin'] } },
      { path: 'regulator/users', name: 'UserManage', component: () => import('@/views/regulator/UserManage.vue'), meta: { title: '用户管理', roles: ['regulator', 'admin'] } }
    ]
  },
  // 兜底路由：未匹配到的所有路径统一重定向到企业首页
  { path: '/:pathMatch(.*)*', redirect: '/enterprise/home' }
]

// 创建路由实例，采用 Hash 模式历史记录（兼容静态部署环境）
const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// ============ 全局前置守卫 ============
// 负责：动态设置页面标题、登录态校验、角色权限鉴权
router.beforeEach((to, from, next) => {
  // 从本地存储读取登录令牌与用户信息
  const token = localStorage.getItem('carbon_token')
  const userStr = localStorage.getItem('carbon_user')
  // 根据路由 meta.title 动态更新浏览器标签页标题
  document.title = to.meta.title ? `${to.meta.title} - 碳链通` : '碳链通'

  // 公共查询端无需登录
  if (to.path.startsWith('/public')) {
    next()
    return
  }

  // 登录页直接放行
  if (to.path === '/login') {
    next()
    return
  }

  // 未登录则强制跳转登录页
  if (!token || !userStr) {
    next('/login')
    return
  }

  // 角色权限校验：若路由声明了 roles 且当前用户角色不在其中，则重定向到对应端首页
  const user = JSON.parse(userStr)
  if (to.meta.roles && !to.meta.roles.includes(user.role)) {
    // 重定向到对应端首页
    const redirectMap = { enterprise: '/enterprise/home', auditor: '/auditor/home', regulator: '/regulator/home', admin: '/regulator/home' }
    next(redirectMap[user.role] || '/enterprise/home')
    return
  }

  next()
})

export default router
