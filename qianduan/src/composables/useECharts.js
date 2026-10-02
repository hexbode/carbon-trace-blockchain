/**
 * @file ECharts 组合式函数
 * @description 封装图表实例的创建、配置渲染、自适应缩放与销毁逻辑，
 *              使各页面只需传入配置生成函数即可绑定一个响应式图表容器。
 * @module 图表组合式函数
 *
 * 用法：
 *   const option = () => ({ ... })          // 返回 ECharts 配置的函数，内部可读取响应式数据
 *   const { chartRef } = useECharts(option) // chartRef 绑定到模板中的容器 div
 */

// 引入 Vue 组合式 API：ref 模板引用、生命周期钩子、nextTick、watchEffect 依赖追踪
import { ref, onMounted, onBeforeUnmount, nextTick, watchEffect } from 'vue'
// 引入 ECharts 全量包，用于初始化图表实例
import * as echarts from 'echarts'

/**
 * 初始化并管理一个 ECharts 图表实例
 * @param {Function} optionFactory 返回 ECharts 配置对象的函数（内部读取响应式数据以建立依赖）
 * @returns {{ chartRef: import('vue').Ref }} 图表容器引用，需绑定到模板中的 div
 */
export function useECharts(optionFactory) {
  // 图表容器 DOM 引用
  const chartRef = ref(null)
  // ECharts 实例（非响应式，避免被 Vue 代理影响性能）
  let chart = null

  // 监听配置依赖：optionFactory 内部读取的响应式数据变化时自动重绘
  watchEffect(() => {
    const option = optionFactory()
    if (chart) chart.setOption(option, true)
  })

  // 窗口尺寸变化时自适应
  const handleResize = () => {
    if (chart) chart.resize()
  }

  // 挂载后初始化图表并首次渲染
  onMounted(async () => {
    await nextTick()
    if (!chartRef.value) return
    chart = echarts.init(chartRef.value)
    chart.setOption(optionFactory(), true)
    window.addEventListener('resize', handleResize)
  })

  // 卸载前移除监听并销毁实例，防止内存泄漏
  onBeforeUnmount(() => {
    window.removeEventListener('resize', handleResize)
    if (chart) {
      chart.dispose()
      chart = null
    }
  })

  return { chartRef }
}
