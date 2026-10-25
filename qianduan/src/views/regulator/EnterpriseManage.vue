<!--
  EnterpriseManage.vue —— 企业管理页（监管端）
  用途：统一维护「企业端 / 审核端 / 监管端」三类单位。
  顶部通过类型切换列表数据源（enterprises / audit_organizations / regulator_organizations）；
  新增/编辑弹窗内同样用类型三选一决定表单字段与写入的目标表；
  支持按名称（企业端还可按行业）筛选，以及详情、编辑、删除。
-->
<template>
  <div class="page-container">
    <!-- 页面标题区：标题说明 + 新增按钮（按钮文案随类型变化） -->
    <div class="page-header flex-between">
      <div>
        <h2>企业管理</h2>
        <p>统一维护企业端、审核端、监管端三类单位信息</p>
      </div>
      <el-button type="primary" @click="openForm()">
        <el-icon><Plus /></el-icon> 新增{{ currentType.unit }}
      </el-button>
    </div>

    <!-- 筛选区：类型切换 + 名称（企业端额外支持按行业过滤） -->
    <div class="panel mb-16">
      <div class="panel-body">
        <el-form :inline="true" :model="filters">
          <el-form-item label="类型">
            <el-radio-group v-model="activeType" @change="onTypeChange">
              <el-radio-button v-for="t in TYPE_OPTIONS" :key="t.value" :value="t.value">
                {{ t.label }}
              </el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="名称">
            <el-input v-model="filters.name" placeholder="请输入" clearable style="width: 200px" />
          </el-form-item>
          <el-form-item v-if="activeType === 'enterprise'" label="行业">
            <el-select v-model="filters.industry" placeholder="全部" clearable style="width: 140px">
              <el-option v-for="i in industries" :key="i" :label="i" :value="i" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <!-- 单位列表：公共列（编号/名称/联系人/电话/时间/状态/操作）+ 各类型专属列 -->
    <div class="panel">
      <div class="panel-body" style="padding: 0">
        <!-- 手机端卡片列表 -->
        <div v-if="isMobile" v-loading="loading" class="m-list" style="padding: 12px">
          <el-empty v-if="!filteredList.length" description="暂无数据" :image-size="70" />
          <div v-for="row in filteredList" :key="row.id" class="m-card">
            <div class="m-card-head">
              <span class="m-card-title">{{ row.name }}</span>
              <el-tag type="success" size="small">正常</el-tag>
            </div>
            <div class="m-card-sub">{{ currentType.idLabel }}：{{ row.id }}</div>
            <!-- 按当前类型展示对应字段 -->
            <template v-if="activeType === 'enterprise'">
              <div class="m-row"><span class="m-key">行业</span><span class="m-val">{{ row.industry || '-' }}</span></div>
              <div class="m-row"><span class="m-key">统一社会信用代码</span><span class="m-val">{{ row.creditCode || '-' }}</span></div>
            </template>
            <template v-else-if="activeType === 'auditor'">
              <div class="m-row"><span class="m-key">资质证书号</span><span class="m-val">{{ row.qualification || '-' }}</span></div>
              <div class="m-row"><span class="m-key">业务范围</span><span class="m-val">{{ row.scope || '-' }}</span></div>
            </template>
            <template v-else>
              <div class="m-row"><span class="m-key">所属区域</span><span class="m-val">{{ row.region || '-' }}</span></div>
              <div class="m-row"><span class="m-key">监管职责</span><span class="m-val">{{ row.scope || '-' }}</span></div>
            </template>
            <div class="m-row"><span class="m-key">联系人</span><span class="m-val">{{ row.contact || '-' }}</span></div>
            <div class="m-row"><span class="m-key">联系电话</span><span class="m-val">{{ row.phone || '-' }}</span></div>
            <div class="m-row"><span class="m-key">注册时间</span><span class="m-val">{{ row.registeredAt || row.createdAt || '-' }}</span></div>
            <div class="m-card-actions">
              <el-button text type="primary" size="small" @click="viewDetail(row)">详情</el-button>
              <el-button text type="primary" size="small" @click="openForm(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
            </div>
          </div>
        </div>

        <el-table v-else :data="filteredList" stripe v-loading="loading">
          <el-table-column prop="id" :label="currentType.idLabel" min-width="120" align="center" />
          <el-table-column prop="name" :label="currentType.nameLabel" min-width="180" show-overflow-tooltip align="center" />
          <!-- 企业端专属列 -->
          <el-table-column v-if="activeType === 'enterprise'" prop="industry" label="行业" min-width="90" align="center" />
          <el-table-column v-if="activeType === 'enterprise'" prop="creditCode" label="统一社会信用代码" min-width="180" align="center" />
          <!-- 审核端专属列 -->
          <el-table-column v-if="activeType === 'auditor'" prop="qualification" label="资质证书号" min-width="140" align="center" />
          <el-table-column v-if="activeType === 'auditor'" prop="scope" label="业务范围" min-width="180" show-overflow-tooltip align="center" />
          <!-- 监管端专属列 -->
          <el-table-column v-if="activeType === 'regulator'" prop="region" label="所属区域" min-width="110" align="center" />
          <el-table-column v-if="activeType === 'regulator'" prop="scope" label="监管职责" min-width="180" show-overflow-tooltip align="center" />
          <el-table-column prop="contact" label="联系人" min-width="100" align="center" />
          <el-table-column prop="phone" label="联系电话" min-width="140" align="center" />
          <el-table-column label="注册时间" min-width="120" align="center">
            <template #default="{ row }">{{ row.registeredAt || row.createdAt || '-' }}</template>
          </el-table-column>
          <el-table-column label="状态" min-width="90" align="center">
            <template #default>
              <el-tag type="success" size="small">正常</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="160" fixed="right" align="center">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="viewDetail(row)">详情</el-button>
              <el-button text type="primary" size="small" @click="openForm(row)">编辑</el-button>
              <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 新增/编辑弹窗：类型三选一，字段随类型切换，写入对应的表 -->
    <el-dialog v-model="showForm" :title="(form.id ? '编辑' : '新增') + formUnit" width="600px">
      <!-- 内容区：手机端限制高度并允许滚动，保证长表单可完整填写 -->
      <div :style="isMobile ? { maxHeight: '58vh', overflowY: 'auto', paddingRight: '4px' } : null">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="130px">
        <el-form-item label="类型">
          <!-- 编辑时不允许改类型，避免记录跨表迁移 -->
          <el-radio-group v-model="form.type" :disabled="!!form.id">
            <el-radio-button v-for="t in TYPE_OPTIONS" :key="t.value" :value="t.value">
              {{ t.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <!-- 企业端字段（写入 enterprises） -->
        <template v-if="form.type === 'enterprise'">
          <el-form-item label="企业名称" prop="name">
            <el-input v-model="form.name" />
          </el-form-item>
          <el-form-item label="所属行业" prop="industry">
            <el-select v-model="form.industry" style="width: 100%">
              <el-option v-for="i in industries" :key="i" :label="i" :value="i" />
            </el-select>
          </el-form-item>
          <el-form-item label="统一社会信用代码" prop="creditCode">
            <el-input v-model="form.creditCode" />
          </el-form-item>
          <el-form-item label="联系人" prop="contact">
            <el-input v-model="form.contact" />
          </el-form-item>
          <el-form-item label="联系电话" prop="phone">
            <el-input v-model="form.phone" />
          </el-form-item>
          <el-form-item label="注册地址">
            <el-input v-model="form.address" />
          </el-form-item>
          <el-form-item label="核算范围">
            <el-input v-model="form.scope" type="textarea" :rows="2" />
          </el-form-item>
        </template>

        <!-- 审核端字段（写入 audit_organizations） -->
        <template v-else-if="form.type === 'auditor'">
          <el-form-item label="机构名称" prop="name">
            <el-input v-model="form.name" />
          </el-form-item>
          <el-form-item label="资质证书号">
            <el-input v-model="form.qualification" />
          </el-form-item>
          <el-form-item label="业务范围">
            <el-input v-model="form.scope" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item label="联系人" prop="contact">
            <el-input v-model="form.contact" />
          </el-form-item>
          <el-form-item label="联系电话" prop="phone">
            <el-input v-model="form.phone" />
          </el-form-item>
        </template>

        <!-- 监管端字段（写入 regulator_organizations） -->
        <template v-else>
          <el-form-item label="机构名称" prop="name">
            <el-input v-model="form.name" />
          </el-form-item>
          <el-form-item label="所属区域" prop="region">
            <el-input v-model="form.region" />
          </el-form-item>
          <el-form-item label="联系人" prop="contact">
            <el-input v-model="form.contact" />
          </el-form-item>
          <el-form-item label="联系电话" prop="phone">
            <el-input v-model="form.phone" />
          </el-form-item>
          <el-form-item label="办公地址" prop="address">
            <el-input v-model="form.address" />
          </el-form-item>
          <el-form-item label="监管职责" prop="scope">
            <el-input v-model="form.scope" type="textarea" :rows="2" />
          </el-form-item>
        </template>
      </el-form>
      </div>
      <template #footer>
        <el-button @click="showForm = false">取消</el-button>
        <el-button type="primary" @click="submitForm" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉：详情项按类型动态生成 -->
    <el-drawer v-model="showDetail" :title="currentType.unit + '详情'" size="480px">
      <el-descriptions :column="1" border v-if="detail">
        <el-descriptions-item v-for="r in detailRows" :key="r.label" :label="r.label">
          {{ r.value === null || r.value === undefined || r.value === '' ? '-' : r.value }}
        </el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// ===== 依赖引入 =====
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
// 企业端接口
import { getEnterprises, getEnterpriseIndustries, createEnterprise, updateEnterprise, deleteEnterprise } from '@/api/enterprise'
// 审核端接口
import { getAuditOrganizations, createAuditOrganization, updateAuditOrganization, deleteAuditOrganization } from '@/api/auditOrg'
// 监管端接口
import { getRegulatorOrganizations, createRegulatorOrganization, updateRegulatorOrganization, deleteRegulatorOrganization } from '@/api/regulatorOrg'
// 引入设备检测：手机端渲染卡片列表，桌面端渲染表格
import { useDevice } from '@/composables/useDevice'

// 设备类型（手机端切换为卡片列表）
const { isMobile } = useDevice()

// ===== 类型定义 =====
// 三类单位共用本页：企业端存 enterprises，审核端存 audit_organizations，监管端存 regulator_organizations
const TYPE_OPTIONS = [
  { value: 'enterprise', label: '企业端', unit: '企业', idLabel: '企业编号', nameLabel: '企业名称' },
  { value: 'auditor', label: '审核端', unit: '审核机构', idLabel: '机构编号', nameLabel: '机构名称' },
  { value: 'regulator', label: '监管端', unit: '监管机构', idLabel: '机构编号', nameLabel: '机构名称' }
]
// 当前列表展示的类型
const activeType = ref('enterprise')
// 当前类型的配置项（用于列标题、按钮与提示文案）
const currentType = computed(() => TYPE_OPTIONS.find(t => t.value === activeType.value) || TYPE_OPTIONS[0])

// ===== 筛选与列表 =====
const filters = reactive({ name: '', industry: '' })
// 当前类型的单位列表
const list = ref([])
// 行业下拉选项（仅企业端使用）
const industries = ref([])
// 列表加载态
const loading = ref(false)

// 根据名称与行业条件过滤列表（行业条件仅在企业端生效）
const filteredList = computed(() =>
  list.value.filter(e =>
    (!filters.name || (e.name || '').includes(filters.name)) &&
    (!filters.industry || e.industry === filters.industry)
  )
)

// ===== 新增/编辑表单 =====
const showForm = ref(false)
const formRef = ref()
// 表单字段为三类单位的并集，提交时按类型只取对应字段
const emptyForm = () => ({
  type: 'enterprise',
  id: '', name: '', industry: '', creditCode: '', contact: '', phone: '',
  address: '', scope: '', qualification: '', region: ''
})
const form = reactive(emptyForm())
// 弹出表单当前对应的单位名称（用于标题）
const formUnit = computed(() => (TYPE_OPTIONS.find(t => t.value === form.type) || {}).unit || '企业')
// 各类型可提交的字段（与后端实体字段一一对应）
const TYPE_FIELDS = {
  enterprise: ['name', 'industry', 'creditCode', 'contact', 'phone', 'address', 'scope'],
  auditor: ['name', 'qualification', 'scope', 'contact', 'phone'],
  regulator: ['name', 'region', 'contact', 'phone', 'address', 'scope']
}
// 提交按钮加载态
const submitting = ref(false)
// 表单校验规则：隐藏的分支不会被渲染，因此不会参与校验
const rules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  industry: [{ required: true, message: '请选择行业', trigger: 'change' }],
  creditCode: [{ required: true, message: '请输入信用代码', trigger: 'blur' }],
  contact: [{ required: true, message: '请输入联系人', trigger: 'blur' }],
  phone: [{ required: true, message: '请输入联系电话', trigger: 'blur' }],
  // 监管端必填：与数据库 NOT NULL 约束一致（企业端/审核端无这些 prop，不受影响）
  region: [{ required: true, message: '请输入所属区域', trigger: 'blur' }],
  address: [{ required: true, message: '请输入办公地址', trigger: 'blur' }],
  scope: [{ required: true, message: '请输入监管职责', trigger: 'blur' }]
}

// 拉取当前类型的数据（企业端同时拉取行业下拉选项）
async function load() {
  loading.value = true
  try {
    if (activeType.value === 'enterprise') {
      const [data, inds] = await Promise.all([getEnterprises(), getEnterpriseIndustries()])
      list.value = data || []
      industries.value = inds || []
    } else if (activeType.value === 'auditor') {
      list.value = (await getAuditOrganizations()) || []
    } else {
      list.value = (await getRegulatorOrganizations()) || []
    }
  } finally {
    loading.value = false
  }
}

// 切换类型：清空筛选条件并重新拉取该类型的数据
function onTypeChange() {
  filters.name = ''
  filters.industry = ''
  showForm.value = false
  load()
}

// 打开表单：传入 row 为编辑，否则为新增；新增时默认沿用当前列表类型
function openForm(row) {
  Object.assign(form, emptyForm())
  if (row) Object.assign(form, row)
  form.type = activeType.value
  showForm.value = true
}

// 按类型抽取要提交的字段，避免把无关字段写进其它表
function buildPayload(type) {
  const payload = {}
  TYPE_FIELDS[type].forEach(k => { payload[k] = form[k] })
  return payload
}

// 校验通过后保存：按类型调用对应接口，成功后刷新列表
async function submitForm() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  // 编辑时类型锁定为当前列表类型；新增时取表单里选择的类型
  const type = form.id ? activeType.value : form.type
  try {
    const payload = buildPayload(type)
    if (form.id) {
      if (type === 'enterprise') await updateEnterprise(form.id, payload)
      else if (type === 'auditor') await updateAuditOrganization(form.id, payload)
      else await updateRegulatorOrganization(form.id, payload)
      ElMessage.success('修改成功')
    } else {
      if (type === 'enterprise') await createEnterprise(payload)
      else if (type === 'auditor') await createAuditOrganization(payload)
      else await createRegulatorOrganization(payload)
      ElMessage.success('新增成功')
    }
    showForm.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

// 删除：按当前列表类型调用对应删除接口并刷新
function remove(row) {
  ElMessageBox.confirm(`确定删除${currentType.value.unit}「${row.name}」吗？`, '提示', { type: 'warning' }).then(async () => {
    if (activeType.value === 'enterprise') await deleteEnterprise(row.id)
    else if (activeType.value === 'auditor') await deleteAuditOrganization(row.id)
    else await deleteRegulatorOrganization(row.id)
    ElMessage.success('已删除')
    await load()
  }).catch(() => {})
}

// ===== 单位详情 =====
const showDetail = ref(false)
const detail = ref(null)
// 打开详情抽屉并展示对应单位信息
function viewDetail(row) { detail.value = row; showDetail.value = true }
// 详情项按当前类型动态生成，字段名差异（registeredAt / createdAt）在此抹平
const detailRows = computed(() => {
  const d = detail.value
  if (!d) return []
  if (activeType.value === 'enterprise') {
    return [
      { label: '企业编号', value: d.id },
      { label: '企业名称', value: d.name },
      { label: '所属行业', value: d.industry },
      { label: '统一社会信用代码', value: d.creditCode },
      { label: '联系人', value: d.contact },
      { label: '联系电话', value: d.phone },
      { label: '注册地址', value: d.address },
      { label: '核算范围', value: d.scope },
      { label: '注册时间', value: d.registeredAt }
    ]
  }
  if (activeType.value === 'auditor') {
    return [
      { label: '机构编号', value: d.id },
      { label: '机构名称', value: d.name },
      { label: '机构简称', value: d.shortName },
      { label: '机构代码', value: d.code },
      { label: '资质证书号', value: d.qualification },
      { label: '业务范围', value: d.scope },
      { label: '联系人', value: d.contact },
      { label: '联系电话', value: d.phone },
      { label: '入驻时间', value: d.createdAt }
    ]
  }
  return [
    { label: '机构编号', value: d.id },
    { label: '机构名称', value: d.name },
    { label: '所属区域', value: d.region },
    { label: '联系人', value: d.contact },
    { label: '联系电话', value: d.phone },
    { label: '办公地址', value: d.address },
    { label: '监管职责', value: d.scope },
    { label: '成立时间', value: d.createdAt }
  ]
})

// 初始化加载
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>
