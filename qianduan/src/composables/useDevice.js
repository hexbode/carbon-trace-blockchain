/**
 * @file useDevice.js
 * @description 设备类型检测组合式函数
 * @module 设备适配
 *
 * 说明：
 *   以视口宽度作为判断依据（< 768px 视为手机），返回全局共享的响应式 isMobile，
 *   窗口尺寸变化时自动更新。布局组件据此渲染移动端页面结构（抽屉菜单 + 底部导航），
 *   桌面端保持原有侧边栏布局。
 *
 * 依赖：vue 的 ref / computed。
 * 导出：useDevice，返回 { isMobile, isTablet, width }。
 * 断点分段：≤767px 为手机（与 Element Plus 的 md 断点、global.css 的移动端媒体查询一致）；
 *          768~1024px 为平板 / 窄屏桌面；>1024px 为常规桌面。
 */
import { computed, ref } from 'vue'

// 移动端断点：与 Element Plus 的 md 断点保持一致（max-width: 767px 即手机）
const MOBILE_MAX_WIDTH = 767

// 全局共享的设备状态（单例，避免多组件重复监听 resize）
// 服务端渲染或非浏览器环境下没有 window，回退为 1280（按桌面处理）
const width = ref(typeof window !== 'undefined' ? window.innerWidth : 1280)

// 模块级只注册一次 resize 监听：所有调用 useDevice 的组件共享同一份 width，
// 因此不会随组件数量增加监听器。该监听生命周期与整个应用一致，不做移除。
if (typeof window !== 'undefined') {
  window.addEventListener('resize', () => {
    width.value = window.innerWidth
  })
}

/**
 * 获取响应式设备类型
 *
 * 可在任意组件的 setup 中调用；返回值为 computed，宽度变化时使用处自动更新。
 * @returns {{ isMobile: import('vue').ComputedRef<boolean>,
 *             isTablet: import('vue').ComputedRef<boolean>,
 *             width: import('vue').Ref<number> }} 设备状态
 */
export function useDevice() {
  // 是否为手机端
  const isMobile = computed(() => width.value <= MOBILE_MAX_WIDTH)
  // 是否为平板 / 窄屏桌面（768 ~ 1024）
  const isTablet = computed(() => width.value > MOBILE_MAX_WIDTH && width.value <= 1024)
  return { isMobile, isTablet, width }
}
