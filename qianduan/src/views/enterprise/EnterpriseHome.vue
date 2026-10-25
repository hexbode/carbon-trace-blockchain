<!--
  EnterpriseHome.vue - 企业端首页
  用途：企业登录后首页概览，展示欢迎横幅、关键指标卡片、月度排放趋势图、排放源占比图及最近报告提交列表
  路由：/enterprise/home（name: EnterpriseHome，仅 enterprise 角色可访问）
  依赖：企业详情、企业概览/趋势/排放源占比统计接口与报告记录接口；图表由 ECharts 渲染
-->
<template>
  <div class="page-container">
    <!-- 欢迎横幅：展示企业名称与概览说明（手机端隐藏大图标并压缩留白） -->
    <div class="welcome-banner" :class="{ 'is-mobile': isMobile }">
      <div class="banner-text">
        <h2 >欢迎回来，{{ enterprise.name }}</h2>
        <p>以下是贵企业碳排放数据与认证状态概览</p>
      </div>
      <div class="banner-icon" v-if="!isMobile">
        <el-icon :size="64" color="#fff"><OfficeBuilding /></el-icon>
      </div>
    </div>

    <!-- 关键指标卡片区：累计碳排放、已认证/待审核报告数、认证证书数及环比趋势
         数值来自企业概览接口；趋势为演示用静态环比值（正数表示较上期下降、负数表示上升） -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="6" v-for="stat in stats" :key="stat.label">
        <div class="stat-card clickable" @click="$router.push(stat.link)">
          <div class="label"><el-icon><component :is="stat.icon" /></el-icon> {{ stat.label }}</div>
          <div class="value">{{ stat.value }}<span class="unit">{{ stat.unit }}</span></div>
          <div class="trend" :class="stat.trend > 0 ? 'text-success' : 'text-danger'">
            {{ stat.trend > 0 ? '↓' : '↑' }} {{ Math.abs(stat.trend) }}% 较上期
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 图表区：左侧月度排放趋势折线图，右侧排放源占比饼图
         趋势图横轴取接口返回的月份轴（YYYY-MM），饼图数值按排放源聚合后取整 -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="16">
        <div class="panel">
          <div class="panel-header">
            <span class="title">月度排放趋势（tCO₂）</span>
            <el-button text type="primary" @click="$router.push('/enterprise/trend')">详细统计</el-button>
          </div>
          <div class="panel-body">
            <div ref="trendChartRef" :style="{ height: isMobile ? '240px' : '300px' }"></div>
          </div>
        </div>
      </el-col>
      <el-col :span="8">
        <div class="panel">
          <div class="panel-header"><span class="title">排放源占比</span></div>
          <div class="panel-body">
            <div ref="pieChartRef" :style="{ height: isMobile ? '240px' : '300px' }"></div>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 最近报告提交列表：展示最近 6 条报告记录 -->
    <div class="panel">
      <div class="panel-header">
        <span class="title">最近报告提交</span>
        <el-button text type="primary" @click="$router.push('/enterprise/records')">查看全部</el-button>
      </div>
      <div class="panel-body" style="padding: 0">
        <!-- 最近报告列表：手机端用卡片列表，桌面端用表格 -->
        <div v-if="isMobile" class="m-list" style="padding: 12px">
          <el-empty v-if="!recentRecords.length" description="暂无数据" :image-size="70" />
          <div v-for="row in recentRecords" :key="row.id" class="m-card">
            <div class="m-card-head">
              <span class="m-card-title">{{ row.period }}</span>
              <el-tag :type="tagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
            </div>
            <div class="m-row"><span class="m-key">排放源</span><span class="m-val">{{ row.sourceName }}</span></div>
            <div class="m-row"><span class="m-key">活动数据</span><span class="m-val">{{ row.activityData ?? '-' }} {{ row.activityUnit || '' }}</span></div>
            <div class="m-row">
              <span class="m-key">排放因子</span>
              <span class="m-val m-val-inline">
                <span>{{ row.emissionFactor ?? '-' }}</span>
                <span class="m-unit">{{ row.unit || '-' }}/单位</span>
              </span>
            </div>
            <div class="m-row"><span class="m-key">排放量</span><span class="m-val text-primary" style="font-weight:600">{{ row.amount }} tCO₂</span></div>
            <div class="m-row"><span class="m-key">提交时间</span><span class="m-val">{{ row.submittedAt }}</span></div>
          </div>
        </div>

        <el-table v-else :data="recentRecords" size="default">
          <el-table-column prop="period" label="报告日期" min-width="150" align="center" />
          <el-table-column prop="sourceName" label="排放源" min-width="80" />
          <el-table-column label="排放量" min-width="80" >
            <template #default="{ row }"><span class="text-primary" style="font-weight:600">{{ row.amount }}</span> tCO₂</template>
          </el-table-column>
          <el-table-column prop="submittedAt" label="提交时间" min-width="70" align="center" />
          <el-table-column label="状态" min-width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="tagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// 引入 Vue 响应式 API
