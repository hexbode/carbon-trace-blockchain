<!--
  CBAM 申报管理（监管端）
  路由：/regulator/cbam
  功能：查看辖区企业提交的 CBAM 年度申报包、完整性校验结果与核查信息，
        执行确认/退回并导出 JSON 数据包。
  依赖接口：@/api/cbam（申报包列表、详情、导出、确认/退回、统计）、@/api/enterprise（企业名称映射）。
-->
<template>
  <div class="page-container">
    <div class="page-header">
      <h2>CBAM 申报管理</h2>
      <p>对年度 CBAM 申报包进行完整性复核与监管确认，确认后可交付欧盟进口商</p>
    </div>

    <el-row :gutter="16" class="mb-16">
      <el-col :span="6" v-for="s in stats" :key="s.label">
        <div class="stat-card">
          <div class="label">{{ s.label }}</div>
          <div class="value" :class="s.cls">{{ s.value }}<span v-if="s.unit" class="unit">{{ s.unit }}</span></div>
        </div>
      </el-col>
    </el-row>

    <div class="panel">
      <div class="panel-header">
        <div class="title">年度申报包 · {{ filters.year }}</div>
        <div class="actions">
          <el-select v-model="filters.year" style="width: 110px" @change="load">
            <el-option v-for="y in years" :key="y" :label="`${y} 年`" :value="y" />
          </el-select>
          <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 130px">
            <el-option label="草稿" value="draft" />
            <el-option label="待确认" value="submitted" />
            <el-option label="已确认" value="approved" />
            <el-option label="已退回" value="returned" />
          </el-select>
          <el-input v-model="filters.keyword" placeholder="企业名称 / 申报编号" clearable style="width: 220px">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button :loading="reconciling" @click="reconcileNow">立即对账</el-button>
          <el-button @click="openLogs">对账记录</el-button>
        </div>
      </div>
      <div class="panel-body">
        <!-- 手机端卡片 -->
        <div v-if="isMobile" v-loading="loading" class="m-list">
          <el-empty v-if="!filtered.length" :description="emptyHint" :image-size="70" />
          <div v-for="row in filtered" :key="row.id" class="m-card">
            <div class="m-card-head">
              <span class="m-card-title">{{ row.declarationNo }}</span>
              <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
            </div>
            <div class="m-card-sub">{{ enterpriseName(row.enterpriseId) }}</div>
            <div class="m-row"><span class="m-key">年度</span><span class="m-val">{{ row.reportYear }}</span></div>
            <div class="m-row"><span class="m-key">隐含排放</span><span class="m-val">{{ fmt(row.totalEmissions, 3) }} tCO₂e</span></div>
            <div class="m-row"><span class="m-key">完整性</span><span class="m-val">{{ row.completeness === 'complete' ? '通过' : '缺失' }}</span></div>
            <div class="m-row"><span class="m-key">数据一致性</span><span class="m-val">{{ row.dataChanged ? '已变化' : '一致' }}</span></div>
            <div class="m-row"><span class="m-key">链上锚点</span><span class="m-val">{{ row.anchorTxHash ? '已锚定' : '未锚定' }}</span></div>
            <div class="m-card-actions">
              <el-button text type="primary" size="small" @click="openDetail(row)">查看</el-button>
              <el-button v-if="row.status === 'submitted'" text type="success" size="small" @click="openReview(row)">确认/退回</el-button>
              <el-button v-if="row.status === 'approved'" text type="warning" size="small" @click="reopen(row)">退回修改</el-button>
            </div>
          </div>
        </div>

        <!-- 桌面端表格 -->
        <el-table v-else :data="filtered" v-loading="loading" stripe :empty-text="emptyHint">
          <el-table-column prop="declarationNo" label="申报编号" width="200" />
          <el-table-column label="企业" min-width="200">
            <template #default="{ row }">
              <div class="cell-main">{{ enterpriseName(row.enterpriseId) }}</div>
              <div class="cell-sub">{{ row.enterpriseId }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="reportYear" label="年度" width="80" />
          <el-table-column label="货物总量 (t)" width="130" align="right">
            <template #default="{ row }">{{ fmt(row.totalQuantity, 3) }}</template>
          </el-table-column>
          <el-table-column label="隐含排放 (tCO₂e)" width="150" align="right">
            <template #default="{ row }">{{ fmt(row.totalEmissions, 3) }}</template>
          </el-table-column>
          <el-table-column label="完整性" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.completeness === 'complete' ? 'success' : 'warning'">
                {{ row.completeness === 'complete' ? '通过' : '缺失' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="数据一致性" width="120">
            <template #default="{ row }">
              <el-tooltip :content="`实时 ${fmt(row.liveTotalEmissions, 3)} / 快照 ${fmt(row.totalEmissions, 3)} tCO₂e；版本 ${row.dataVersion ?? '-'} → ${row.liveVersion ?? '-'}`">
                <el-tag size="small" :type="row.dataChanged ? 'warning' : 'success'">
                  {{ row.dataChanged ? '已变化' : '一致' }}
                </el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="链上锚点" width="110">
            <template #default="{ row }">
              <el-tooltip v-if="row.anchorTxHash"
                          :content="`${row.anchorTxHash}（区块 ${row.anchorBlock ?? '-'}）`">
                <el-tag size="small" type="success">已锚定</el-tag>
              </el-tooltip>
              <el-tag v-else size="small" type="info">未锚定</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="160">
            <template #default="{ row }">{{ row.updatedAt || '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openDetail(row)">查看</el-button>
              <el-button text size="small" @click="download(row)">导出</el-button>
              <el-button v-if="row.status === 'submitted'" text type="success" size="small" @click="openReview(row)">
                确认/退回
              </el-button>
              <el-button v-if="row.status === 'approved'" text type="warning" size="small" @click="reopen(row)">
                退回修改
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 申报包详情 -->
    <el-dialog v-model="detailVisible" title="CBAM 申报包详情" :width="isMobile ? '94%' : '860px'">
      <template v-if="detail">
        <div class="detail-head">
          <div class="detail-no">{{ detail.declarationNo }}</div>
          <el-tag :type="statusType(detail.status)">{{ statusLabel(detail.status) }}</el-tag>
          <el-tag :type="detail.completeness === 'complete' ? 'success' : 'warning'" effect="plain">
            {{ detail.completeness === 'complete' ? '完整性通过' : '存在缺失项' }}
          </el-tag>
        </div>
        <el-descriptions :column="isMobile ? 1 : 2" border size="small" class="mb-16">
          <el-descriptions-item label="企业">{{ enterpriseName(detail.enterpriseId) }}（{{ detail.enterpriseId }}）</el-descriptions-item>
          <el-descriptions-item label="年度">{{ detail.reportYear }}</el-descriptions-item>
          <el-descriptions-item label="欧盟进口商">{{ detail.importerName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="已付碳价">€ {{ fmt(detail.carbonPrice, 2) }}</el-descriptions-item>
          <el-descriptions-item label="直接排放">{{ fmt(detail.directEmissions, 3) }} tCO₂e</el-descriptions-item>
          <el-descriptions-item label="间接排放">{{ fmt(detail.indirectEmissions, 3) }} tCO₂e</el-descriptions-item>
          <el-descriptions-item label="快照哈希">
            <span class="hash">{{ detail.snapshotHash || '-' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="链上锚点">
            <template v-if="detail.anchorTxHash">
              <el-tag size="small" type="success">已锚定</el-tag>
              区块 {{ detail.anchorBlock ?? '-' }} · {{ detail.anchoredAt || '-' }}
              <div class="hash">{{ detail.anchorTxHash }}</div>
            </template>
            <el-tag v-else size="small" type="info">未锚定</el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="detail.dataChanged" type="warning" :closable="false" show-icon class="mb-16"
                  title="实时排放数据与申报快照不一致"
                  :description="`实时 ${fmt(detail.liveTotalEmissions, 3)} tCO₂e，快照 ${fmt(detail.totalEmissions, 3)} tCO₂e；数据版本 ${detail.dataVersion ?? '-'} → ${detail.liveVersion ?? '-'}。新增/修改的排放记录未纳入快照，请退回修改后由企业重新生成。`" />
        <el-alert v-if="detail.missingItems" type="warning" :closable="false" show-icon
                  title="缺失项" :description="detail.missingItems" class="mb-16" />
        <el-alert v-if="detail.reviewComment" type="info" :closable="false" show-icon
                  :title="'监管意见：' + detail.reviewComment" class="mb-16" />
        <div class="detail-title">申报明细（{{ detailItems.length }}）</div>
        <el-table :data="detailItems" size="small" stripe max-height="320">
          <el-table-column prop="cnCode" label="CN 编码" width="110" />
          <el-table-column prop="goodsName" label="产品" min-width="130" />
          <el-table-column label="装置" min-width="140">
            <template #default="{ row }">{{ row.installation?.name || '-' }}</template>
          </el-table-column>
          <el-table-column label="数量 (t)" width="110" align="right">
            <template #default="{ row }">{{ fmt(row.quantity, 3) }}</template>
          </el-table-column>
          <el-table-column label="隐含排放" width="130" align="right">
            <template #default="{ row }">{{ fmt(row.totalEmissions, 3) }}</template>
          </el-table-column>
          <el-table-column label="核查报告" width="160">
            <template #default="{ row }">
              <template v-if="row.verification">
                <div class="cell-main">{{ row.verification.reportNo }}</div>
                <div class="cell-sub">{{ row.verification.verifierName || '核查机构' }}</div>
              </template>
              <el-tag v-else size="small" type="info">默认值/未核查</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button @click="download(detail)">导出 JSON</el-button>
        <el-button v-if="detail && detail.status === 'submitted'" type="primary" @click="openReview(detail)">
          确认/退回
        </el-button>
        <el-button v-if="detail && detail.status === 'approved'" type="warning" @click="reopen(detail)">
          退回修改
        </el-button>
      </template>
    </el-dialog>

    <!-- 确认/退回 -->
    <el-dialog v-model="reviewVisible" title="申报包确认" :width="isMobile ? '92%' : '520px'">
      <el-form label-width="90px">
        <el-form-item label="处理结果">
          <el-radio-group v-model="reviewForm.approved">
            <el-radio :value="true">确认通过</el-radio>
            <el-radio :value="false">退回整改</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="监管意见">
          <el-input v-model="reviewForm.comment" type="textarea" :rows="3" maxlength="300" show-word-limit
                    :placeholder="reviewForm.approved ? '选填' : '退回时必填'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :loading="reviewing" @click="submitReview">提交</el-button>
      </template>
    </el-dialog>

    <!-- 对账记录（定时任务每小时写入 + 手动触发） -->
    <el-dialog v-model="logsVisible" title="对账记录" :width="isMobile ? '94%' : '900px'">
      <el-table :data="logs" v-loading="logsLoading" size="small" stripe max-height="420">
        <el-table-column prop="checkedAt" label="对账时间" width="170" />
        <el-table-column prop="declarationNo" label="申报包" width="190" />
        <el-table-column prop="reportYear" label="年度" width="70" />
        <el-table-column label="版本" width="110">
          <template #default="{ row }">{{ row.snapshotVersion ?? '-' }} → {{ row.liveVersion ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="快照 / 实时 (tCO₂e)" min-width="180">
          <template #default="{ row }">{{ fmt(row.snapshotTotal, 3) }} / {{ fmt(row.liveTotal, 3) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'consistent' ? 'success' : 'warning'">
              {{ row.status === 'consistent' ? '一致' : '已变化' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="说明" min-width="220" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="logsVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { exportCbamDeclaration, getCbamDeclarations, getCbamReconciliationLogs, getCbamStats, reviewCbamDeclaration, runCbamReconciliation } from '@/api/cbam'
import { getEnterprises } from '@/api/enterprise'
import { useDevice } from '@/composables/useDevice'

const { isMobile } = useDevice()
const currentYear = new Date().getFullYear()
const filters = reactive({ year: currentYear, status: '', keyword: '' })
const years = computed(() => Array.from(new Set([currentYear, currentYear - 1, 2026])).sort((a, b) => b - a))

const declarations = ref([])
const enterprises = ref([])
const statsData = ref({})
const loading = ref(false)

const enterpriseMap = computed(() => Object.fromEntries(enterprises.value.map(e => [e.id, e.name])))
const stats = computed(() => [
  { label: '申报包总数', value: statsData.value.declarations ?? declarations.value.length, cls: '' },
  { label: '待监管确认', value: statsData.value.submitted ?? 0, cls: 'text-warning' },
  { label: '已确认', value: statsData.value.approved ?? 0, cls: 'text-success' },
  { label: '数据已变化', value: statsData.value.stale ?? 0, cls: 'text-warning' }
])

const filtered = computed(() => {
  const kw = filters.keyword.trim().toLowerCase()
  return declarations.value.filter(d => {
    if (filters.status && d.status !== filters.status) return false
    if (!kw) return true
    return String(d.declarationNo || '').toLowerCase().includes(kw)
      || String(enterpriseMap.value[d.enterpriseId] || '').toLowerCase().includes(kw)
  })
})

// 空列表提示：明确“年度 + 状态”两个隔离条件，避免误以为企业端未申报
const emptyHint = computed(() => {
  const status = filters.status ? statusLabel(filters.status) : '全部状态'
  return `${filters.year} 年 · ${status}下暂无申报包；申报包按年度隔离，请切换年度或状态查看`
})

function enterpriseName(id) { return enterpriseMap.value[id] || id || '-' }
function fmt(value, digits = 3) {
  if (value === null || value === undefined || value === '') return '-'
  return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}
function statusLabel(status) {
  return { draft: '草稿', submitted: '待确认', approved: '已确认', returned: '已退回' }[status] || status || '-'
}
function statusType(status) {
  return { draft: 'info', submitted: 'warning', approved: 'success', returned: 'danger' }[status] || 'info'
}

async function load() {
  loading.value = true
  try {
    const [list, ents, statsRes] = await Promise.all([
      getCbamDeclarations({ year: filters.year }),
      getEnterprises(),
      getCbamStats({ year: filters.year })
    ])
    declarations.value = list || []
    enterprises.value = ents || []
    statsData.value = statsRes || {}
  } finally {
    loading.value = false
  }
}

// ==================== 详情与导出 ====================
const detailVisible = ref(false)
const detail = ref(null)
const detailItems = computed(() => {
  try {
    return JSON.parse(detail.value?.packageJson || '{}').items || []
  } catch (e) {
    return []
  }
})

function openDetail(row) {
  detail.value = row
  detailVisible.value = true
}

async function download(row) {
  try {
    const blob = await exportCbamDeclaration(row.id)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${row.declarationNo}.json`
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error('导出失败')
  }
}

// ==================== 确认/退回 ====================
const reviewVisible = ref(false)
const reviewing = ref(false)
const reviewForm = reactive({ id: null, approved: true, comment: '' })

function openReview(row) {
  reviewForm.id = row.id
  reviewForm.approved = true
  reviewForm.comment = ''
  reviewVisible.value = true
}

async function submitReview() {
  if (!reviewForm.approved && !reviewForm.comment.trim()) {
    ElMessage.warning('退回时必须填写监管意见')
    return
  }
  reviewing.value = true
  try {
    await reviewCbamDeclaration(reviewForm.id, {
      approved: reviewForm.approved,
      comment: reviewForm.comment
    })
    ElMessage.success(reviewForm.approved ? '申报包已确认' : '申报包已退回')
    reviewVisible.value = false
    detailVisible.value = false
    await load()
  } catch (e) { /* 拦截器已提示 */ } finally {
    reviewing.value = false
  }
}

// 已确认申报包退回修改：企业端补充新数据后可重新生成并再次提交
async function reopen(row) {
  try {
    const { value } = await ElMessageBox.prompt('请填写退回原因，企业端将据此重新生成申报包', '退回修改', {
      inputType: 'textarea',
      inputPlaceholder: '如：新增了产品排放记录，需要重新生成申报包',
      inputValidator: (v) => (v && v.trim() ? true : '必须填写退回原因')
    })
    await reviewCbamDeclaration(row.id, { approved: false, comment: value })
    ElMessage.success('已退回，企业端可重新生成申报包')
    detailVisible.value = false
    await load()
  } catch (e) { /* 取消或接口错误 */ }
}

// ==================== 对账 ====================
const reconciling = ref(false)
const logsVisible = ref(false)
const logsLoading = ref(false)
const logs = ref([])

async function reconcileNow() {
  reconciling.value = true
  try {
    const result = await runCbamReconciliation()
    ElMessage.success(`对账完成：一致 ${result.consistent}，异常 ${result.stale}`)
    await load()
  } catch (e) { /* 拦截器已提示 */ } finally {
    reconciling.value = false
  }
}

async function openLogs() {
  logsVisible.value = true
  logsLoading.value = true
  try {
    logs.value = await getCbamReconciliationLogs({ year: filters.year, limit: 50 }) || []
  } catch (e) {
    logs.value = []
  } finally {
    logsLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.panel-header .actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.cell-main { font-weight: 600; color: #0f172a; }
.cell-sub { font-size: 12px; color: #94a3b8; }
.text-warning { color: #f59e0b; }
.detail-head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.detail-no { font-weight: 700; font-size: 16px; color: #0f172a; }
.detail-title { font-weight: 600; margin-bottom: 8px; color: #0f172a; }
.hash { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; color: #475569; word-break: break-all; }
@media (max-width: 767px) {
  .panel-header .actions { width: 100%; }
}
</style>
