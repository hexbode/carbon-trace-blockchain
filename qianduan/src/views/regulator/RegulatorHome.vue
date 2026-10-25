<!--
  文件：RegulatorHome.vue 监管端首页总览页面
  所属端：监管端
  功能描述：集中展示全行业碳排放监管的核心指标，
           包括监管企业数量、累计碳排放量、已认证记录数、待处理告警数；
           同时提供行业碳排放对比柱状图、认证状态分布饼图，以及最新异常告警列表，
           帮助监管人员快速掌握整体监管态势。
  路由：/regulator/home（name: RegulatorHome，roles: regulator / admin）
  依赖接口：@/api/stats 的 getRegulatorOverview（顶部四项指标）、
           getRegulatorIndustryEmission（行业碳排放柱状图）、getRegulatorCertStatus（认证状态饼图）；
           @/api/alert 的 getAlerts（固定传 status=pending，首页只取最新 5 条）
  依赖模块：@/composables/useECharts（图表实例与自动重绘）、useDevice（移动端适配）、
           useAutoRefresh（布局层定时信号触发重新拉取）
  统计口径：
    监管企业数 = 已入驻企业总数；
    累计碳排放 = 各企业排放记录按 tCO₂ 汇总；
    已认证记录 = 认证通过的记录条数；
    待处理告警 = alerts 中 status = pending 的条数。
-->
<template>
  <div class="page-container">
    <!-- 顶部欢迎横幅：展示监管端标题与简介 -->
    <div class="welcome-banner">
      <div class="banner-text">
        <h2>监管总览</h2>
        <p>全行业碳排放数据监管、认证状态与异常监控概览</p>
      </div>
      <div class="banner-icon"><el-icon :size="64" color="#fff"><View /></el-icon></div>
    </div>

    <!-- 统计卡片区：监管企业 / 累计碳排放 / 已认证记录 / 待处理告警（点击跳转对应页面） -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="6" v-for="s in stats" :key="s.label">
        <div class="stat-card clickable" @click="$router.push(s.link)">
          <div class="label"><el-icon><component :is="s.icon" /></el-icon> {{ s.label }}</div>
          <div class="value">{{ s.value }}<span class="unit">{{ s.unit }}</span></div>
        </div>
      </el-col>
    </el-row>

    <!-- 图表区域：行业碳排放对比柱状图 + 认证状态分布饼图 -->
    <!-- 布局：左侧柱状图占 14 栅格，右侧饼图占 10 栅格；移动端仅把图表高度压到 240px，不改变分栏 -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="14">
        <div class="panel">
          <div class="panel-header"><span class="title">行业碳排放对比</span></div>
          <div class="panel-body"><div ref="industryChartRef" :style="{ height: isMobile ? '240px' : '300px' }"></div></div>
        </div>
      </el-col>
      <el-col :span="10">
        <div class="panel">
          <div class="panel-header"><span class="title">认证状态分布</span></div>
          <div class="panel-body"><div ref="statusChartRef" :style="{ height: isMobile ? '240px' : '300px' }"></div></div>
        </div>
      </el-col>
    </el-row>

    <!-- 异常告警面板：展示待处理告警数量徽标及最新告警列表 -->
    <div class="panel">
      <div class="panel-header flex-between">
        <span class="title">异常告警</span>
        <el-badge :value="pendingAlerts" class="badge" type="danger" />
        <el-button text type="primary" @click="$router.push('/regulator/alerts')">查看全部</el-button>
      </div>
      <div class="panel-body" style="padding: 0">
        <!-- 告警列表：手机端用卡片展示，桌面端用表格；均仅展示最新 5 条待处理告警 -->
        <div v-if="isMobile" v-loading="loading" class="m-list" style="padding: 12px">
          <el-empty v-if="!topAlerts.length" description="暂无待处理告警" :image-size="70" />
          <div v-for="row in topAlerts" :key="row.id" class="m-card">
            <div class="m-card-head">
              <span class="m-card-title">{{ row.enterpriseName }}</span>
              <el-tag :type="row.level === 'high' ? 'danger' : row.level === 'medium' ? 'warning' : 'info'" size="small" effect="dark">{{ levelLabel(row.level) }}</el-tag>
            </div>
            <div class="m-row"><span class="m-key">告警内容</span><span class="m-val">{{ row.message }}</span></div>
            <div class="m-row"><span class="m-key">时间</span><span class="m-val">{{ row.createdAt }}</span></div>
          </div>
        </div>
        <!-- 桌面端表格（>767px）：字段为级别、企业名称、告警内容、触发时间，与移动端卡片信息一致 -->
        <el-table v-else :data="topAlerts" size="default">
          <el-table-column label="级别" min-width="80" align="center">
            <template #default="{ row }"><el-tag :type="row.level === 'high' ? 'danger' : row.level === 'medium' ? 'warning' : 'info'" size="small" effect="dark">{{ levelLabel(row.level) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="enterpriseName" label="企业名称" min-width="180" show-overflow-tooltip align="center" />
          <el-table-column prop="message" label="告警内容" min-width="280" show-overflow-tooltip align="center" />
          <el-table-column prop="createdAt" label="时间" min-width="170" show-overflow-tooltip align="center" />
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// 引入 Vue 计算属性与响应式引用
import { computed, ref, onMounted } from 'vue'
// 引入 ECharts 组合式函数，用于图表实例化与渲染
import { useECharts } from '@/composables/useECharts'
// 引入监管端统计与告警接口
import { getRegulatorOverview, getRegulatorIndustryEmission, getRegulatorCertStatus } from '@/api/stats'
import { getAlerts } from '@/api/alert'
// 引入设备检测：手机端将告警表格切换为卡片列表，并调整图表高度
import { useDevice } from '@/composables/useDevice'

