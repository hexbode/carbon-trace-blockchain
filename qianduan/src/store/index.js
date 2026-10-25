/**
 * @file 用户状态管理
 * @description 基于 Pinia 定义全局用户 Store，负责当前登录用户信息与访问令牌的
 *              状态维护、本地持久化（localStorage）以及登录/登出/资料更新等操作。
 * @module 全局状态模块（Pinia）
 *
 * 依赖：pinia 的 defineStore。
 * 导出：useUserStore（仓库 id 为 'user'）。
 * 持久化键：carbon_user（用户信息 JSON）、carbon_token（JWT），与 request.js、
 *          路由守卫读取的键保持一致；三处共同构成前端登录态。
 */

// 引入 Pinia 的 defineStore 方法用于定义状态仓库
import { defineStore } from 'pinia'

// 定义用户仓库，id 为 'user'，全局唯一
export const useUserStore = defineStore('user', {
  // ============ State：仓库初始状态 ============
  // 从 localStorage 读取已保存的用户信息与令牌，实现刷新后保持登录态
  // carbon_user 为空时解析为 null（避免 JSON.parse('') 抛错），carbon_token 缺省为空串
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
    /**
     * 登录：写入用户信息与令牌到 state 并持久化到 localStorage
     *
     * localStorage 与 state 同步写入，保证刷新页面后 state 能从本地恢复登录态。
     * @param {Object} user 用户信息 { username, name, role, enterpriseId, orgId }
     * @param {string} token 登录接口签发的 JWT
     * @returns {void}
     */
    login(user, token) {
      this.user = user
      this.token = token
      localStorage.setItem('carbon_user', JSON.stringify(user))
      localStorage.setItem('carbon_token', token)
    },
    /**
     * 登出：清空 state 与 localStorage 中的登录信息
     *
     * 两个持久化键必须一并清除，否则路由守卫仍会认为处于登录态；
     * 本方法只负责前端状态，不调用后端 /auth/logout（服务端 JWT 无状态、不吊销令牌）。
     * @returns {void}
     */
    logout() {
      this.user = null
      this.token = ''
      localStorage.removeItem('carbon_user')
      localStorage.removeItem('carbon_token')
    },
    /**
     * 更新当前用户资料并持久化
     *
     * 以浅合并方式局部更新（如头像、联系方式），未传入的字段保持原值。
     * @param {Object} patch 待更新的用户字段
     * @returns {void}
     */
    updateProfile(patch) {
      this.user = { ...this.user, ...patch }
      localStorage.setItem('carbon_user', JSON.stringify(this.user))
    }
  }
})
