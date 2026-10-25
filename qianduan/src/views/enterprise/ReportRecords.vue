<!--
  ReportRecords.vue - 报告提交记录页（企业端）
  用途：展示本企业所有碳排放报告的提交状态与审核结果，支持按报告期/状态筛选及查看报告详情
  路由：/enterprise/records（name: ReportRecords，仅 enterprise 角色可访问）
        支持 /enterprise/records?status=certified|pending|rejected 直达对应状态
  依赖：报告记录接口；活动数据与排放因子为报告记录的联查展示字段，详情抽屉中完整呈现
-->
<template>
  <div class="page-container">
    <!-- 页面标题区：展示页面名称与功能说明 -->
    <div class="page-header">
      <h2>报告提交记录</h2>
      <p>查看本企业所有碳排放报告的提交状态与审核结果</p>
    </div>

    <!-- 统计卡片区：汇总提交总数及各审核状态数量，点击卡片筛选下方列表
         数值在前端按已加载的记录列表统计（非服务端汇总），选中状态以 filters.status 为准 -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="6" v-for="s in stats" :key="s.label">
        <div class="stat-card clickable" :class="{ active: filters.status === s.status }" @click="selectStatus(s.status)">
          <div class="label">{{ s.label }}</div>
          <div class="value" :class="s.cls">{{ s.value }}</div>
        </div>
      </el-col>
    </el-row>
    
    <div class="panel">
      <!-- 筛选区：仅关键词搜索；报告日期与状态通过上方统计卡片筛选，和关键词条件叠加生效 -->
      <div class="panel-body" style="padding: 16px 20px 0">
        <el-form :inline="true" :model="filters">
          <el-form-item label="关键词">
            <el-input v-model="filters.keyword" placeholder="报告编号 / 排放源 / 提交人 / 日期" clearable style="width: 280px">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
          </el-form-item>
        </el-form>
      </div>
      <!-- 报告列表：手机端用卡片列表，桌面端用表格
           卡片展示活动数据与排放因子；桌面表格为保持列宽仅展示关键字段，完整字段见详情抽屉
           列表一次性渲染接口返回的全部记录，未做分页 -->
      <div style="padding: 0 20px 20px">
        <div v-if="isMobile" v-loading="loading" class="m-list">
          <el-empty v-if="!filtered.length" description="暂无报告记录" :image-size="70" />
          <div v-for="row in filtered" :key="row.id" class="m-card">
            <div class="m-card-head">
              <span class="m-card-title">{{ row.id }}</span>
              <el-tag :type="tagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
            </div>
            <div class="m-row"><span class="m-key">报告日期</span><span class="m-val">{{ row.period }}</span></div>
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
            <div class="m-row"><span class="m-key">提交人</span><span class="m-val">{{ row.submitter }}</span></div>
            <div class="m-row"><span class="m-key">提交时间</span><span class="m-val">{{ row.submittedAt }}</span></div>
            <div class="m-row"><span class="m-key">审核人</span><span class="m-val">{{ row.auditor || '-' }}</span></div>
            <div class="m-card-actions">
              <el-button size="small" type="primary" text @click="viewDetail(row)">详情</el-button>
              <el-button v-if="row.status === 'rejected'" size="small" type="warning" text @click="viewDetail(row)">重新提交</el-button>
            </div>
          </div>
        </div>

        <el-table v-else :data="filtered" stripe v-loading="loading">
          <el-table-column prop="id" label="报告编号" min-width="100" align="center" />
          <el-table-column prop="period" label="报告日期" min-width="180" align="center" />
          <el-table-column prop="sourceName" label="排放源" min-width="130"  />
          <el-table-column label="排放量" min-width="130" >
            <template #default="{ row }"><span class="text-primary" style="font-weight:600">{{ row.amount }}</span> tCO₂</template>
          </el-table-column>
          <el-table-column prop="submitter" label="提交人" min-width="100" align="center" />
          <el-table-column prop="submittedAt" label="提交时间" min-width="170" align="center" />
          <el-table-column label="审核人" min-width="100" align="center">
            <template #default="{ row }">{{ row.auditor || '-' }}</template>
          </el-table-column>
          <el-table-column label="状态" min-width="120" align="center">
            <template #default="{ row }"><el-tag :type="tagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" min-width="160" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="viewDetail(row)">详情</el-button>
              <el-button v-if="row.status === 'rejected'" text type="warning" size="small" @click="viewDetail(row)">重新提交</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 报告详情抽屉：展示选中报告的完整信息，含活动数据、排放因子、电子防伪码与审核意见
         「重新提交」按钮当前与「详情」行为相同（只打开抽屉），实际重新申请需在认证申请页发起 -->
    <el-drawer v-model="showDetail" title="报告详情" size="520px">
      <div v-if="current">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="报告编号">{{ current.id }}</el-descriptions-item>
          <el-descriptions-item label="报告日期">{{ current.period }}</el-descriptions-item>
          <el-descriptions-item label="排放源">{{ current.sourceName }}</el-descriptions-item>
          <el-descriptions-item label="活动数据">{{ current.activityData ?? '-' }} {{ current.activityUnit || '' }}</el-descriptions-item>
          <el-descriptions-item label="排放因子">{{ current.emissionFactor ?? '-' }} {{ current.unit || '' }}/单位</el-descriptions-item>
          <el-descriptions-item label="排放量">{{ current.amount }} tCO₂</el-descriptions-item>
          <el-descriptions-item label="电子防伪码"><span class="hash-text">{{ current.fingerprint }}</span></el-descriptions-item>
          <el-descriptions-item label="提交人">{{ current.submitter }}</el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ current.submittedAt }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="tagType(current.status)" size="small">{{ statusLabel(current.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="current.auditor" label="审核人">{{ current.auditor }}</el-descriptions-item>
          <el-descriptions-item v-if="current.auditedAt" label="审核时间">{{ current.auditedAt }}</el-descriptions-item>
          <el-descriptions-item v-if="current.auditOpinion" label="审核意见">{{ current.auditOpinion }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// 引入 Vue 响应式 API：ref 响应式基本值，reactive 响应式对象，computed 计算属性
import { ref, reactive, computed, onMounted } from 'vue'
// 引入路由实例，用于读取首页卡片跳转时携带的筛选条件
import { useRoute } from 'vue-router'
// 引入用户 store，用于获取当前登录企业信息
import { useUserStore } from '@/store'
// 引入报告记录接口
import { getReportRecords } from '@/api/report'
// 引入设备检测：手机端渲染卡片列表，桌面端渲染表格
import { useDevice } from '@/composables/useDevice'

// 用户 store 实例
const userStore = useUserStore()
// 当前路由信息（读取 query.status，实现从首页卡片直达对应状态的列表）
const route = useRoute()
// 当前企业的报告记录
const myRecords = ref([])
// 列表加载态
const loading = ref(false)
// 设备类型（手机端切换为卡片列表）
const { isMobile } = useDevice()

// 筛选条件：关键词与审核状态（状态初始取路由 query.status，用于首页卡片直达；
// 仅在组件初始化时读取一次，后续同一路由内的 query 变化不会同步到此处）
const filters = reactive({ keyword: '', status: route.query.status || '' })

// 关键词可匹配的字段：报告编号、排放源、提交人、审核人、报告期、提交时间（后两者支持按日期搜索）
const keywordFields = ['id', 'sourceName', 'submitter', 'auditor', 'period', 'submittedAt']
// 判断一条记录是否命中关键词（大小写不敏感，去掉首尾空格）
function matchKeyword(row) {
  const kw = filters.keyword.trim().toLowerCase()
  if (!kw) return true
  return keywordFields.some(f => String(row[f] || '').toLowerCase().includes(kw))
}

// 经筛选后的报告列表（关键词与状态条件同时满足；status 为空表示不限状态）
const filtered = computed(() => myRecords.value.filter(r =>
  matchKeyword(r) &&
  (!filters.status || r.status === filters.status)
))

// 各状态报告数量统计（基于未筛选的完整列表，因此卡片数字不随筛选条件变化）
const certified = computed(() => myRecords.value.filter(r => r.status === 'certified').length)
const pending = computed(() => myRecords.value.filter(r => r.status === 'pending').length)
const rejected = computed(() => myRecords.value.filter(r => r.status === 'rejected').length)

// 统计卡片数据：status 为空表示不限状态（提交总数），点击后按该状态筛选列表
const stats = computed(() => [
  { label: '提交总数', value: myRecords.value.length, cls: '', status: '' },
  { label: '已认证', value: certified.value, cls: 'text-success', status: 'certified' },
  { label: '审核中', value: pending.value, cls: 'text-warning', status: 'pending' },
  { label: '已驳回', value: rejected.value, cls: 'text-danger', status: 'rejected' }
])

// 点击统计卡片：按对应状态筛选列表；再次点击同一卡片则取消筛选
function selectStatus(status) {
  filters.status = filters.status === status ? '' : status
}

// 详情抽屉显隐控制与当前选中报告
const showDetail = ref(false)
const current = ref(null)
// 打开详情抽屉：记录当前行并显示
function viewDetail(row) { current.value = row; showDetail.value = true }
// 状态码映射为中文标签
function statusLabel(s) { return { certified: '已认证', pending: '审核中', rejected: '已驳回' }[s] }
// 状态码映射为 el-tag 类型（控制颜色）
function tagType(s) { return { certified: 'success', pending: 'warning', rejected: 'danger' }[s] }

// 拉取当前企业报告记录
async function load() {
  loading.value = true
  try {
    myRecords.value = await getReportRecords({ enterpriseId: userStore.user?.enterpriseId })
  } finally {
    loading.value = false
  }
}
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>

<style scoped>
/* ===== 桌面端（>767px）基础样式；≤767px 的移动端排版由 isMobile + mobile.css 处理 ===== */
/* 可点击的统计卡片：手型光标提示可交互 */
.stat-card.clickable {
  cursor: pointer;
}
/* 统计卡片选中态：主题色边框 + 浅绿背景，标识当前筛选的状态 */
.stat-card.active {
  border-color: var(--primary);
  background: #f0fdf9;
}
</style>