// 设备类型（手机端渲染卡片列表、压缩图表高度）
const { isMobile } = useDevice()
// 告警列表加载态（手机端卡片列表显示 loading 遮罩）
const loading = ref(false)
// 监管概览统计（接口 /stats/regulator/overview 的返回，缺字段时由 stats 计算属性兜底为 0）
const overview = ref({ enterpriseCount: 0, totalEmission: 0, certCount: 0, pendingAlerts: 0 })
// 行业碳排放对比：行业名称与对应数值（两数组按索引一一对应，来自同一接口的 industry / amount）
const industries = ref([])
const industryValues = ref([])
// 认证状态统计：已认证 / 待审核 / 已驳回（来源 /stats/regulator/cert-status）
const statusMap = ref({ certified: 0, pending: 0, rejected: 0 })
// 最新待处理告警（接口按触发时间倒序返回，此处仅保留前 5 条）
const topAlerts = ref([])

// 待处理告警数量：用于告警面板徽标与统计卡片的“待处理告警”项
const pendingAlerts = computed(() => overview.value.pendingAlerts || 0)

// 顶部统计卡片数据：企业数 / 累计碳排放 / 已认证记录 / 待处理告警（link 为点击跳转地址）
// 累计碳排放取整展示（toFixed(0)），单位统一为 tCO₂
const stats = computed(() => [
  { label: '监管企业', value: overview.value.enterpriseCount || 0, unit: '家', icon: 'OfficeBuilding', link: '/regulator/enterprise' },
  { label: '累计碳排放', value: Number(overview.value.totalEmission || 0).toFixed(0), unit: 'tCO₂', icon: 'DataAnalysis', link: '/regulator/industry-stats' },
  { label: '已认证记录', value: overview.value.certCount || 0, unit: '条', icon: 'Stamp', link: '/regulator/revoke' },
  { label: '待处理告警', value: pendingAlerts.value, unit: '条', icon: 'Warning', link: '/regulator/alerts' }
])

