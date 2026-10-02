/**
 * @file 页面数据自动刷新
 * @description 提供一个全局刷新信号与注册函数。页面在 setup 中把自己的数据加载函数
 *              注册进来后，布局层定时自增刷新信号，页面即通过接口（Ajax）重新拉取数据。
 *              整个过程不重新挂载组件，因此筛选条件、搜索词、表单内容、当前标签页
 *              等页面状态都不会被重置。
 * @module 组合式函数
 */

import { ref, watch } from 'vue'

// 全局刷新信号：自增即触发所有已注册页面重新拉取数据
export const refreshTick = ref(0)

/**
 * 注册当前页面的数据加载函数，使其随全局刷新信号重新拉取数据
 * @param {Function} load 数据加载函数，通常是页面内的 load / loadRecords
 */
export function useAutoRefresh(load) {
  // watch 建立在本组件的作用域内，组件卸载后会自动停止监听
  watch(refreshTick, () => { load() })
}
