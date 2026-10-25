<!--
  EmissionTrend.vue - 排放趋势统计页（企业端）
  用途：多维度分析企业碳排放数据变化趋势与排放源结构，支持按时间范围与排放源筛选，
  提供趋势折线图、占比饼图与对比柱状图
  路由：/enterprise/trend（name: EmissionTrend，仅 enterprise 角色可访问）
  依赖：排放记录接口（前端按报告期与排放源聚合）；三张图表由 ECharts 渲染
-->
<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <h2>排放趋势统计</h2>
      <p>多维度分析企业碳排放数据变化趋势与排放源结构</p>
    </div>

    <!-- 筛选区：按时间范围（近6/12个月）与排放源过滤数据
         时间范围决定月份轴长度（本页月份轴固定从 2024-01 起连续 N 个月，与演示数据期一致），
         排放源按名称过滤，二者均在前端本地生效 -->
    <div class="panel mb-16">
      <div class="panel-body">
        <el-form :inline="true" :model="filters">
          <el-form-item label="时间范围">
            <el-select v-model="filters.range" style="width: 160px">
              <el-option label="近6个月" value="6" />
              <el-option label="近12个月" value="12" />
            </el-select>
          </el-form-item>
          <el-form-item label="排放源">
            <el-select v-model="filters.source" placeholder="全部排放源" clearable style="width: 180px">
              <el-option v-for="s in sourceList" :key="s" :label="s" :value="s" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <!-- 汇总指标卡片区：排放总量、月均排放量、峰值月份、排放源数量
         口径：总量与峰值按当前筛选后的记录聚合；月均 = 总量 ÷ 月份轴长度（无数据月份按 0 计入） -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="6" v-for="s in summary" :key="s.label">
        <div class="stat-card">
          <div class="label">{{ s.label }}</div>
          <div class="value">{{ s.value }}<span class="unit">{{ s.unit }}</span></div>
        </div>
      </el-col>
    </el-row>

    <!-- 图表区：左侧月度排放量趋势折线图（用 markPoint 标注峰值/谷值），右侧排放源结构占比环形饼图 -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="16">
        <div class="panel">
          <div class="panel-header"><span class="title">月度排放量趋势</span></div>
          <div class="panel-body"><div ref="lineChartRef" :style="{ height: isMobile ? '240px' : '340px' }"></div></div>
        </div>
      </el-col>
      <el-col :span="8">
        <div class="panel">
          <div class="panel-header"><span class="title">排放源结构占比</span></div>
          <div class="panel-body"><div ref="pieChartRef" :style="{ height: isMobile ? '240px' : '340px' }"></div></div>
        </div>
      </el-col>
    </el-row>

    <!-- 各排放源排放对比柱状图 -->
    <div class="panel">
      <div class="panel-header"><span class="title">各排放源排放对比</span></div>
      <div class="panel-body"><div ref="barChartRef" :style="{ height: isMobile ? '240px' : '320px' }"></div></div>
    </div>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// 引入 Vue 响应式 API
import { ref, reactive, computed, onMounted } from 'vue'
// 引入 ECharts 组合式函数
import { useECharts } from '@/composables/useECharts'
// 引入用户 store，获取当前登录企业
import { useUserStore } from '@/store'
// 引入排放记录接口
import { getEmissionRecords } from '@/api/emission'
// 引入设备检测：手机端压缩图表高度
import { useDevice } from '@/composables/useDevice'

// 用户 store 实例
const userStore = useUserStore()
// 设备类型（手机端图表高度更紧凑）
const { isMobile } = useDevice()

// 筛选条件：range 时间范围（'6' / '12'，决定月份轴长度）、source 排放源名称（空表示全部）
const filters = reactive({ range: '12', source: '' })

// 当前企业的全部排放记录
const myRecords = ref([])

// 按筛选条件过滤后的记录：仅按排放源名称过滤；
// 时间范围不参与记录过滤，而是通过下方月份轴长度间接截断统计区间
const records = computed(() => myRecords.value.filter(r =>
  !filters.source || r.sourceName === filters.source
))

// 排放源下拉选项：取当前企业记录中出现的排放源名称并去重
const sourceList = computed(() => [...new Set(myRecords.value.map(r => r.sourceName))])