import { computed, ref, onMounted } from 'vue'
// 引入 ECharts 组合式函数，用于初始化与更新图表
import { useECharts } from '@/composables/useECharts'
// 引入用户 store，获取当前登录企业
import { useUserStore } from '@/store'
// 引入企业详情与统计接口
import { getEnterprise } from '@/api/enterprise'
import { getEnterpriseOverview, getEnterpriseTrend, getEnterpriseSourceShare } from '@/api/stats'
// 引入报告记录接口
import { getReportRecords } from '@/api/report'
// 引入设备检测：手机端渲染卡片列表并压缩横幅与图表高度
import { useDevice } from '@/composables/useDevice'

// 用户 store 实例
const userStore = useUserStore()
// 当前企业信息
const enterprise = ref({ name: '企业' })
// 当前企业的报告记录
const myRecords = ref([])
// 企业排放概览统计（统计口径：totalEmission 为企业全部排放记录排放量合计；
// certifiedCount / pendingCount 为排放记录的状态计数；certCount 仅统计有效（valid）认证，
// 认证记录在审核通过时生成，撤销后会减少，因此 certCount 与 certifiedCount 可能不相等）
const overview = ref({ totalEmission: 0, certifiedCount: 0, pendingCount: 0, certCount: 0 })
// 月度趋势：月份标签（YYYY-MM）与对应月度排放合计
// 月份轴由后端按该企业真实报告期生成（无记录时回退最近 12 个月），data 与 months 等长且缺月补 0
const months = ref([])
const trendData = ref([])
// 排放源占比数据
const pieData = ref([])
// 设备类型（手机端切换为卡片列表）
const { isMobile } = useDevice()

// 汇总指标：累计碳排放总量、已认证/待审核报告数
const totalEmission = computed(() => Number(overview.value.totalEmission || 0))
const certifiedCount = computed(() => overview.value.certifiedCount || 0)
const pendingCount = computed(() => overview.value.pendingCount || 0)

// 首页指标卡片数据：含标签、数值、单位、图标、环比趋势与点击跳转地址
// trend 为演示用静态值（本项目未提供环比接口）；正数渲染为绿色 ↓，负数渲染为红色 ↑
const stats = computed(() => [
  { label: '累计碳排放', value: totalEmission.value.toFixed(1), unit: 'tCO₂', icon: 'DataAnalysis', trend: -3.2, link: '/enterprise/trend' },
  { label: '已认证报告', value: certifiedCount.value, unit: '份', icon: 'Stamp', trend: 8.5, link: '/enterprise/records?status=certified' },
  { label: '待审核报告', value: pendingCount.value, unit: '份', icon: 'Clock', trend: -12.4, link: '/enterprise/records?status=pending' },
  { label: '认证证书', value: overview.value.certCount || 0, unit: '张', icon: 'Certificate', trend: 5.1, link: '/enterprise/certificate' }
])

// 最近 6 条报告记录（列表顺序沿用接口返回顺序，首页仅做数量截取）
const recentRecords = computed(() => myRecords.value.slice(0, 6))

