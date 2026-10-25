<!--
  文件：IndustryStats.vue 行业碳排放统计页面
  所属端：监管端
  功能描述：从行业维度和企业维度对碳排放数据进行分析，
           展示行业总数、监管企业数、累计排放、认证总数等汇总指标；
           通过各行业月度排放趋势折线图、行业排放占比饼图、企业排放排行表格，
           辅助监管人员进行碳排放趋势研判与监管决策。
  路由：/regulator/industry-stats（name: IndustryStats，roles: regulator / admin）
  依赖接口：@/api/stats 的 getRegulatorIndustrySummary（行业汇总，同时供饼图使用）、
           getRegulatorIndustryRanking（行业排放排名）、getRegulatorEnterpriseRanking（企业排放排名）、
           getRegulatorEnterpriseTrend（各行业月度趋势）；@/api/certification 的 getCertifications（认证记录）
  统计口径：行业汇总的 emission 为该行业企业累计排放之和，percent 为占全部行业累计排放的比例；
           顶部“累计排放/监管企业”由行业汇总逐项相加得到，“认证总数”取认证记录条数。
-->
<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <h2>行业碳排放统计</h2>
      <p>分行业、分企业的碳排放总量与趋势分析，辅助监管决策</p>
    </div>

    <!-- 顶部统计卡片：行业总数 / 监管企业 / 累计排放 / 认证总数（点击切换下方明细视图） -->
    <!-- 默认激活“监管企业”视图，即企业排放排行（activeView 初值为 enterprise） -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="6" v-for="s in stats" :key="s.label">
        <div class="stat-card clickable" :class="{ active: activeView === s.view }" @click="activeView = s.view">
          <div class="label">{{ s.label }}</div>
          <div class="value">{{ s.value }}<span class="unit">{{ s.unit }}</span></div>
        </div>
      </el-col>
    </el-row>

    <!-- 图表区域：各行业月度排放趋势折线图 + 行业排放占比饼图 -->
    <!-- 折线图每个行业一条平滑曲线（数据来自 enterprise-trend），饼图按行业累计排放占比（数据来自行业汇总） -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="14">
        <div class="panel">
          <div class="panel-header"><span class="title">各行业月度排放趋势</span></div>
          <div class="panel-body"><div ref="trendChartRef" :style="{ height: isMobile ? '240px' : '300px' }"></div></div>
        </div>
      </el-col>
      <el-col :span="10">
        <div class="panel">
          <div class="panel-header"><span class="title">行业排放占比</span></div>
          <div class="panel-body"><div ref="pieChartRef" :style="{ height: isMobile ? '240px' : '300px' }"></div></div>
        </div>
      </el-col>
    </el-row>

    <!-- 明细数据表格：随点击的统计卡片切换对应视图 -->
    <div class="panel">
      <div class="panel-header"><span class="title">{{ viewTitle }}</span></div>
      <div class="panel-body" style="padding: 0">
        <!-- 手机端卡片列表：随当前激活视图切换展示，字段与下方对应表格列一致 -->
        <div v-if="isMobile" v-loading="loading" class="m-list" style="padding: 12px">
          <!-- 行业汇总 -->
          <template v-if="activeView === 'industry'">
            <el-empty v-if="!industryStats.length" description="暂无行业汇总数据" :image-size="70" />
            <div v-for="row in industryStats" :key="row.industry" class="m-card">
              <div class="m-card-head">
                <span class="m-card-title">{{ row.industry }}</span>
                <span class="m-card-sub">{{ row.enterpriseCount }} 家企业</span>
              </div>
              <div class="m-row"><span class="m-key">累计排放</span><span class="m-val text-primary" style="font-weight:600">{{ row.emission }} tCO₂</span></div>
              <div class="m-row"><span class="m-key">认证数</span><span class="m-val">{{ row.certCount }}</span></div>
            </div>
          </template>
          <!-- 企业排放排行 -->
          <template v-else-if="activeView === 'enterprise'">
            <el-empty v-if="!enterpriseRank.length" description="暂无企业排行数据" :image-size="70" />
            <div v-for="(row, i) in enterpriseRank" :key="row.name" class="m-card">
              <div class="m-card-head">
                <span class="m-card-title">{{ row.name }}</span>
                <el-tag :type="i < 3 ? 'warning' : 'info'" size="small">{{ i + 1 }}</el-tag>
              </div>
              <div class="m-row"><span class="m-key">行业</span><span class="m-val">{{ row.industry }}</span></div>
              <div class="m-row"><span class="m-key">累计排放量</span><span class="m-val text-primary" style="font-weight:600">{{ row.total }} tCO₂</span></div>
              <div class="m-row"><span class="m-key">认证记录数</span><span class="m-val">{{ row.certCount }}</span></div>
              <div class="m-row"><span class="m-key">占比</span><span class="m-val">{{ row.percent }}%</span></div>
              <el-progress :percentage="row.percent" :stroke-width="8" color="#10b981" style="margin-top: 4px" />
            </div>
          </template>
          <!-- 行业排放排行 -->
          <template v-else-if="activeView === 'emission'">
            <el-empty v-if="!industryEmission.length" description="暂无行业排放数据" :image-size="70" />
            <div v-for="row in industryEmission" :key="row.industry" class="m-card">
              <div class="m-card-head">
                <span class="m-card-title">{{ row.industry }}</span>
                <span class="m-card-sub">{{ row.percent }}%</span>
              </div>
              <div class="m-row"><span class="m-key">累计排放</span><span class="m-val text-primary" style="font-weight:600">{{ row.total }} tCO₂</span></div>
              <el-progress :percentage="row.percent" :stroke-width="8" color="#0ea5e9" style="margin-top: 4px" />
            </div>
          </template>
          <!-- 认证记录 -->
          <template v-else>
            <el-empty v-if="!certList.length" description="暂无认证记录" :image-size="70" />
            <div v-for="row in certList" :key="row.id" class="m-card">
              <div class="m-card-head">
                <span class="m-card-title">{{ row.id }}</span>
              </div>
              <div class="m-row"><span class="m-key">企业名称</span><span class="m-val">{{ row.enterpriseName }}</span></div>
              <div class="m-row"><span class="m-key">报告日期</span><span class="m-val">{{ row.period }}</span></div>
              <div class="m-row"><span class="m-key">排放量</span><span class="m-val">{{ row.amount }} tCO₂</span></div>
              <div class="m-row"><span class="m-key">审核人</span><span class="m-val">{{ row.auditor }}</span></div>
              <div class="m-row"><span class="m-key">认证时间</span><span class="m-val">{{ row.certifiedAt }}</span></div>
            </div>
          </template>
        </div>

        <!-- 行业汇总视图 -->
        <el-table v-else-if="activeView === 'industry'" :data="industryStats" stripe v-loading="loading">
          <el-table-column prop="industry" label="行业" min-width="150" align="center" />
          <el-table-column prop="enterpriseCount" label="企业数" min-width="120" align="center" />
          <el-table-column label="累计排放" min-width="160" align="center">
            <template #default="{ row }"><span class="text-primary" style="font-weight:600">{{ row.emission }}</span> tCO₂</template>
          </el-table-column>
          <el-table-column prop="certCount" label="认证数" min-width="120" align="center" />
        </el-table>
        <!-- 企业排放排行视图 -->
        <el-table v-else-if="activeView === 'enterprise'" :data="enterpriseRank" stripe v-loading="loading">
          <el-table-column label="排名" min-width="80" align="center">
            <template #default="{ $index }">
              <el-tag :type="$index < 3 ? 'warning' : 'info'" size="small">{{ $index + 1 }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="企业名称" min-width="100" align="center" />
          <el-table-column prop="industry" label="行业" min-width="180" align="center" />
          <el-table-column label="累计排放量" min-width="100" >
            <template #default="{ row }"><span class="text-primary" style="font-weight:600">{{ row.total }}</span> tCO₂</template>
          </el-table-column>
          <el-table-column label="占比" min-width="120" align="center">
            <template #default="{ row }">
              <el-progress :percentage="row.percent" :stroke-width="8" color="#10b981" />
            </template>
          </el-table-column>
          <el-table-column label="认证记录数" min-width="110" align="center">
            <template #default="{ row }">{{ row.certCount }}</template>
          </el-table-column>
        </el-table>
        <!-- 行业排放排行视图 -->
        <el-table v-else-if="activeView === 'emission'" :data="industryEmission" stripe v-loading="loading">
          <el-table-column prop="industry" label="行业" min-width="150" align="center" />
          <el-table-column label="累计排放" min-width="160" align="center">
            <template #default="{ row }"><span class="text-primary" style="font-weight:600">{{ row.total }}</span> tCO₂</template>
          </el-table-column>
          <el-table-column label="占比" min-width="200" align="center">
            <template #default="{ row }">
              <el-progress :percentage="row.percent" :stroke-width="8" color="#0ea5e9" />
            </template>
          </el-table-column>
        </el-table>
        <!-- 认证记录视图 -->
        <el-table v-else :data="certList" stripe v-loading="loading">
          <el-table-column prop="id" label="认证编号" min-width="140" align="center" />
          <el-table-column prop="enterpriseName" label="企业名称" min-width="180" show-overflow-tooltip align="center" />
          <el-table-column prop="period" label="报告日期" min-width="120" align="center" />
          <el-table-column label="排放量" min-width="120" align="center">
            <template #default="{ row }">{{ row.amount }} tCO₂</template>
          </el-table-column>
          <el-table-column prop="auditor" label="审核人" min-width="100" align="center" />
          <el-table-column prop="certifiedAt" label="认证时间" min-width="170" align="center" />
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// 引入 Vue 计算属性与响应式引用
import { ref, computed, onMounted } from 'vue'
// 引入 ECharts 组合式函数，用于图表实例化与渲染
import { useECharts } from '@/composables/useECharts'
// 引入监管端统计与认证接口
import { getRegulatorIndustrySummary, getRegulatorIndustryRanking, getRegulatorEnterpriseRanking, getRegulatorEnterpriseTrend } from '@/api/stats'
import { getCertifications } from '@/api/certification'
// 引入设备检测：手机端将明细表格切换为卡片列表，并调整图表高度
import { useDevice } from '@/composables/useDevice'

// 设备类型（手机端渲染卡片列表、压缩图表高度）
const { isMobile } = useDevice()

// 图表配色方案（折线图与饼图共用）
const colors = ['#10b981', '#0ea5e9', '#f59e0b', '#8b5cf6', '#ef4444']

// 行业汇总：[{industry, enterpriseCount, emission, certCount}]
// 同时用于顶部“行业总数/累计排放/监管企业”指标、行业汇总表格与占比饼图
const industrySummary = ref([])
// 行业排放排行：[{industry, total, percent}]，percent 为占全部行业累计排放的百分比
const industryRanking = ref([])
// 企业排放排行：[{name, industry, total, percent, certCount}]，按累计排放量降序返回
const enterpriseRanking = ref([])
// 各行业月度趋势：{ months:[YYYY-MM], series:[{name,data}] }，每个行业一条折线
const trend = ref({ months: [], series: [] })
// 认证记录明细：用于认证总数指标与认证记录视图
const certList = ref([])
// 加载态
const loading = ref(false)

// 横轴月份标签（由后端返回的 YYYY-MM 转换为 "n月"）
const monthLabels = computed(() => trend.value.months.map(m => `${Number(m.slice(5))}月`))

// 明细表格数据：直接使用接口返回值，不做二次加工
// 行业汇总、行业排名、企业排名分别对应四个统计卡片中的三个视图
const industryStats = computed(() => industrySummary.value)
const industryEmission = computed(() => industryRanking.value)
const enterpriseRank = computed(() => enterpriseRanking.value)

// 累计碳排放总量：各行业 emission 相加（后端已按行业聚合，避免重复累加明细记录）
const totalEmission = computed(() => industrySummary.value.reduce((s, i) => s + Number(i.emission || 0), 0))
// 监管企业总数：各行业 enterpriseCount 相加
const totalEnterprise = computed(() => industrySummary.value.reduce((s, i) => s + Number(i.enterpriseCount || 0), 0))

// 顶部统计卡片数据：行业总数 / 监管企业 / 累计排放 / 认证总数（view 为点击后切换的明细视图）
// 累计排放取整展示（toFixed(0)），业界统一单位 tCO₂
const stats = computed(() => [
  { label: '行业总数', value: industrySummary.value.length, unit: '个', view: 'industry' },
  { label: '监管企业', value: totalEnterprise.value, unit: '家', view: 'enterprise' },
  { label: '累计排放', value: totalEmission.value.toFixed(0), unit: 'tCO₂', view: 'emission' },
  { label: '认证总数', value: certList.value.length, unit: '条', view: 'certification' }
])

// 当前激活的明细视图：industry 行业汇总 / enterprise 企业排行 / emission 行业排放 / certification 认证记录
const activeView = ref('enterprise')
// 明细表标题（随视图切换）
const viewTitle = computed(() => ({
  industry: '行业汇总',
  enterprise: '企业排放排行',
  emission: '行业排放排行',
  certification: '认证记录'
}[activeView.value]))

// 各行业月度排放趋势折线图配置：每个行业一条平滑曲线
// 系列直接来自 trend.series，图例与配色按行业顺序分配；数据更新后由 useECharts 自动重绘
const trendOption = () => ({
  tooltip: { trigger: 'axis' },
  legend: { bottom: 0, textStyle: { fontSize: 11 } },
  grid: { left: 50, right: 20, top: 20, bottom: 50 },
  xAxis: { type: 'category', data: monthLabels.value, axisLine: { lineStyle: { color: '#e2e8f0' } } },
  yAxis: { type: 'value', name: 'tCO₂', splitLine: { lineStyle: { color: '#f1f5f9' } } },
  color: colors,
  series: trend.value.series.map(s => ({
    name: s.name,
    type: 'line',
    smooth: true,
    data: s.data,
    lineStyle: { width: 2 }
  }))
})
// 趋势折线图实例（通过 useECharts 绑定到 trendChartRef）
const { chartRef: trendChartRef } = useECharts(trendOption)

// 饼图数据：各行业累计排放量
// 取整是为了让 tooltip / 图例读数与表格展示口径一致（tCO₂ 整数）
const pieData = computed(() => industrySummary.value.map(i => ({
  name: i.industry,
  value: Math.round(Number(i.emission || 0))
})))
// 行业排放占比饼图配置（环形图）：占比由 ECharts 依据各项数值自动计算
const pieOption = () => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c} tCO₂ ({d}%)' },
  legend: { bottom: 0, textStyle: { fontSize: 11 } },
  color: colors,
  series: [{
    type: 'pie', radius: ['40%', '70%'], center: ['50%', '45%'],
    itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 2 },
    label: { show: false },
    data: pieData.value
  }]
})
// 占比饼图实例（通过 useECharts 绑定到 pieChartRef）
const { chartRef: pieChartRef } = useECharts(pieOption)

// 拉取行业统计与认证数据
// 五个接口并行请求，任一失败都会进入 finally 关闭 loading；空值统一兜底为空数组/空趋势
async function load() {
  loading.value = true
  try {
    // 返回依次为：行业汇总 / 行业排名 / 企业排名 / 月度趋势 / 认证记录
    const [summary, ranking, entRanking, trendData, certs] = await Promise.all([
      getRegulatorIndustrySummary(),
      getRegulatorIndustryRanking(),
      getRegulatorEnterpriseRanking(),
      getRegulatorEnterpriseTrend(),
      getCertifications()
    ])
    industrySummary.value = summary || []
    industryRanking.value = ranking || []
    enterpriseRanking.value = entRanking || []
    trend.value = trendData || { months: [], series: [] }
    certList.value = certs || []
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
/* ===== 移动端（≤767px）：由 isMobile 压缩图表高度（240px）并切换卡片列表，卡片外观来自全局 mobile.css ===== */
/* 可点击的统计卡片：手型光标提示可交互 */
.stat-card.clickable { cursor: pointer; }
/* 统计卡片选中态：主题色边框 + 浅绿背景，标识当前激活的明细视图 */
.stat-card.active {
  border-color: var(--primary);
  background: #f0fdf9;
}
</style>
