<!--
  文件：CertificationRevoke.vue 认证撤销管理页面
  所属端：监管端
  功能描述：对已认证的碳排放数据进行复核，当发现数据造假、
           核算方法错误等问题时，可撤销认证并记录撤销原因与详细说明。
           页面提供有效认证、已撤销、撤销率统计，支持按企业名称与状态筛选，
           以及撤销详情查看。撤销后链上电子防伪码保留但标记为已撤销。
-->
<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <h2>认证管理</h2>
      <p>对已认证的碳排放数据进行复核，发现问题时可撤销认证并记录原因</p>
    </div>

    <!-- 顶部统计卡片：有效认证 / 已撤销 / 撤销率（前两个点击筛选下方列表） -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="8" v-for="s in stats" :key="s.label">
        <div
          class="stat-card"
          :class="[s.clickable ? 'clickable' : '', { active: s.status && filters.status === s.status }]"
          @click="s.clickable && selectStatus(s.status)"
        >
          <div class="label">{{ s.label }}</div>
          <div class="value" :class="s.cls">{{ s.value }}<span v-if="s.unit" class="unit">{{ s.unit }}</span></div>
        </div>
      </el-col>
    </el-row>

    <!-- 认证记录列表面板：包含筛选条件与数据表格 -->
    <div class="panel">
      <div class="panel-body" style="padding: 16px 20px 0">
        <!-- 筛选区：关键词搜索 + 状态（有效/已撤销）筛选 -->
        <el-form :inline="true" :model="filters">
          <el-form-item label="关键词">
            <el-input v-model="filters.keyword" placeholder="认证编号 / 企业名称 / 报告日期 / 审核人" clearable style="width: 260px">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="filters.status" placeholder="全部" clearable style="width:120px">
              <el-option label="有效" value="valid" />
              <el-option label="已撤销" value="revoked" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>
      <div style="padding: 0 20px 20px">
        <!-- 认证记录表格 -->
        <el-table :data="filtered" stripe v-loading="loading">
          <el-table-column prop="id" label="认证编号" min-width="130" align="center" />
          <el-table-column prop="enterpriseName" label="企业名称" min-width="130" show-overflow-tooltip align="center" />
          <el-table-column prop="period" label="报告日期" min-width="150" align="center" />
          <el-table-column label="排放量" min-width="120" >
            <template #default="{ row }">{{ row.amount }} tCO₂</template>
          </el-table-column>
          <el-table-column prop="auditor" label="审核人" min-width="90" align="center" />
          <el-table-column prop="certifiedAt" label="认证时间" min-width="160" align="center" />
          <el-table-column label="状态" min-width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'revoked' ? 'danger' : 'success'" size="small">
                {{ row.status === 'revoked' ? '已撤销' : '有效' }}
              </el-tag>
            </template>
          </el-table-column>
          <!-- 撤销原因列：仅在筛选"已撤销"时显示 -->
          <el-table-column v-if="filters.status === 'revoked'" label="撤销原因" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.revokeReason }}</template>
          </el-table-column>
          <el-table-column label="操作" min-width="120" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status !== 'revoked'" text type="danger" size="small" @click="revoke(row)">撤销认证</el-button>
              <el-button v-else text type="primary" size="small" @click="viewRevoke(row)">撤销详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 撤销认证对话框：警示说明 + 认证信息描述 + 撤销原因表单 -->
    <el-dialog v-model="showRevoke" title="撤销认证" width="500px">
      <div v-if="current">
        <!-- 撤销操作不可逆警示 -->
        <el-alert type="warning" :closable="false" style="margin-bottom:16px">
          撤销后该认证证书将失效，电子防伪码仍保留在链上但标记为已撤销，操作不可撤销。
        </el-alert>
        <!-- 当前认证基本信息描述 -->
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="认证编号">{{ current.id }}</el-descriptions-item>
          <el-descriptions-item label="企业">{{ current.enterpriseName }}</el-descriptions-item>
          <el-descriptions-item label="排放量">{{ current.amount }} tCO₂</el-descriptions-item>
        </el-descriptions>
        <!-- 撤销原因填写表单 -->
        <el-form label-width="90px" style="margin-top:16px">
          <el-form-item label="撤销原因">
            <el-select v-model="reason" style="width:100%">
              <el-option label="数据造假" value="数据造假" />
              <el-option label="核算方法错误" value="核算方法错误" />
              <el-option label="排放因子使用不当" value="排放因子使用不当" />
              <el-option label="企业申请撤销" value="企业申请撤销" />
              <el-option label="其他" value="其他" />
            </el-select>
          </el-form-item>
          <el-form-item label="详细说明">
            <el-input v-model="detail" type="textarea" :rows="3" placeholder="请补充说明撤销原因" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="showRevoke = false">取消</el-button>
        <el-button type="danger" @click="confirmRevoke" :loading="submitting">确认撤销</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// 引入 Vue 响应式 API
