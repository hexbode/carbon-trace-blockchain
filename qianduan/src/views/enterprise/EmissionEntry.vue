<!--
  EmissionEntry.vue —— 碳排放数据填报页（企业端）
  用途：企业按排放源填报温室气体排放数据，系统按报告期与排放源自动匹配排放因子、
  计算核算排放量，提交后生成电子防伪码并进入认证流程，同时支持筛选与详情查看。
-->
<template>
  <div class="page-container">
    <!-- 页面标题区：标题说明 + 新增排放数据按钮 -->
    <div class="page-header flex-between">
      <div>
        <h2>碳排放数据录入</h2>
        <p>按排放源填报企业温室气体排放数据，提交后将生成电子防伪码并进入认证流程</p>
      </div>
      <el-button type="primary" @click="showForm = true">
        <el-icon><Plus /></el-icon> 新增排放数据
      </el-button>
    </div>

    <!-- 筛选区：关键词搜索 + 按报告期、排放源、认证状态过滤记录 -->
    <div class="panel mb-16">
      <div class="panel-body">
        <el-form :inline="true" :model="filters">
          <el-form-item label="关键词">
            <el-input v-model="filters.keyword" placeholder="记录编号 / 排放源 / 指纹 / 日期" clearable style="width: 260px">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="报告日期">
            <el-select v-model="filters.period" placeholder="全部" clearable style="width: 140px">
              <el-option v-for="p in periods" :key="p" :label="p" :value="p" />
            </el-select>
          </el-form-item>
          <el-form-item label="排放源">
            <el-select v-model="filters.source" placeholder="全部" clearable style="width: 160px">
              <el-option v-for="s in emissionSources" :key="s.code" :label="s.name" :value="s.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="认证状态">
            <el-select v-model="filters.status" placeholder="全部" clearable style="width: 120px">
              <el-option label="已认证" value="certified" />
              <el-option label="待审核" value="pending" />
              <el-option label="已驳回" value="rejected" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button @click="resetFilters">重置</el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <!-- 数据列表：展示排放记录编号、报告期、排放源、活动数据、因子、排放量、指纹与状态 -->
    <div class="panel">
      <el-table :data="filteredRecords" stripe v-loading="loading">
        <!-- min-width 按目标占比等比取值（1% = 10px），Element Plus 会按 min-width 比例分配剩余宽度 -->
        <el-table-column prop="id" label="记录编号" min-width="80" align="center" />
        <el-table-column prop="period" label="报告日期" min-width="100" align="center" />
        <el-table-column prop="sourceName" label="排放源" min-width="90"  />
        <el-table-column label="活动数据" min-width="90" >
          <template #default="{ row }">
            {{ row.activityData }} {{ row.activityUnit }}
          </template>
        </el-table-column>
        <el-table-column label="排放因子" min-width="100" >
          <template #default="{ row }">{{ row.emissionFactor }} {{ row.unit }}/单位</template>
        </el-table-column>
        <el-table-column label="碳排放量" min-width="60" >
          <template #default="{ row }">
            <span class="text-primary" style="font-weight: 600">{{ row.amount }}</span> {{ row.unit }}
          </template>
        </el-table-column>
        <el-table-column label="电子防伪码" min-width="120" show-overflow-tooltip align="center">
          <template #default="{ row }">
            <span class="hash-text">{{ row.fingerprint }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right" align="center">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="viewDetail(row)" align="center">详情</el-button>
            <el-button v-if="row.status === 'pending'" text type="warning" size="small" @click="viewDetail(row)">审核中</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新增表单弹窗 -->
    <el-dialog v-model="showForm" title="新增碳排放数据" width="640px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <el-form-item label="报告日期" prop="period">
          <el-date-picker
            v-model="form.period"
            type="month"
            value-format="YYYY-MM"
            placeholder="请选择报告年月"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="排放源" prop="sourceCode">
          <el-select v-model="form.sourceCode" placeholder="请选择排放源" style="width: 100%" @change="onSourceChange">
            <el-option v-for="s in emissionSources" :key="s.code" :label="s.name" :value="s.code">
              <span>{{ s.name }}</span>
              <span style="float: right; color: #94a3b8; font-size: 12px">因子: {{ s.factor }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="活动数据" prop="activityData">
          <el-input-number v-model="form.activityData" :min="0" :precision="2" style="width: 100%" />
          <div class="form-tip">单位：{{ currentSource?.unit || '-' }}</div>
        </el-form-item>
        <el-form-item label="排放因子">
          <span class="factor-display">{{ currentSource?.factor || '-' }} tCO₂/单位</span>
          <div class="form-tip">系统依据《企业温室气体排放核算指南》自动匹配</div>
        </el-form-item>
        <el-form-item label="核算排放量">
          <span class="emission-result">{{ calculatedAmount.toFixed(2) }} tCO₂</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="补充说明（选填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showForm = false">取消</el-button>
        <el-button type="primary" @click="submitForm" :loading="submitting">提交并生成指纹</el-button>
      </template>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog v-model="showDetail" title="排放数据详情" width="640px">
      <el-descriptions :column="2" border v-if="current">
        <el-descriptions-item label="记录编号">{{ current.id }}</el-descriptions-item>
        <el-descriptions-item label="报告日期">{{ current.period }}</el-descriptions-item>
        <el-descriptions-item label="排放源">{{ current.sourceName }}</el-descriptions-item>
        <el-descriptions-item label="填报人">{{ current.reporter }}</el-descriptions-item>
        <el-descriptions-item label="活动数据">{{ current.activityData }} {{ current.activityUnit }}</el-descriptions-item>
        <el-descriptions-item label="排放因子">{{ current.emissionFactor }}</el-descriptions-item>
        <el-descriptions-item label="碳排放量" :span="2">
          <span class="text-primary" style="font-weight: 600">{{ current.amount }} {{ current.unit }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="电子防伪码" :span="2">
          <span class="hash-text">{{ current.fingerprint }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="提交时间" :span="2">{{ current.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="认证状态">
          <el-tag :type="statusTag(current.status)" size="small">{{ statusLabel(current.status) }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// ===== 依赖引入 =====
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
// 排放记录与排放源接口
import { getEmissionRecords, getEmissionSources, createEmissionRecord } from '@/api/emission'
// 企业详情接口
import { getEnterprise } from '@/api/enterprise'
import { useUserStore } from '@/store'

// ===== 当前登录企业与报告期 =====
const userStore = useUserStore()
// 当前登录用户关联的企业 ID
const enterpriseId = userStore.user?.enterpriseId
// 当前企业信息
const enterprise = ref(null)

// 生成 2024-01 ~ 2024-12 的报告期选项
const periods = Array.from({ length: 12 }, (_, i) => `2024-${String(i + 1).padStart(2, '0')}`)
// 排放源字典（用于筛选与填报下拉）
const emissionSources = ref([])

// ===== 筛选与列表 =====
const filters = reactive({ keyword: '', period: '', source: '', status: '' })
// 重置筛选条件
const resetFilters = () => { filters.keyword = ''; filters.period = ''; filters.source = ''; filters.status = '' }

// 关键词可匹配的字段：记录编号、排放源、电子防伪码、填报人、报告期、提交时间（后两者支持按日期搜索）
const keywordFields = ['id', 'sourceName', 'fingerprint', 'reporter', 'period', 'createdAt']
// 判断一条记录是否命中关键词（大小写不敏感，去掉首尾空格）
function matchKeyword(row) {
  const kw = filters.keyword.trim().toLowerCase()
  if (!kw) return true
  return keywordFields.some(f => String(row[f] || '').toLowerCase().includes(kw))
}

// 当前企业的排放记录（后端已按 enterpriseId 过滤）
const myRecords = ref([])
// 列表加载态
const loading = ref(false)
// 在自身记录基础上再按关键词、报告期、排放源、认证状态过滤
const filteredRecords = computed(() =>
  myRecords.value.filter(r =>
    matchKeyword(r) &&
    (!filters.period || r.period === filters.period) &&
    (!filters.source || r.sourceCode === filters.source) &&
    (!filters.status || r.status === filters.status)
  )
)

// 认证状态值 → 中文标签
function statusLabel(s) {
  return { certified: '已认证', pending: '待审核', rejected: '已驳回' }[s]
}
// 认证状态值 → 标签颜色类型
function statusTag(s) {
  return { certified: 'success', pending: 'warning', rejected: 'danger' }[s]
}

// ===== 新增表单 =====
const showForm = ref(false)
const formRef = ref()
const form = reactive({ period: '', sourceCode: '', activityData: null, remark: '' })
// 提交按钮加载态
const submitting = ref(false)
// 表单校验规则
const formRules = {
  period: [{ required: true, message: '请选择报告期', trigger: 'change' }],
  sourceCode: [{ required: true, message: '请选择排放源', trigger: 'change' }],
  activityData: [{ required: true, message: '请输入活动数据', trigger: 'blur' }]
}

// 当前选中的排放源（用于取单位与排放因子）
const currentSource = computed(() => emissionSources.value.find(s => s.code === form.sourceCode))
// 核算排放量 = 活动数据 × 排放因子
const calculatedAmount = computed(() =>
  (form.activityData || 0) * (currentSource.value?.factor || 0)
)
// 切换排放源时清空已填活动数据，避免单位不匹配
function onSourceChange() { form.activityData = null }

// 拉取当前企业排放记录
async function loadRecords() {
  loading.value = true
  try {
    myRecords.value = await getEmissionRecords({ enterpriseId })
  } finally {
    loading.value = false
  }
}

// 提交表单：校验通过后调用后端接口（后端自动计算排放量与电子防伪码）
async function submitForm() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await createEmissionRecord({
      enterpriseId,
      enterpriseName: enterprise.value?.name,
      period: form.period,
      sourceCode: form.sourceCode,
      activityData: form.activityData
    })
    ElMessage.success('数据已提交，电子防伪码已生成，等待监管认证')
    showForm.value = false
    // 重置表单
    form.period = ''; form.sourceCode = ''; form.activityData = null; form.remark = ''
    // 重新拉取列表刷新页面
    await loadRecords()
  } finally {
    submitting.value = false
  }
}

// ===== 详情 =====
const showDetail = ref(false)
const current = ref(null)
// 打开详情弹窗并展示对应记录
function viewDetail(row) { current.value = row; showDetail.value = true }

// 初始化：加载企业信息、排放源字典与排放记录
onMounted(async () => {
  if (enterpriseId) {
    try { enterprise.value = await getEnterprise(enterpriseId) } catch (e) { enterprise.value = null }
  }
  try { emissionSources.value = await getEmissionSources() } catch (e) { emissionSources.value = [] }
  await loadRecords()
})
// 定时自动刷新：仅重新拉取排放记录列表，不影响正在填写的表单
useAutoRefresh(loadRecords)
</script>

<style scoped>
/* 表单字段下方的提示文字 */
.form-tip {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 4px;
}
/* 排放因子展示样式 */
.factor-display {
  font-weight: 600;
  color: #475569;
}
/* 核算排放量结果高亮样式 */
.emission-result {
  font-size: 18px;
  font-weight: 700;
  color: #10b981;
}
</style>