// 展示的月份列表：2024 年 1 月起连续 6 或 12 个月（演示数据固定在 2024 年度）
const months = computed(() => {
  const n = Number(filters.range)
  return Array.from({ length: n }, (_, i) => `2024-${String(i + 1).padStart(2, '0')}`)
})
// 月份中文标签
const monthLabels = computed(() => months.value.map(m => `${Number(m.slice(5))}月`))

// 各月排放量：以月份轴为准，按报告期聚合该月全部记录的排放量，无记录月份结果为 0
const monthlyData = computed(() => months.value.map(m =>
  records.value.filter(r => r.period === m).reduce((s, r) => s + Number(r.amount || 0), 0)
))

// 排放总量
const totalEmission = computed(() => records.value.reduce((s, r) => s + Number(r.amount || 0), 0))
// 月均排放量
const avgEmission = computed(() => monthlyData.value.length
  ? Math.round(totalEmission.value / monthlyData.value.length)
  : 0)
// 峰值月份（排放量最大的月份；存在并列最大值时取月份轴上最先出现的月份）
const peakMonth = computed(() => {
  const max = Math.max(...monthlyData.value)
  const idx = monthlyData.value.indexOf(max)
  return idx > -1 ? `${Number(months.value[idx].slice(5))}月` : '-'
})

// 汇总指标卡片数据
const summary = computed(() => [
  { label: '排放总量', value: Math.round(totalEmission.value), unit: 'tCO₂' },
  { label: '月均排放量', value: avgEmission.value, unit: 'tCO₂' },
  { label: '峰值月份', value: peakMonth.value, unit: '' },
  { label: '排放源数量', value: sourceList.value.length, unit: '个' }
])

// 按排放源聚合排放量（同时用于饼图与对比柱状图，聚合结果四舍五入取整）
const sourceStats = computed(() => {
  const map = {}
  records.value.forEach(r => { map[r.sourceName] = (map[r.sourceName] || 0) + Number(r.amount || 0) })
  return Object.entries(map).map(([name, value]) => ({ name, value: Math.round(value) }))
})

// 月度排放量趋势折线图配置：平滑曲线 + 区域填充 + 峰谷标记
const lineOption = () => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 50, right: 20, top: 30, bottom: 30 },
  xAxis: { type: 'category', data: monthLabels.value, axisLine: { lineStyle: { color: '#e2e8f0' } } },
  yAxis: { type: 'value', name: 'tCO₂', splitLine: { lineStyle: { color: '#f1f5f9' } } },
  series: [{
    type: 'line',
    data: monthlyData.value,
    smooth: true,
    areaStyle: { color: 'rgba(16,185,129,.12)' },
    lineStyle: { color: '#10b981', width: 3 },
    itemStyle: { color: '#10b981' },
    markPoint: {
      symbolSize: 46,
      data: [
        { type: 'max', name: '峰值' },
        { type: 'min', name: '谷值' }
      ]
    }
  }]
})
const { chartRef: lineChartRef } = useECharts(lineOption)

// 排放源结构占比饼图配置（环形图）
const pieOption = () => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c} tCO₂ ({d}%)' },
  legend: { bottom: 0, textStyle: { fontSize: 11 } },
  color: ['#10b981', '#0ea5e9', '#f59e0b', '#8b5cf6', '#ef4444'],
  series: [{
    type: 'pie',
    radius: ['45%', '70%'],
    center: ['50%', '45%'],
    itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
    label: { show: false },
    data: sourceStats.value
  }]
})
const { chartRef: pieChartRef } = useECharts(pieOption)

// 各排放源排放对比柱状图配置
const barOption = () => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  grid: { left: 50, right: 20, top: 20, bottom: 30 },
  xAxis: { type: 'category', data: sourceStats.value.map(s => s.name), axisLine: { lineStyle: { color: '#e2e8f0' } } },
  yAxis: { type: 'value', name: 'tCO₂', splitLine: { lineStyle: { color: '#f1f5f9' } } },
  series: [{
    type: 'bar',
    data: sourceStats.value.map(s => s.value),
    itemStyle: { color: '#0ea5e9', borderRadius: [6, 6, 0, 0] },
    barWidth: 40,
    label: { show: true, position: 'top' }
  }]
})
const { chartRef: barChartRef } = useECharts(barOption)

// 拉取当前企业排放记录
async function load() {
  try {
    myRecords.value = await getEmissionRecords({ enterpriseId: userStore.user?.enterpriseId })
  } catch (e) {
    myRecords.value = []
  }
}
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>