// 行业碳排放对比柱状图配置：x 轴为行业，y 轴为 tCO₂ 排放量
// 数据源为 getRegulatorIndustryEmission；配置函数内部读取 industries / industryValues，
// 由 useECharts 的 watchEffect 追踪依赖，接口数据更新后自动重绘
const industryOption = () => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  grid: { left: 50, right: 20, top: 20, bottom: 30 },
  xAxis: { type: 'category', data: industries.value, axisLine: { lineStyle: { color: '#e2e8f0' } } },
  yAxis: { type: 'value', name: 'tCO₂', splitLine: { lineStyle: { color: '#f1f5f9' } } },
  series: [{
    type: 'bar', data: industryValues.value,
    itemStyle: { color: '#0ea5e9', borderRadius: [6,6,0,0] },
    barWidth: 40, label: { show: true, position: 'top' }
  }]
})
// 行业碳排放柱状图实例（通过 useECharts 绑定到 industryChartRef）
const { chartRef: industryChartRef } = useECharts(industryOption)

// 认证状态分布饼图配置（环形图）
// 数据源为 getRegulatorCertStatus，三段分别对应已认证 / 待审核 / 已驳回
const statusOption = () => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
  legend: { bottom: 0 },
  color: ['#10b981', '#f59e0b', '#ef4444'],
  series: [{
    type: 'pie', radius: ['45%', '70%'], center: ['50%', '45%'],
    itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
    label: { show: false },
    data: [
      { name: '已认证', value: statusMap.value.certified },
      { name: '待审核', value: statusMap.value.pending },
      { name: '已驳回', value: statusMap.value.rejected }
    ]
  }]
})
// 认证状态饼图实例（通过 useECharts 绑定到 statusChartRef）
const { chartRef: statusChartRef } = useECharts(statusOption)

// 告警级别中文映射：high→高危，medium→中危，low→低危
function levelLabel(l) { return { high: '高危', medium: '中危', low: '低危' }[l] }

// 拉取监管总览数据：四个接口并行请求，避免串行等待
async function load() {
  loading.value = true
  try {
    // 返回依次为：概览指标 / 行业排放 / 认证状态 / 待处理告警
    const [ov, industry, certStatus, alertList] = await Promise.all([
      getRegulatorOverview(),
      getRegulatorIndustryEmission(),
      getRegulatorCertStatus(),
      getAlerts({ status: 'pending' })
    ])
    overview.value = ov || {}
    // 行业接口返回 [{ industry, amount }]，拆成名称与数值两个数组供柱状图使用；数值四舍五入后再绘制
    industries.value = (industry || []).map(i => i.industry)
    industryValues.value = (industry || []).map(i => Math.round(i.amount))
    statusMap.value = certStatus || { certified: 0, pending: 0, rejected: 0 }
    // 首页仅展示最新 5 条，完整列表在告警页查看
    topAlerts.value = (alertList || []).slice(0, 5)
  } finally {
    loading.value = false
  }
}
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>

<style scoped>
/* ===== 桌面端（>767px）：基础样式，本页无 scoped 断点规则 ===== */
/* ===== 移动端（≤767px）：由 isMobile 切换结构（告警卡片列表、图表高度 240px），卡片外观来自全局 mobile.css ===== */
/* 顶部欢迎横幅：紫色渐变背景，左侧文字 + 右侧图标 */
.welcome-banner {
  background: linear-gradient(135deg, #8b5cf6, #6d28d9);
  border-radius: 14px; padding: 24px 32px;
  display: flex; align-items: center; justify-content: space-between;
  color: #fff; margin-bottom: 20px; position: relative; overflow: hidden;
}
/* 横幅右上角装饰圆形 */
.welcome-banner::after { content:''; position:absolute; right:-40px; top:-40px; width:200px; height:200px; border-radius:50%; background:rgba(255,255,255,.1); }
.banner-text h2 { margin: 0 0 6px; font-size: 22px; }
.banner-text p { margin: 0; opacity: .9; font-size: 14px; }
.banner-icon { position: relative; z-index: 1; opacity: .85; }
/* 告警徽标：在标题与按钮之间占据剩余空间 */
.badge { margin-right: auto; margin-left: 12px; }
/* 可点击跳转的统计卡片：手型光标提示可交互 */
.stat-card.clickable { cursor: pointer; }
</style>
