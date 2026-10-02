/**
 * @file 用户状态管理
 * @description 基于 Pinia 定义全局用户 Store，负责当前登录用户信息与访问令牌的
 *              状态维护、本地持久化（localStorage）以及登录/登出/资料更新等操作。
 * @module 全局状态模块（Pinia）
 */

// 引入 Pinia 的 defineStore 方法用于定义状态仓库
import { defineStore } from 'pinia'

// 定义用户仓库，id 为 'user'，全局唯一
export const useUserStore = defineStore('user', {
  // ============ State：仓库初始状态 ============
  // 从 localStorage 读取已保存的用户信息与令牌，实现刷新后保持登录态
  state: () => ({
    user: JSON.parse(localStorage.getItem('carbon_user') || 'null'),
    token: localStorage.getItem('carbon_token') || ''
  }),
  // ============ Getters：派生状态（计算属性）============
  getters: {
    // 是否已登录（依据 token 是否存在）
    isLoggedIn: (state) => !!state.token,
    // 当前用户角色标识（enterprise/auditor/regulator/admin）
    role: (state) => state.user?.role || '',
    // 角色中文标签，用于界面展示
    roleLabel: (state) => {
      const map = { enterprise: '企业用户', auditor: '审核人员', regulator: '监管人员', admin: '系统管理员' }
      return map[state.user?.role] || ''
    },
    // 当前所属端（业务端口）中文标签
    portalLabel: (state) => {
      const map = { enterprise: '企业端', auditor: '审核端', regulator: '监管端', admin: '监管端' }
      return map[state.user?.role] || ''
    }
  },
  // ============ Actions：同步/异步操作方法 ============
  actions: {
    // 登录：写入用户信息与令牌到 state 并持久化到 localStorage
    login(user, token) {
      this.user = user
      this.token = token
      localStorage.setItem('carbon_user', JSON.stringify(user))
      localStorage.setItem('carbon_token', token)
    },
    // 登出：清空 state 与 localStorage 中的登录信息
    logout() {
      this.user = null
      this.token = ''
      localStorage.removeItem('carbon_user')
      localStorage.removeItem('carbon_token')
    },
    // 更新当前用户资料并持久化
    updateProfile(patch) {
      this.user = { ...this.user, ...patch }
      localStorage.setItem('carbon_user', JSON.stringify(this.user))
    }
  }
})
