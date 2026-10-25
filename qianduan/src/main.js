/**
 * @file main.js
 * @description Vue 应用入口文件
 * @module 应用入口
 *
 * 文件用途：
 *   创建 Vue 3 应用实例，完成状态管理（Pinia）、路由（vue-router）、
 *   UI 组件库（Element Plus 简体中文版）以及图标库的全局注册，
 *   最终将根组件挂载到 index.html 中的 #app 节点。
 *
 * 依赖：vue、pinia、vue-router（./router/index.js）、element-plus 及其中文语言包、
 *      @element-plus/icons-vue、根组件 App.vue、全局样式 global.css 与 mobile.css。
 * 导出：无（入口文件，仅执行副作用：创建实例、注册插件、挂载根组件）。
 */

// 引入 Vue 3 提供的工厂函数，用于创建应用实例
import { createApp } from 'vue'
// 引入 Pinia 状态管理工厂函数，用于集中管理全局共享状态
import { createPinia } from 'pinia'
// 引入 Element Plus UI 组件库（按需全量引入）
import ElementPlus from 'element-plus'
// 引入 Element Plus 全局样式表，提供组件基础样式
import 'element-plus/dist/index.css'
// 引入 Element Plus 简体中文语言包，使组件文案默认显示中文
import zhCn from 'element-plus/es/locale/lang/zh-cn.mjs'
// 引入 Element Plus 图标集合，包含所有内置 SVG 图标组件
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

// 引入根组件 App.vue，作为整个应用的渲染起点
import App from './App.vue'
// 引入路由配置模块，定义页面路由与跳转规则
import router from './router/index.js'
// 引入全局样式表，定义通用样式与基础重置
import './assets/styles/global.css'
// 引入移动端设计层（仅 ≤767px 生效，需在 global.css 之后加载以覆盖手机端样式）
// 两层顺序不可调换：global.css 提供通用移动端适配，mobile.css 在其之上做视觉覆盖
import './assets/styles/mobile.css'

// 创建 Vue 应用实例，以 App 根组件为参数
const app = createApp(App)

// 注册所有 Element Plus 图标，使其可在模板中通过组件名直接使用
// 路由菜单里的 icon 字段（如 HomeFilled）即依赖此处的全局注册
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 安装 Pinia 状态管理插件，启用全局 store 能力
// 需在 router 之前安装：路由守卫与页面组件会在初始化时读取用户状态
app.use(createPinia())
// 安装路由插件，启用声明式导航与页面切换
app.use(router)
// 安装 Element Plus，并指定简体中文为默认语言环境
// 语言包影响分页、日期选择、空状态等内置组件文案
app.use(ElementPlus, { locale: zhCn })

// 将应用实例挂载到 DOM 中 id 为 'app' 的节点，完成页面渲染
// 该节点由 index.html 提供；挂载后入口文件的初始化流程结束
app.mount('#app')
