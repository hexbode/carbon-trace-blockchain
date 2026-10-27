<!--
  CBAM 合规申报（企业端）
  路由：/enterprise/cbam
  功能：维护本企业生产装置与产品级隐含排放（年度 × 产品 × 装置），
        按年度生成面向欧盟进口商的 CBAM 申报数据包，支持完整性校验、JSON 导出与提交监管确认。
  依赖接口：@/api/cbam（产品目录、装置、产品排放、申报包）。
-->
<template>
  <div class="page-container">
    <div class="page-header">
      <h2>CBAM 合规申报</h2>
      <p>按 CBAM 正式期口径登记装置与产品级隐含排放，生成可交付欧盟进口商的年度申报数据包</p>
    </div>

    <!-- 顶部统计 -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="6" v-for="s in stats" :key="s.label">
        <div class="stat-card">
          <div class="label">{{ s.label }}</div>
          <div class="value" :class="s.cls">{{ s.value }}<span v-if="s.unit" class="unit">{{ s.unit }}</span></div>
        </div>
      </el-col>
    </el-row>

    <!-- 产品级隐含排放 -->
    <div class="panel mb-16">
      <div class="panel-header">
        <div class="title">产品级隐含排放 · {{ filters.year }} 年度</div>
        <div class="actions">
          <el-select v-model="filters.year" style="width: 110px" @change="load">
            <el-option v-for="y in years" :key="y" :label="`${y} 年`" :value="y" />
          </el-select>
          <el-button @click="openInstallations">装置管理</el-button>
          <el-button type="primary" @click="openCreate">新增产品排放</el-button>
        </div>
      </div>
      <div class="panel-body">
        <!-- 手机端卡片列表 -->
        <div v-if="isMobile" v-loading="loading" class="m-list">
          <el-empty v-if="!emissions.length" description="本年度暂无产品排放记录" :image-size="70" />
          <div v-for="row in emissions" :key="row.id" class="m-card">
            <div class="m-card-head">
              <span class="m-card-title">{{ goodsName(row.goodsId) }}</span>
              <el-tag size="small" :type="row.dataMethod === 'actual' ? 'success' : 'info'">
                {{ row.dataMethod === 'actual' ? '实测' : '默认值' }}
              </el-tag>
            </div>
            <div class="m-card-sub">{{ installationName(row.installationId) }}</div>
            <div class="m-row"><span class="m-key">产量</span><span class="m-val">{{ fmt(row.quantity, 3) }} t</span></div>
            <div class="m-row"><span class="m-key">直接排放</span><span class="m-val">{{ fmt(row.directEmissions, 3) }} tCO₂e</span></div>
            <div class="m-row"><span class="m-key">间接排放</span><span class="m-val">{{ fmt(row.indirectEmissions, 3) }} tCO₂e</span></div>
            <div class="m-card-actions">
              <el-button text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
            </div>
          </div>
        </div>

        <!-- 桌面端表格 -->
        <el-table v-else :data="emissions" v-loading="loading" stripe>
          <el-table-column label="CBAM 产品" min-width="180">
            <template #default="{ row }">
              <div class="cell-main">{{ goodsName(row.goodsId) }}</div>
              <div class="cell-sub">{{ goodsCnCode(row.goodsId) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="生产装置" min-width="170">
            <template #default="{ row }">
              <div class="cell-main">{{ installationName(row.installationId) }}</div>
              <div class="cell-sub">{{ installationRoute(row.installationId) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="数据方式" width="96">
            <template #default="{ row }">
              <el-tag size="small" :type="row.dataMethod === 'actual' ? 'success' : 'info'">
                {{ row.dataMethod === 'actual' ? '实测' : '默认值' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="产量 (t)" width="110" align="right">
            <template #default="{ row }">{{ fmt(row.quantity, 3) }}</template>
          </el-table-column>
          <el-table-column label="直接强度" width="100" align="right">
            <template #default="{ row }">{{ fmt(row.directIntensity, 4) }}</template>
          </el-table-column>
          <el-table-column label="直接排放 (tCO₂e)" width="150" align="right">
            <template #default="{ row }">{{ fmt(row.directEmissions, 3) }}</template>
          </el-table-column>
          <el-table-column label="间接排放 (tCO₂e)" width="150" align="right">
            <template #default="{ row }">{{ fmt(row.indirectEmissions, 3) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 年度申报包 -->
    <div class="panel">
      <div class="panel-header">
        <div class="title">年度申报包 · {{ filters.year }}</div>
        <div class="actions">
          <el-input v-model="importerName" placeholder="欧盟进口商（选填）" style="width: 200px" clearable />
          <el-button :loading="generating"
                     :disabled="declaration && ['submitted', 'approved'].includes(declaration.status)"
                     @click="onGenerate">
            {{ declaration && ['submitted', 'approved'].includes(declaration.status)
              ? '已提交，不可重新生成'
              : (declaration ? '重新生成' : '生成申报包') }}
          </el-button>
        </div>
      </div>
      <div class="panel-body">
        <el-empty v-if="!declaration" description="本年度尚未生成申报包" :image-size="70" />
        <template v-else>
          <div class="decl-head">
            <div class="decl-no">{{ declaration.declarationNo }}</div>
            <el-tag :type="statusType(declaration.status)" effect="light">{{ statusLabel(declaration.status) }}</el-tag>
            <el-tag :type="declaration.completeness === 'complete' ? 'success' : 'warning'" effect="plain">
              {{ declaration.completeness === 'complete' ? '完整性通过' : '存在缺失项' }}
            </el-tag>
          </div>
          <el-row :gutter="16" class="decl-totals">
            <el-col :span="6"><div class="kv"><span>货物总量</span><b>{{ fmt(declaration.totalQuantity, 3) }} t</b></div></el-col>
            <el-col :span="6"><div class="kv"><span>直接排放</span><b>{{ fmt(declaration.directEmissions, 3) }} tCO₂e</b></div></el-col>
            <el-col :span="6"><div class="kv"><span>间接排放</span><b>{{ fmt(declaration.indirectEmissions, 3) }} tCO₂e</b></div></el-col>
            <el-col :span="6"><div class="kv"><span>已付碳价</span><b>€ {{ fmt(declaration.carbonPrice, 2) }}</b></div></el-col>
          </el-row>
          <el-alert v-if="declaration.missingItems" type="warning" :closable="false" show-icon
                    title="申报包缺失项（补齐后可重新生成）" :description="declaration.missingItems" />
          <el-alert v-if="dataChanged" type="warning" :closable="false" show-icon
                    title="当前排放数据已变化，申报包仍是提交时的快照"
                    description="已提交/已确认的申报包不可直接覆盖；需监管端「退回修改」后重新生成，再重新提交。" />
          <el-alert v-if="declaration.reviewComment" type="info" :closable="false" show-icon
                    :title="'监管意见：' + declaration.reviewComment" />
          <div class="decl-actions">
            <el-button @click="download">导出 JSON 数据包</el-button>
            <el-button type="primary" :disabled="declaration.completeness !== 'complete'
                || !['draft', 'returned'].includes(declaration.status)"
                       @click="submit">提交监管确认</el-button>
            <span class="deadline">申报窗口：次年 9 月 30 日前（2026 年度截止 2027-09-30）</span>
          </div>
        </template>
      </div>
    </div>

    <!-- 新增/编辑产品排放 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑产品排放' : '新增产品排放'"
               :width="isMobile ? '94%' : '620px'">
      <el-form label-width="120px">
        <el-form-item label="CBAM 产品" required>
          <el-select v-model="form.goodsId" placeholder="选择 CN 编码产品" style="width: 100%" @change="onGoodsChange">
            <el-option v-for="g in goods" :key="g.id" :label="`${g.cnCode} · ${g.name}`" :value="g.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="生产装置" required>
          <el-select v-model="form.installationId" placeholder="选择生产装置" style="width: 100%">
            <el-option v-for="i in installations" :key="i.id" :label="i.name" :value="i.id" />
          </el-select>
          <div v-if="!installations.length" class="form-hint">尚未登记装置，请先点击「装置管理」新增</div>
        </el-form-item>
        <el-form-item label="年度产量 (t)" required>
          <el-input-number v-model="form.quantity" :min="0" :precision="3" :step="100" style="width: 100%" />
        </el-form-item>
        <el-form-item label="数据方式">
          <el-radio-group v-model="form.dataMethod" @change="onMethodChange">
            <el-radio value="actual">实测数据</el-radio>
            <el-radio value="default">默认值</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="直接强度">
          <el-input-number v-model="form.directIntensity" :min="0" :precision="4" :step="0.1"
                           :disabled="form.dataMethod === 'default'" style="width: 100%" />
          <div class="form-hint">tCO₂e / t 产品；默认值方式自动取产品目录示例值</div>
        </el-form-item>
        <el-form-item label="间接强度">
          <el-input-number v-model="form.indirectIntensity" :min="0" :precision="4" :step="0.1"
                           :disabled="form.dataMethod === 'default'" style="width: 100%" />
        </el-form-item>
        <el-form-item label="已付碳价 (€)">
          <el-input-number v-model="form.carbonPrice" :min="0" :precision="2" :step="100" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="300" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 装置管理 -->
    <el-dialog v-model="installDialog" title="生产装置管理" :width="isMobile ? '94%' : '640px'">
      <el-form :inline="!isMobile" label-width="90px">
        <el-form-item label="装置名称">
          <el-input v-model="instForm.name" placeholder="如：一号生产装置" style="width: 190px" />
        </el-form-item>
        <el-form-item label="工艺路线">
          <el-input v-model="instForm.productionRoute" placeholder="如 BF-BOF / EAF" style="width: 150px" />
        </el-form-item>
        <el-form-item label="所在国家">
          <el-input v-model="instForm.country" style="width: 90px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="instSaving" @click="saveInstallation">
            {{ instForm.id ? '保存修改' : '新增装置' }}
          </el-button>
          <el-button v-if="instForm.id" @click="resetInstForm">取消编辑</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="installations" size="small" stripe>
        <el-table-column prop="name" label="装置名称" min-width="150" />
        <el-table-column prop="productionRoute" label="工艺路线" width="120" />
        <el-table-column prop="country" label="国家" width="70" />
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="editInstallation(row)">编辑</el-button>
            <el-button text type="danger" size="small" @click="removeInstallation(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createCbamEmission,
  createCbamInstallation,
  deleteCbamEmission,
  deleteCbamInstallation,
  exportCbamDeclaration,
  generateCbamDeclaration,
  getCbamDeclarations,
  getCbamEmissions,
  getCbamGoods,
  getCbamInstallations,
  submitCbamDeclaration,
  updateCbamEmission,
  updateCbamInstallation
} from '@/api/cbam'
import { useDevice } from '@/composables/useDevice'

const { isMobile } = useDevice()
const currentYear = new Date().getFullYear()

const filters = reactive({ year: currentYear })
const years = computed(() => Array.from(new Set([currentYear, currentYear - 1, 2026])).sort((a, b) => b - a))

const goods = ref([])
const installations = ref([])
const emissions = ref([])
const declaration = ref(null)
const importerName = ref('')
const loading = ref(false)
const generating = ref(false)
const saving = ref(false)

const goodsMap = computed(() => Object.fromEntries(goods.value.map(g => [g.id, g])))
const installMap = computed(() => Object.fromEntries(installations.value.map(i => [i.id, i])))

const stats = computed(() => {
  const direct = emissions.value.reduce((sum, e) => sum + Number(e.directEmissions || 0), 0)
  const indirect = emissions.value.reduce((sum, e) => sum + Number(e.indirectEmissions || 0), 0)
  return [
    { label: '产品排放记录', value: emissions.value.length, cls: '' },
    { label: '直接隐含排放', value: fmt(direct, 1), unit: 'tCO₂e', cls: '' },
    { label: '间接隐含排放', value: fmt(indirect, 1), unit: 'tCO₂e', cls: '' },
    { label: '申报包状态', value: declaration.value ? statusLabel(declaration.value.status) : '未生成', cls: declaration.value?.status === 'approved' ? 'text-success' : '' }
  ]
})

// 当前排放合计（直接 + 间接），用于判断提交后的数据是否已变化
const currentTotal = computed(() =>
  emissions.value.reduce((sum, e) => sum + Number(e.directEmissions || 0) + Number(e.indirectEmissions || 0), 0))
// 已提交/已确认的申报包是冻结快照；数据变化时提示需监管端退回后重新生成
const dataChanged = computed(() => {
  const d = declaration.value
  if (!d || !['submitted', 'approved'].includes(d.status)) return false
  return Math.abs(currentTotal.value - Number(d.totalEmissions || 0)) > 0.001
})

function goodsName(id) { return goodsMap.value[id]?.name || `产品 #${id}` }
function goodsCnCode(id) { return goodsMap.value[id]?.cnCode || '-' }
function installationName(id) { return installMap.value[id]?.name || `装置 #${id}` }
function installationRoute(id) { return installMap.value[id]?.productionRoute || '-' }
function fmt(value, digits = 3) {
  if (value === null || value === undefined || value === '') return '-'
  return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}
function statusLabel(status) {
  return { draft: '草稿', submitted: '待监管确认', approved: '已确认', returned: '已退回' }[status] || status || '-'
}
function statusType(status) {
  return { draft: 'info', submitted: 'warning', approved: 'success', returned: 'danger' }[status] || 'info'
}

async function load() {
  loading.value = true
  try {
    const [g, inst, em, decls] = await Promise.all([
      getCbamGoods(),
      getCbamInstallations(),
      getCbamEmissions({ year: filters.year }),
      getCbamDeclarations({ year: filters.year })
    ])
    goods.value = g || []
    installations.value = inst || []
    emissions.value = em || []
    declaration.value = (decls && decls.length) ? decls[0] : null
    if (declaration.value?.importerName) importerName.value = declaration.value.importerName
  } finally {
    loading.value = false
  }
}

// ==================== 产品排放 ====================
const dialogVisible = ref(false)
const form = reactive({
  id: null, goodsId: null, installationId: null, quantity: 0,
  dataMethod: 'actual', directIntensity: 0, indirectIntensity: 0, carbonPrice: 0, remark: ''
})

function openCreate() {
  if (!installations.value.length) {
    ElMessage.warning('请先在「装置管理」中登记生产装置')
    return
  }
  Object.assign(form, {
    id: null, goodsId: null, installationId: installations.value[0].id, quantity: 0,
    dataMethod: 'actual', directIntensity: 0, indirectIntensity: 0, carbonPrice: 0, remark: ''
  })
  dialogVisible.value = true
}
function openEdit(row) {
  Object.assign(form, {
    id: row.id, goodsId: row.goodsId, installationId: row.installationId, quantity: Number(row.quantity || 0),
    dataMethod: row.dataMethod || 'actual', directIntensity: Number(row.directIntensity || 0),
    indirectIntensity: Number(row.indirectIntensity || 0), carbonPrice: Number(row.carbonPrice || 0),
    remark: row.remark || ''
  })
  dialogVisible.value = true
}
function onGoodsChange() { if (form.dataMethod === 'default') applyDefaults() }
function onMethodChange() { if (form.dataMethod === 'default') applyDefaults() }
function applyDefaults() {
  const g = goodsMap.value[form.goodsId]
  if (!g) return
  form.directIntensity = Number(g.defaultDirect || 0)
  form.indirectIntensity = Number(g.defaultIndirect || 0)
}

async function save() {
  if (!form.goodsId || !form.installationId) { ElMessage.warning('请选择 CBAM 产品与生产装置'); return }
  if (form.quantity === null || form.quantity < 0) { ElMessage.warning('年度产量不能为负'); return }
  saving.value = true
  try {
    const payload = {
      reportYear: filters.year,
      goodsId: form.goodsId,
      installationId: form.installationId,
      quantity: form.quantity,
      dataMethod: form.dataMethod,
      directIntensity: form.directIntensity,
      indirectIntensity: form.indirectIntensity,
      carbonPrice: form.carbonPrice,
      remark: form.remark
    }
    if (form.id) await updateCbamEmission(form.id, payload)
    else await createCbamEmission(payload)
    ElMessage.success('产品排放已保存')
    dialogVisible.value = false
    await load()
  } catch (e) {
    // 错误提示由 request 拦截器统一弹出
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  await ElMessageBox.confirm(`确认删除「${goodsName(row.goodsId)}」的排放记录？`, '删除确认', { type: 'warning' })
  try {
    await deleteCbamEmission(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) { /* 拦截器已提示 */ }
}

// ==================== 装置管理 ====================
const installDialog = ref(false)
const instSaving = ref(false)
const instForm = reactive({ id: null, name: '', country: 'CN', productionRoute: '', address: '' })

function openInstallations() {
  resetInstForm()
  installDialog.value = true
}
function resetInstForm() {
  Object.assign(instForm, { id: null, name: '', country: 'CN', productionRoute: '', address: '' })
}
function editInstallation(row) {
  Object.assign(instForm, {
    id: row.id, name: row.name, country: row.country || 'CN',
    productionRoute: row.productionRoute || '', address: row.address || ''
  })
}
async function saveInstallation() {
  if (!instForm.name.trim()) { ElMessage.warning('装置名称不能为空'); return }
  instSaving.value = true
  try {
    const payload = {
      name: instForm.name, country: instForm.country,
      productionRoute: instForm.productionRoute, address: instForm.address
    }
    if (instForm.id) await updateCbamInstallation(instForm.id, payload)
    else await createCbamInstallation(payload)
    ElMessage.success('装置已保存')
    resetInstForm()
    await load()
  } catch (e) { /* 拦截器已提示 */ } finally {
    instSaving.value = false
  }
}
async function removeInstallation(row) {
  await ElMessageBox.confirm(`确认删除装置「${row.name}」？`, '删除确认', { type: 'warning' })
  try {
    await deleteCbamInstallation(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) { /* 拦截器已提示 */ }
}

// ==================== 申报包 ====================
async function onGenerate() {
  if (!emissions.value.length) { ElMessage.warning('本年度暂无产品排放数据，无法生成申报包'); return }
  if (declaration.value && ['submitted', 'approved'].includes(declaration.value.status)) {
    ElMessage.warning('申报包已提交或已通过，不能重新生成')
    return
  }
  generating.value = true
  try {
    declaration.value = await generateCbamDeclaration({
      year: filters.year,
      importerName: importerName.value
    })
    if (declaration.value.completeness === 'complete') {
      ElMessage.success('申报包已生成，完整性校验通过')
    } else {
      ElMessage.warning('申报包已生成，但存在缺失项，请补齐后重新生成')
    }
  } catch (e) { /* 拦截器已提示 */ } finally {
    generating.value = false
  }
}

async function submit() {
  await ElMessageBox.confirm('提交后监管端将进行确认，确认前不可重新生成申报包。', '提交申报包', { type: 'warning' })
  try {
    declaration.value = await submitCbamDeclaration(declaration.value.id)
    ElMessage.success('已提交，等待监管确认')
    await load()
  } catch (e) { /* 拦截器已提示 */ }
}

async function download() {
  try {
    const blob = await exportCbamDeclaration(declaration.value.id)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${declaration.value.declarationNo}.json`
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error('导出失败')
  }
}

onMounted(load)
</script>

<style scoped>
.panel-header .actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.cell-main { font-weight: 600; color: #0f172a; }
.cell-sub { font-size: 12px; color: #94a3b8; }
.form-hint { font-size: 12px; color: #94a3b8; line-height: 1.5; margin-top: 2px; }
.decl-head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.decl-no { font-weight: 700; font-size: 16px; color: #0f172a; }
.decl-totals { margin-bottom: 14px; }
.kv { background: #f8fafc; border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; }
.kv span { font-size: 12px; color: #64748b; }
.kv b { font-size: 15px; color: #0f172a; }
.decl-actions { display: flex; align-items: center; gap: 10px; margin-top: 14px; flex-wrap: wrap; }
.deadline { font-size: 12px; color: #94a3b8; }
@media (max-width: 767px) {
  .panel-header .actions { width: 100%; }
  .decl-totals .el-col { max-width: 50%; flex: 0 0 50%; margin-bottom: 8px; }
  .decl-actions { flex-direction: column; align-items: stretch; }
}
</style>
