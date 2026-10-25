/**
 * @file 页面数据自动刷新
 * @description 提供一个全局刷新信号与注册函数。页面在 setup 中把自己的数据加载函数
 *              注册进来后，布局层定时自增刷新信号，页面即通过接口（Ajax）重新拉取数据。
 *              整个过程不重新挂载组件，因此筛选条件、搜索词、表单内容、当前标签页
 *              等页面状态都不会被重置。
 * @module 组合式函数
 *
 * 依赖：vue 的 ref / watch。
 * 导出：refreshTick（全局刷新信号）、useAutoRefresh（注册数据加载函数）。
 * 定时器位置：本文件不创建定时器；Layout.vue 挂载后以 15 秒间隔自增 refreshTick，
 *            并在 onBeforeUnmount 中 clearInterval，标签页处于后台时跳过递增。
 */

import { ref, watch } from 'vue'

// 全局刷新信号：自增即触发所有已注册页面重新拉取数据
export const refreshTick = ref(0)

/**
 * 注册当前页面的数据加载函数，使其随全局刷新信号重新拉取数据
 *
 * 使用约束：必须在 setup 同步阶段调用，watch 才能绑定到当前组件实例。
 * 清理机制：watch 默认随组件卸载自动停止（effect scope 销毁），无需手动解绑；
 *          页面切换后旧组件不再响应刷新信号，避免重复请求与状态污染。
 * @param {Function} load 数据加载函数，通常是页面内的 load / loadRecords
 * @returns {void} 无返回值，仅建立监听关系
 */
export function useAutoRefresh(load) {
  // watch 建立在本组件的作用域内，组件卸载后会自动停止监听
  // 回调只执行 load，不重置页面本地状态（筛选条件、页码、表单内容均保留）
  watch(refreshTick, () => { load() })
}
