<!--
  文件：AuditorHome.vue
  用途：审核端 - 工作台首页
  功能：
    1. 顶部欢迎横幅：展示当前登录审核员姓名与所属机构
    2. 四个统计卡片：待审核报告数、今日已审核数、累计审核数、平均审核时长
    3. 左侧待审核队列表格：快速预览最近 6 条待审报告
    4. 右侧 ECharts 柱状图：展示近 7 日审核量趋势
-->
<template>
  <div class="page-container">
    <!-- 欢迎横幅：渐变背景，展示审核员姓名与所属机构名称 -->
    <div class="welcome-banner">
      <div class="banner-text">
        <h2>审核工作台</h2>
        <p>欢迎，{{ userStore.user?.name }} · {{ orgName }}</p>
      </div>
      <!-- 审核印章图标，象征审核职责 -->
      <div class="banner-icon"><el-icon :size="64" color="#fff"><Stamp /></el-icon></div>
    </div>

    <!-- 统计卡片区：4 个等宽卡片，展示审核工作核心指标（前三个可点击跳转对应页面） -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="6" v-for="s in stats" :key="s.label">
        <div
          class="stat-card"
          :class="[s.cls, { clickable: s.link }]"
          @click="s.link && $router.push(s.link)"
        >
          <div class="label">{{ s.label }}</div>
          <div class="value">{{ s.value }}<span v-if="s.unit" class="unit">{{ s.unit }}</span></div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <!-- 左侧：待审核队列面板 -->
      <el-col :span="14">
        <div class="panel">
          <div class="panel-header flex-between">
            <span class="title">待审核队列</span>
            <!-- 跳转到待审核报告完整列表页 -->
            <el-button text type="primary" @click="$router.push('/auditor/pending')">全部待审核</el-button>
          </div>
          <!-- 待审核列表：仅展示前 6 条 -->
          <div class="panel-body" style="padding: 0">
            <el-table :data="pendingList" size="default">
              <el-table-column prop="id" label="报告编号" min-width="120" align="center" />
              <el-table-column prop="enterpriseName" label="企业" min-width="160" show-overflow-tooltip align="center" />
              <el-table-column prop="period" label="报告日期" min-width="120" align="center" />
              <el-table-column label="排放量" min-width="90">
                <template #default="{ row }"><span class="text-primary" style="font-weight:600">{{ row.amount }}tCO₂</span></template>
              </el-table-column>
              <el-table-column prop="submittedAt" label="提交时间" min-width="160" align="center" />
            </el-table>
          </div>
        </div>
      </el-col>
      <!-- 右侧：近 7 日审核量柱状图面板 -->
      <el-col :span="10">
        <div class="panel">
          <div class="panel-header"><span class="title">近7日审核量</span></div>
          <!-- ECharts 图表容器，高度 280px -->
          <div class="panel-body"><div ref="chartRef" style="height: 280px"></div></div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// 引入 Vue 组合式 API：computed 计算属性、ref 模板引用
import { computed, ref, onMounted } from 'vue'
// 引入 ECharts 组合式函数封装，用于初始化图表并自动响应数据变化
import { useECharts } from '@/composables/useECharts'
// 引入 Pinia 用户 Store，获取当前登录审核员信息
import { useUserStore } from '@/store'
// 引入统计与审核机构接口
import { getAuditorOverview, getAuditorRecent7Days, getAuditorPendingQueue } from '@/api/stats'
import { getAuditOrganizations } from '@/api/auditOrg'

// 用户 Store 实例：包含登录用户信息（姓名、机构 ID 等）
const userStore = useUserStore()
// 审核机构列表（用于展示当前审核员所属机构名称）
const orgs = ref([])
// 当前用户所属机构名称
const orgName = computed(() => orgs.value.find(o => o.id === userStore.user?.orgId)?.name || '')

// 审核概览统计
const overview = ref({ pending: 0, todayDone: 0, totalDone: 0 })
// 待审核队列（取前 6 条用于首页快速预览）
const pendingList = ref([])
// 近 7 日审核量：横轴星期、纵轴数量
const days = ref([])
const data = ref([])

// 统计卡片数据：cls 控制数值颜色；link 存在时卡片可点击并跳转到对应页面
const stats = computed(() => [
  { label: '待审核报告', value: overview.value.pending || 0, cls: 'warning', link: '/auditor/pending' },
  { label: '今日已审核', value: overview.value.todayDone || 0, cls: 'success', link: '/auditor/today' },
  { label: '累计审核', value: overview.value.totalDone || 0, cls: '', link: '/auditor/history' },
  { label: '平均审核时长', value: '2.4', unit: '小时', cls: '' }
])

// ECharts 配置：柱状图，绿色柱体、圆角顶部
const option = () => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 40, right: 20, top: 20, bottom: 30 },
  xAxis: { type: 'category', data: days.value, axisLine: { lineStyle: { color: '#e2e8f0' } } },
  yAxis: { type: 'value', splitLine: { lineStyle: { color: '#f1f5f9' } } },
  series: [{ type: 'bar', data: data.value, itemStyle: { color: '#10b981', borderRadius: [6,6,0,0] }, barWidth: 30 }]
})
// 初始化图表：useECharts 返回 chartRef 绑定到模板容器
const { chartRef } = useECharts(option)

// 拉取工作台数据
async function load() {
  const [ov, list, recent, orgList] = await Promise.all([
    getAuditorOverview(),
    getAuditorPendingQueue({ limit: 6 }),
    getAuditorRecent7Days(),
    getAuditOrganizations()
  ])
  overview.value = ov || {}
  pendingList.value = list || []
  days.value = recent?.days || []
  data.value = recent?.data || []
  orgs.value = orgList || []
}
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>

<style scoped>
/* 欢迎横幅：蓝色渐变背景、圆角、左右两端对齐 */
.welcome-banner {
  background: linear-gradient(135deg, #0ea5e9, #0284c7);
  border-radius: 14px; padding: 24px 32px;
  display: flex; align-items: center; justify-content: space-between;
  color: #fff; margin-bottom: 20px; position: relative; overflow: hidden;
}
/* 横幅右上角装饰圆形（半透明白色） */
.welcome-banner::after { content:''; position:absolute; right:-40px; top:-40px; width:200px; height:200px; border-radius:50%; background:rgba(255,255,255,.1); }
/* 横幅文字：标题与副文案样式 */
.banner-text h2 { margin: 0 0 6px; font-size: 22px; }
.banner-text p { margin: 0; opacity: .9; font-size: 14px; }
/* 印章图标定位与透明度 */
.banner-icon { position: relative; z-index: 1; opacity: .85; }
/* 统计卡片数值颜色变体：warning 黄色（待审核）、success 绿色（今日已审核） */
.stat-card.warning .value { color: #f59e0b; }
.stat-card.success .value { color: #10b981; }
/* 可点击跳转的统计卡片：手型光标提示可交互 */
.stat-card.clickable { cursor: pointer; }
</style>
