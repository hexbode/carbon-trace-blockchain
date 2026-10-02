<!--
  文件：AuditHistory.vue
  用途：审核端 - 审核历史页面
  功能：
    1. 展示本机构已完成的审核记录（已认证 + 已驳回）
    2. 支持按审核结果（通过/驳回）和关键词（企业名称/报告编号）筛选
    3. 点击详情按钮通过抽屉展示单条审核记录的完整信息
-->
<template>
  <div class="page-container">
    <!-- 页面头部：标题与功能说明 -->
    <div class="page-header">
      <h2>审核历史</h2>
      <p>查看本机构已完成的审核记录，支持按时间与结果筛选</p>
    </div>

    <div class="panel">
      <!-- 筛选表单：审核结果下拉 + 关键词输入 -->
      <div class="panel-body" style="padding: 16px 20px 0">
        <el-form :inline="true" :model="filters">
          <!-- 审核结果筛选：通过 / 驳回 / 全部 -->
          <el-form-item label="审核结果">
            <el-select v-model="filters.result" placeholder="全部" clearable style="width:140px">
              <el-option label="通过" value="certified" />
              <el-option label="驳回" value="rejected" />
            </el-select>
          </el-form-item>
          <!-- 关键词筛选：匹配企业名称或报告编号 -->
          <el-form-item label="关键词">
            <el-input v-model="filters.keyword" placeholder="企业/报告编号" clearable style="width:200px" />
          </el-form-item>
        </el-form>
      </div>
      <!-- 审核历史列表表格 -->
      <div style="padding: 0 20px 20px">
        <el-table :data="filtered" stripe v-loading="loading">
          <el-table-column prop="id" label="报告编号" min-width="120" align="center" />
          <el-table-column prop="enterpriseName" label="企业" min-width="200" show-overflow-tooltip align="center" />
          <el-table-column prop="period" label="报告日期" min-width="130" align="center" />
          <el-table-column label="排放量" min-width="120">
            <template #default="{ row }">{{ row.amount }}tCO₂</template>
          </el-table-column>
          <el-table-column prop="auditor" label="审核人" min-width="90" align="center" />
          <el-table-column prop="auditedAt" label="审核时间" min-width="170" align="center" />
          <!-- 结果列：通过显示绿色标签，驳回显示红色标签 -->
          <el-table-column label="结果" min-width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'certified' ? 'success' : 'danger'" size="small">
                {{ row.status === 'certified' ? '通过' : '驳回' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="审核意见" min-width="200" show-overflow-tooltip align="center">
            <template #default="{ row }">{{ row.auditOpinion }}</template>
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

    <!-- 审核记录详情弹框：居中显示，展示单条记录的完整审核信息 -->
    <el-dialog v-model="showDetail" title="审核记录详情" width="620px">
      <el-descriptions :column="1" border v-if="current">
        <el-descriptions-item label="报告编号">{{ current.id }}</el-descriptions-item>
        <el-descriptions-item label="企业">{{ current.enterpriseName }}</el-descriptions-item>
        <el-descriptions-item label="报告日期">{{ current.period }}</el-descriptions-item>
        <el-descriptions-item label="排放量">{{ current.amount }} tCO₂</el-descriptions-item>
        <el-descriptions-item label="电子防伪码"><span class="hash-text">{{ current.fingerprint }}</span></el-descriptions-item>
        <el-descriptions-item label="审核人">{{ current.auditor }}</el-descriptions-item>
        <el-descriptions-item label="审核时间">{{ current.auditedAt }}</el-descriptions-item>
        <!-- 审核结果：通过/驳回标签 -->
        <el-descriptions-item label="审核结果">
          <el-tag :type="current.status === 'certified' ? 'success' : 'danger'" size="small">
            {{ current.status === 'certified' ? '通过' : '驳回' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="审核意见">{{ current.auditOpinion }}</el-descriptions-item>
      </el-descriptions>
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
// 引入报告记录接口
import { getReportRecords } from '@/api/report'

// 筛选条件：result 审核结果、keyword 关键词
const filters = reactive({ result: '', keyword: '' })
// 报告记录原始列表
const records = ref([])
// 列表加载态
const loading = ref(false)
// 已审核列表：筛选出已认证或已驳回的报告（排除待审核）
const audited = computed(() => records.value.filter(r => r.status === 'certified' || r.status === 'rejected'))

// 最终展示列表：在已审核列表基础上应用筛选条件
// - result 为空时展示全部结果
// - keyword 匹配企业名称或报告编号
const filtered = computed(() => audited.value.filter(r =>
  (!filters.result || r.status === filters.result) &&
  (!filters.keyword || r.enterpriseName.includes(filters.keyword) || r.id.includes(filters.keyword))
))

// 详情抽屉显隐控制
const showDetail = ref(false)
// 当前查看的审核记录
const current = ref(null)
// 打开详情抽屉：设置当前记录并显示抽屉
function view(row) { current.value = row; showDetail.value = true }

// 拉取报告记录
async function load() {
  loading.value = true
  try {
    records.value = await getReportRecords({})
  } finally {
    loading.value = false
  }
}
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>