import { ref, reactive, computed, onMounted } from 'vue'
// 引入 Element Plus 消息提示与弹框组件
import { ElMessage, ElMessageBox } from 'element-plus'
// 引入认证记录接口
import { getCertifications, revokeCertification } from '@/api/certification'

// 筛选条件响应式状态：关键词 + 状态
const filters = reactive({ keyword: '', status: '' })
// 认证记录列表
const certs = ref([])
// 列表加载态
const loading = ref(false)
// 认证记录列表（补全 status 与 revokeReason 字段，避免空值）
const list = computed(() => certs.value.map(c => ({ ...c, status: c.status || 'valid', revokeReason: c.revokeReason || '' })))

// 关键词可匹配的字段：认证编号、企业名称、报告日期、审核人、认证时间
const keywordFields = ['id', 'enterpriseName', 'period', 'auditor', 'certifiedAt']
// 判断一条记录是否命中关键词（大小写不敏感，去掉首尾空格）
function matchKeyword(c) {
  const kw = filters.keyword.trim().toLowerCase()
  if (!kw) return true
  return keywordFields.some(f => String(c[f] || '').toLowerCase().includes(kw))
}

// 经筛选后的认证记录（同时匹配关键词与状态条件）
const filtered = computed(() => list.value.filter(c =>
  matchKeyword(c) &&
  (!filters.status || c.status === filters.status)
))
// 有效认证数量
const validCount = computed(() => list.value.filter(c => c.status === 'valid').length)
// 已撤销认证数量
const revokedCount = computed(() => list.value.filter(c => c.status === 'revoked').length)
// 撤销率：已撤销数量占总数的百分比
const revokeRate = computed(() => list.value.length ? Math.round(revokedCount.value / list.value.length * 100) : 0)

// 顶部统计卡片数据：status 非空表示可点击并按其筛选列表；撤销率为汇总指标，不可点击
const stats = computed(() => [
  { label: '有效认证', value: validCount.value, cls: 'text-success', status: 'valid', clickable: true },
  { label: '已撤销', value: revokedCount.value, cls: 'text-danger', status: 'revoked', clickable: true },
  { label: '撤销率', value: revokeRate.value, unit: '%', cls: '', status: '', clickable: false }
])

// 点击统计卡片：按对应认证状态筛选列表；再次点击同一卡片则取消筛选
function selectStatus(status) {
  filters.status = filters.status === status ? '' : status
}

// 是否显示撤销对话框
const showRevoke = ref(false)
// 当前待撤销的认证记录
const current = ref(null)
// 撤销原因（下拉选择）
const reason = ref('')
// 撤销详细说明（文本域）
const detail = ref('')
// 提交按钮加载态
const submitting = ref(false)

// 拉取认证记录
async function load() {
  loading.value = true
  try {
    certs.value = await getCertifications()
  } finally {
    loading.value = false
  }
}

// 打开撤销对话框：记录当前行并清空原因与说明
function revoke(row) {
  current.value = row
  reason.value = ''
  detail.value = ''
  showRevoke.value = true
}
// 确认撤销：校验原因后调用后端撤销接口，成功后刷新列表
async function confirmRevoke() {
  if (!reason.value) { ElMessage.warning('请选择撤销原因'); return }
  submitting.value = true
  try {
    await revokeCertification(current.value.id, { reason: reason.value, detail: detail.value })
    ElMessage.success('认证已撤销，链上记录已标记')
    showRevoke.value = false
    await load()
  } finally {
    submitting.value = false
  }
}
// 查看撤销详情：弹框展示撤销原因与撤销时间
function viewRevoke(row) {
  ElMessageBox.alert(`撤销原因：${row.revokeReason}\n撤销时间：${row.revokedAt || '-'}`, '撤销详情', { confirmButtonText: '确定' })
}

// 初始化加载
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>

<style scoped>
/* 可点击的统计卡片：手型光标提示可交互 */
.stat-card.clickable { cursor: pointer; }
/* 统计卡片选中态：主题色边框 + 浅绿背景，标识当前筛选状态 */
.stat-card.active {
  border-color: var(--primary);
  background: #f0fdf9;
}
</style>
