<!--
  文件：TodayAudit.vue
  用途：审核端 - 今日审核页面
  功能：
    1. 汇总今日完成的审核工作量：已审核总数、通过数、驳回数
    2. 列出今日已审核的报告记录，支持按审核结果与关键词筛选
    3. 点击详情通过抽屉查看单条记录的完整审核信息
  路由：/auditor/today（name: TodayAudit，仅 auditor 角色可访问）
  依赖：今日审核列表与今日汇总统计接口、报告详情接口
-->
<template>
  <div class="page-container">
    <!-- 页面头部：标题与功能说明 -->
    <div class="page-header">
      <h2>今日审核</h2>
      <p>查看今日已完成的审核记录、审核结果分布与审核意见</p>
    </div>

    <!-- 统计卡片区：今日已审核 / 通过 / 驳回，点击卡片筛选下方列表
         数值来自今日汇总接口（total / passed / rejected），不受下方筛选条件影响 -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="8" v-for="s in stats" :key="s.label">
        <div
          class="stat-card clickable"
          :class="[s.cls, { active: filters.result === s.status }]"
          @click="selectResult(s.status)"
        >
          <div class="label"><el-icon><component :is="s.icon" /></el-icon> {{ s.label }}</div>
          <div class="value">{{ s.value }}<span class="unit">份</span></div>
        </div>
      </el-col>
    </el-row>

    <div class="panel">
      <!-- 筛选区：审核结果（certified / rejected）+ 关键词（企业名称或报告编号，前端本地包含匹配） -->
      <div class="panel-body" style="padding: 16px 20px 0">
        <el-form :inline="true" :model="filters">
          <el-form-item label="审核结果">
            <el-select v-model="filters.result" placeholder="全部" clearable style="width: 140px">
              <el-option label="通过" value="certified" />
              <el-option label="驳回" value="rejected" />
            </el-select>
          </el-form-item>
          <el-form-item label="关键词">
            <el-input v-model="filters.keyword" placeholder="企业 / 报告编号" clearable style="width: 220px" />
          </el-form-item>
        </el-form>
      </div>
      <!-- 今日审核记录列表：手机端卡片列表，桌面端表格
           列表均为当日记录，审核时间只截取时分秒（auditedAt.slice(11)） -->
      <div style="padding: 0 20px 20px">
        <div v-if="isMobile" v-loading="loading" class="m-list">
          <el-empty v-if="!filtered.length" description="暂无今日审核记录" :image-size="70" />
          <div v-for="row in filtered" :key="row.id" class="m-card">
            <div class="m-card-head">
              <span class="m-card-title">{{ row.id }}</span>
              <el-tag :type="row.status === 'certified' ? 'success' : 'danger'" size="small">
                {{ row.status === 'certified' ? '通过' : '驳回' }}
              </el-tag>
            </div>
            <div class="m-row"><span class="m-key">企业</span><span class="m-val">{{ row.enterpriseName }}</span></div>
            <div class="m-row"><span class="m-key">报告日期</span><span class="m-val">{{ row.period }}</span></div>
            <div class="m-row"><span class="m-key">排放源</span><span class="m-val">{{ row.sourceName }}</span></div>
            <div class="m-row"><span class="m-key">排放量</span><span class="m-val text-primary" style="font-weight:600">{{ row.amount }} tCO₂</span></div>
            <div class="m-row"><span class="m-key">审核人</span><span class="m-val">{{ row.auditor }}</span></div>
            <div class="m-row"><span class="m-key">审核时间</span><span class="m-val">{{ row.auditedAt ? row.auditedAt.slice(11) : '-' }}</span></div>
            <div class="m-card-actions">
              <el-button text type="primary" size="small" @click="view(row)">详情</el-button>
            </div>
          </div>
        </div>

        <el-table v-else :data="filtered" stripe v-loading="loading">
          <el-table-column prop="id" label="报告编号" min-width="130" align="center" />
          <el-table-column prop="enterpriseName" label="企业" min-width="200" show-overflow-tooltip align="center" />
          <el-table-column prop="period" label="报告日期" min-width="130" align="center" />
          <el-table-column prop="sourceName" label="排放源" min-width="120" align="center" />
          <el-table-column label="排放量" min-width="120" align="center">
            <template #default="{ row }"><span class="text-primary" style="font-weight:600">{{ row.amount }}</span> tCO₂</template>
          </el-table-column>
          <el-table-column prop="auditor" label="审核人" min-width="100" align="center" />
          <el-table-column label="审核时间" min-width="180" align="center">
            <template #default="{ row }">{{ row.auditedAt ? row.auditedAt.slice(11) : '-' }}</template>
          </el-table-column>
          <!-- 结果列：通过显示绿色标签，驳回显示红色标签 -->
          <el-table-column label="结果" min-width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'certified' ? 'success' : 'danger'" size="small">
                {{ row.status === 'certified' ? '通过' : '驳回' }}
              </el-tag>
            </template>
          </el-table-column>
          <!-- 操作列：打开详情抽屉 -->
          <el-table-column label="操作" min-width="100" fixed="right" align="center">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="view(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 审核记录详情弹框：补全防伪码、提交人、审核意见等字段后居中展示（手机端内容区可滚动） -->
    <el-dialog v-model="showDetail" title="审核记录详情" width="620px">
      <div v-if="current" :class="{ 'dlg-scroll': isMobile }">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="报告编号">{{ current.id }}</el-descriptions-item>
          <el-descriptions-item label="企业">{{ current.enterpriseName }}</el-descriptions-item>
          <el-descriptions-item label="报告日期">{{ current.period }}</el-descriptions-item>
          <el-descriptions-item label="排放源">{{ current.sourceName }}</el-descriptions-item>
          <el-descriptions-item label="排放量">{{ current.amount }} tCO₂</el-descriptions-item>
          <el-descriptions-item label="电子防伪码"><span class="hash-text">{{ current.fingerprint }}</span></el-descriptions-item>
          <el-descriptions-item label="提交人">{{ current.submitter }}</el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ current.submittedAt }}</el-descriptions-item>
          <el-descriptions-item label="审核人">{{ current.auditor }}</el-descriptions-item>
          <el-descriptions-item label="审核时间">{{ current.auditedAt }}</el-descriptions-item>
          <el-descriptions-item label="审核结果">
            <el-tag :type="current.status === 'certified' ? 'success' : 'danger'" size="small">
              {{ current.status === 'certified' ? '通过' : '驳回' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="审核意见">{{ current.auditOpinion }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="showDetail = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// 引入 Vue 组合式 API：ref 响应式、reactive 响应式对象、computed 计算属性
import { ref, reactive, computed, onMounted } from 'vue'
// 引入今日审核统计与报告详情接口
import { getAuditorTodayList, getAuditorTodaySummary } from '@/api/stats'
import { getReportRecord } from '@/api/report'
// 引入设备检测：手机端渲染卡片列表与可滚动弹窗
import { useDevice } from '@/composables/useDevice'

// 设备类型（手机端切换为卡片列表）
const { isMobile } = useDevice()

// 今日已审核列表（含企业、报告期、排放源、审核人与审核时间，不含防伪码/审核意见）
const todayList = ref([])
// 今日审核汇总（total 已审核总数 / passed 通过数 / rejected 驳回数）
const summary = ref({ total: 0, passed: 0, rejected: 0 })
// 列表加载态
const loading = ref(false)

// 筛选条件：result 审核结果、keyword 关键词
const filters = reactive({ result: '', keyword: '' })

// 最终展示列表：在今日记录基础上应用筛选条件
// - result 为空时展示全部结果
// - keyword 按包含关系匹配企业名称或报告编号（区分大小写，未做 trim / 小写归一）
const filtered = computed(() => todayList.value.filter(r =>
  (!filters.result || r.status === filters.result) &&
  (!filters.keyword || r.enterpriseName.includes(filters.keyword) || r.id.includes(filters.keyword))
))

// 统计卡片数据：status 为空表示不限结果（今日已审核）；点击卡片按该结果筛选列表
const stats = computed(() => [
  { label: '今日已审核', value: summary.value.total || 0, cls: '', status: '', icon: 'DocumentChecked' },
  { label: '今日通过', value: summary.value.passed || 0, cls: 'success', status: 'certified', icon: 'CircleCheck' },
  { label: '今日驳回', value: summary.value.rejected || 0, cls: 'danger', status: 'rejected', icon: 'CircleClose' }
])

// 点击统计卡片：按对应审核结果筛选列表；再次点击同一卡片则取消筛选
function selectResult(status) {
  filters.result = filters.result === status ? '' : status
}

// 详情弹框显隐控制与当前查看的记录
const showDetail = ref(false)
const current = ref(null)
// 打开详情弹框：拉取完整报告详情（今日列表接口不含防伪码/审核意见等字段），失败时回退为列表行数据
async function view(row) {
  try {
    current.value = await getReportRecord(row.id)
  } catch (e) {
    current.value = row
  }
  showDetail.value = true
}

// 拉取今日审核数据：列表与汇总两个接口并发请求
async function load() {
  loading.value = true
  try {
    const [list, sum] = await Promise.all([getAuditorTodayList(), getAuditorTodaySummary()])
    todayList.value = list || []
    summary.value = sum || {}
  } finally {
    loading.value = false
  }
}
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>

<style scoped>
/* ===== 桌面端（>767px）基础样式 ===== */
/* 统计卡片数值颜色变体：通过绿色、驳回红色 */
.stat-card.success .value { color: #10b981; }
.stat-card.danger .value { color: #ef4444; }
/* 可点击的统计卡片：手型光标提示可交互 */
.stat-card.clickable { cursor: pointer; }
/* 统计卡片选中态：主题色边框 + 浅绿背景，标识当前筛选的结果 */
.stat-card.active {
  border-color: var(--primary);
  background: #f0fdf9;
}
/* ===== 移动端（≤767px）适配 ===== */
/* 手机端弹窗内容区：限制最大高度并允许滚动，保证底部按钮始终可达 */
.dlg-scroll {
  max-height: 62vh;
  overflow-y: auto;
  padding-right: 4px;
}
</style>