// 月度排放趋势折线图配置（平滑曲线 + 区域填充）
const trendOption = () => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 40, right: 20, top: 20, bottom: 30 },
  xAxis: { type: 'category', data: months.value.map(m => `${Number(m.slice(5))}月`), axisLine: { lineStyle: { color: '#e2e8f0' } } },
  yAxis: { type: 'value', splitLine: { lineStyle: { color: '#f1f5f9' } } },
  series: [{
    type: 'line', data: trendData.value, smooth: true,
    areaStyle: { color: 'rgba(16,185,129,.12)' },
    lineStyle: { color: '#10b981', width: 3 },
    itemStyle: { color: '#10b981' }
  }]
})
// 初始化趋势图，trendChartRef 绑定到模板容器
const { chartRef: trendChartRef } = useECharts(trendOption)

// 排放源占比环形饼图配置
const pieOption = () => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c} tCO₂ ({d}%)' },
  legend: { bottom: 0, textStyle: { fontSize: 11 } },
  color: ['#10b981', '#0ea5e9', '#f59e0b', '#8b5cf6', '#ef4444'],
  series: [{
    type: 'pie', radius: ['45%', '70%'], center: ['50%', '45%'],
    itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
    label: { show: false },
    data: pieData.value
  }]
})
// 初始化占比图，pieChartRef 绑定到模板容器
const { chartRef: pieChartRef } = useECharts(pieOption)

// 拉取首页数据：五个接口并发请求，任一失败会影响整组结果，故各字段均做空值兜底
async function load() {
  const enterpriseId = userStore.user?.enterpriseId
  const [detail, ov, trend, share, reports] = await Promise.all([
    enterpriseId ? getEnterprise(enterpriseId) : Promise.resolve(null),
    getEnterpriseOverview({ enterpriseId }),
    getEnterpriseTrend({ enterpriseId }),
    getEnterpriseSourceShare({ enterpriseId }),
    getReportRecords({ enterpriseId })
  ])
  if (detail) enterprise.value = detail
  overview.value = ov || {}
  months.value = trend?.months || []
  trendData.value = trend?.data || []
  pieData.value = (share || []).map(s => ({ name: s.name, value: Math.round(s.value) }))
  myRecords.value = reports || []
}
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)

// 状态码映射为中文标签
function statusLabel(s) { return { certified: '已认证', pending: '审核中', rejected: '已驳回' }[s] }
// 状态码映射为 el-tag 类型（控制颜色）
function tagType(s) { return { certified: 'success', pending: 'warning', rejected: 'danger' }[s] }
</script>

<style scoped>
/* ===== 桌面端（>767px）基础样式 ===== */
/* 欢迎横幅：渐变背景 + 装饰圆形 */
.welcome-banner {
  background: linear-gradient(135deg, #10b981, #0d9488);
  border-radius: 14px;
  padding: 28px 32px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #fff;
  margin-bottom: 20px;
  position: relative;
  overflow: hidden;
}
/* 横幅右上角装饰圆形 */
.welcome-banner::after {
  content: '';
  position: absolute;
  right: -40px;
  top: -40px;
  width: 200px;
  height: 200px;
  border-radius: 50%;
  background: rgba(255,255,255,.1);
}
/* 横幅文字与图标样式 */
.banner-text h2 { margin: 0 0 6px; font-size: 24px; }
.banner-text p { margin: 0; opacity: .9; font-size: 14px; }
.banner-icon { position: relative; z-index: 1; opacity: .85; }
/* ===== 移动端（≤767px）适配：由 isMobile 添加 is-mobile 类，其余通用适配见 mobile.css ===== */
/* 手机端横幅：压缩内边距并降低标题字号，避免占用过多首屏高度 */
.welcome-banner.is-mobile {
  padding: 16px 18px;
}
.welcome-banner.is-mobile .banner-text h2 { font-size: 18px; }
.welcome-banner.is-mobile .banner-text p { font-size: 12px; }
/* 可跳转的指标卡片：使用手型光标提示可点击 */
.stat-card.clickable { cursor: pointer; }
</style>
