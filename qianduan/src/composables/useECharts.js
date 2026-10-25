/**
 * @file ECharts 组合式函数
 * @description 封装图表实例的创建、配置渲染、自适应缩放与销毁逻辑，
 *              使各页面只需传入配置生成函数即可绑定一个响应式图表容器。
 * @module 图表组合式函数
 *
 * 依赖：vue（ref / onMounted / onBeforeUnmount / nextTick / watchEffect）、echarts 全量包。
 * 导出：useECharts，返回 { chartRef }。
 *
 * 用法：
 *   const option = () => ({ ... })          // 返回 ECharts 配置的函数，内部可读取响应式数据
 *   const { chartRef } = useECharts(option) // chartRef 绑定到模板中的容器 div
 *
 * 实例生命周期：
 *   1. 创建：onMounted + nextTick 后容器尺寸已确定，调用 echarts.init 并首次 setOption；
 *   2. 更新：watchEffect 追踪 optionFactory 内读取的响应式数据，变化时以 notMerge 方式重绘；
 *      挂载前 chart 为 null，此时 watchEffect 只建立依赖、不触发渲染；
 *   3. 自适应：监听 window resize 调用 chart.resize()，适配侧边栏折叠与窗口缩放；
 *   4. 销毁：onBeforeUnmount 移除 resize 监听并 dispose 实例、置空引用，防止内存泄漏。
 */

// 引入 Vue 组合式 API：ref 模板引用、生命周期钩子、nextTick、watchEffect 依赖追踪
import { ref, onMounted, onBeforeUnmount, nextTick, watchEffect } from 'vue'
// 引入 ECharts 全量包，用于初始化图表实例
import * as echarts from 'echarts'

/**
 * 初始化并管理一个 ECharts 图表实例
 *
 * 必须在组件 setup 中调用，且模板容器需有确定的宽高，否则初始化后图表不可见。
 * @param {Function} optionFactory 返回 ECharts 配置对象的函数（内部读取响应式数据以建立依赖）
 * @returns {{ chartRef: import('vue').Ref }} 图表容器引用，需绑定到模板中的 div
 */
export function useECharts(optionFactory) {
  // 图表容器 DOM 引用
  const chartRef = ref(null)
  // ECharts 实例（非响应式，避免被 Vue 代理影响性能）
  let chart = null

  // 监听配置依赖：optionFactory 内部读取的响应式数据变化时自动重绘
  // 第二个参数 true 表示不与旧配置合并，数据切换（如行业/企业）时不会残留上一次的系列
  watchEffect(() => {
    const option = optionFactory()
    if (chart) chart.setOption(option, true)
  })

  // 窗口尺寸变化时自适应
  const handleResize = () => {
    if (chart) chart.resize()
  }

  // 挂载后初始化图表并首次渲染
  // 先等待 nextTick，确保容器已经完成布局、拿得到真实宽高
  onMounted(async () => {
    await nextTick()
    if (!chartRef.value) return
    chart = echarts.init(chartRef.value)
    chart.setOption(optionFactory(), true)
    // 监听在实例创建成功后注册，避免图表未初始化时产生无效 resize
    window.addEventListener('resize', handleResize)
  })

  // 卸载前移除监听并销毁实例，防止内存泄漏
  // 路由切换频繁的页面若缺少 dispose，会持续持有 canvas 与 resize 监听
  onBeforeUnmount(() => {
    window.removeEventListener('resize', handleResize)
    if (chart) {
      chart.dispose()
      chart = null
    }
  })

  return { chartRef }
}
