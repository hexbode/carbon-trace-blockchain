/**
 * @file useDevice.js
 * @description 设备类型检测组合式函数
 * @module 设备适配
 *
 * 说明：
 *   以视口宽度作为判断依据（< 768px 视为手机），返回全局共享的响应式 isMobile，
 *   窗口尺寸变化时自动更新。布局组件据此渲染移动端页面结构（抽屉菜单 + 底部导航），
 *   桌面端保持原有侧边栏布局。
 */
import { computed, ref } from 'vue'

// 移动端断点：与 Element Plus 的 md 断点保持一致
const MOBILE_MAX_WIDTH = 767

// 全局共享的设备状态（单例，避免多组件重复监听 resize）
const width = ref(typeof window !== 'undefined' ? window.innerWidth : 1280)

if (typeof window !== 'undefined') {
  window.addEventListener('resize', () => {
    width.value = window.innerWidth
  })
}

export function useDevice() {
  // 是否为手机端
  const isMobile = computed(() => width.value <= MOBILE_MAX_WIDTH)
  // 是否为平板 / 窄屏桌面（768 ~ 1024）
  const isTablet = computed(() => width.value > MOBILE_MAX_WIDTH && width.value <= 1024)
  return { isMobile, isTablet, width }
}
