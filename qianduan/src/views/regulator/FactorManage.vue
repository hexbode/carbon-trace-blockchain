<!--
  文件：FactorManage.vue 碳排放因子管理页面
  所属端：监管端
  功能描述：维护碳排放核算因子库（排放源、类别、排放因子值、
           单位、参考标准等），统一各企业的核算口径，确保排放数据可比性。
           支持按排放源与类别筛选、新增/编辑因子、启用/停用因子状态。
  路由：/regulator/factor（name: FactorManage，roles: regulator / admin）
  依赖接口：@/api/factor 的 getEmissionFactors、createEmissionFactor、
           updateEmissionFactor（编辑与启用/停用共用同一更新接口）
  说明：类别下拉由已加载因子的 category 去重生成，因此新增时只能选择库中已有类别；
       列表一次全量加载，筛选在前端完成，无分页；单位限定为四种标准口径，见 UNIT_OPTIONS。
-->
<template>
  <div class="page-container">
    <!-- 页面标题区：左侧标题说明，右侧"新增因子"按钮 -->
    <div class="page-header flex-between">
      <div>
        <h2>碳排放因子管理</h2>
        <p>维护碳排放核算因子库，统一企业核算口径，确保数据可比性</p>
      </div>
      <el-button type="primary" @click="openForm()"><el-icon><Plus /></el-icon> 新增因子</el-button>
    </div>

    <!-- 筛选区：按排放源名称、类别筛选因子列表 -->
    <!-- 两个条件在前端叠加过滤（见 filtered）；类别选项来自现有因子数据，不能在此新增类别 -->
    <div class="panel mb-16">
      <div class="panel-body" style="padding: 16px 20px 0">
        <el-form :inline="true" :model="filters">
          <el-form-item label="排放源"><el-input v-model="filters.source" placeholder="请输入" clearable style="width:180px" /></el-form-item>
          <el-form-item label="类别">
            <el-select v-model="filters.category" placeholder="全部" clearable style="width:140px">
              <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <!-- 因子列表表格：展示排放源、类别、因子值、参考标准、更新时间、状态及操作按钮 -->
    <div class="panel">
      <div class="panel-body" style="padding: 0">
        <!-- 手机端卡片列表 -->
        <div v-if="isMobile" v-loading="loading" class="m-list" style="padding: 12px">
          <el-empty v-if="!filtered.length" description="暂无数据" :image-size="70" />
          <div v-for="row in filtered" :key="row.id" class="m-card">
            <div class="m-card-head">
              <span class="m-card-title">{{ row.source }}</span>
              <el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">{{ row.status === 'active' ? '启用' : '停用' }}</el-tag>
            </div>
            <div class="m-card-sub">{{ row.category }}</div>
            <div class="m-row">
              <span class="m-key">排放因子</span>
              <span class="m-val m-val-inline">
                <span class="text-primary" style="font-weight:600">{{ row.factor }}</span>
                <span class="m-unit">{{ row.unit }}</span>
              </span>
            </div>
            <div class="m-row"><span class="m-key">参考标准</span><span class="m-val">{{ row.standard || '-' }}</span></div>
            <div class="m-row"><span class="m-key">更新时间</span><span class="m-val">{{ row.updatedAt || '-' }}</span></div>
            <div class="m-card-actions">
              <el-button text type="primary" size="small" @click="openForm(row)">编辑</el-button>
              <el-button text :type="row.status === 'active' ? 'warning' : 'success'" size="small" @click="toggle(row)">{{ row.status === 'active' ? '停用' : '启用' }}</el-button>
            </div>
          </div>
        </div>

        <!-- 桌面端表格（>767px）：排放因子列把数值与单位拼在同一单元格，便于对照阅读 -->
        <el-table v-else :data="filtered" stripe v-loading="loading">
          <el-table-column prop="source" label="排放源名称" min-width="120" align="center" />
          <el-table-column prop="category" label="类别" min-width="150" align="center" />
          <el-table-column label="排放因子" min-width="140" >
            <template #default="{ row }"><span class="text-primary" style="font-weight:600">{{ row.factor }}</span> {{ row.unit }}</template>
          </el-table-column>
          <el-table-column prop="standard" label="参考标准" min-width="120"  />
          <el-table-column prop="updatedAt" label="更新时间" min-width="120" align="center" />
          <el-table-column label="状态" min-width="90" align="center">
            <template #default="{ row }"><el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">{{ row.status === 'active' ? '启用' : '停用' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" min-width="180" fixed="right" align="center">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openForm(row)">编辑</el-button>
              <el-button text :type="row.status === 'active' ? 'warning' : 'success'" size="small" @click="toggle(row)">{{ row.status === 'active' ? '停用' : '启用' }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 新增/编辑因子对话框：根据 form.id 是否存在切换标题 -->
    <!-- 排放因子使用 el-input-number：保留 4 位小数、步长 0.0001、不允许负数；
         单位下拉由 unitOptions 提供（预设四项 + 当前历史值兼容），避免手工输入造成口径不一致 -->
    <el-dialog v-model="showForm" :title="form.id ? '编辑排放因子' : '新增排放因子'" width="520px">
      <div :style="isMobile ? { maxHeight: '58vh', overflowY: 'auto', paddingRight: '4px' } : null">
        <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
          <el-form-item label="排放源名称" prop="source"><el-input v-model="form.source" /></el-form-item>
          <el-form-item label="类别" prop="category">
            <el-select v-model="form.category" style="width:100%">
              <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
            </el-select>
          </el-form-item>
          <el-form-item label="排放因子" prop="factor">
            <el-input-number v-model="form.factor" :precision="4" :step="0.0001" :min="0" style="width:100%" />
          </el-form-item>
          <el-form-item label="单位" prop="unit">
            <el-select v-model="form.unit" placeholder="请选择单位" style="width:100%">
              <el-option v-for="u in unitOptions" :key="u" :label="u" :value="u" />
            </el-select>
          </el-form-item>
          <el-form-item label="参考标准" prop="standard"><el-input v-model="form.standard" /></el-form-item>
        </el-form>
      </div>
      <template #footer>
        <!-- 保存：先做必填校验，通过后按 form.id 分派新增或更新 -->
        <el-button @click="showForm = false">取消</el-button>
        <el-button type="primary" @click="submit" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// 引入 Vue 响应式 API
import { ref, reactive, computed, onMounted } from 'vue'
// 引入 Element Plus 消息提示组件
import { ElMessage } from 'element-plus'
// 引入碳排放因子接口
import { getEmissionFactors, createEmissionFactor, updateEmissionFactor } from '@/api/factor'
// 引入设备检测：手机端渲染卡片列表，桌面端渲染表格
import { useDevice } from '@/composables/useDevice'

// 设备类型（手机端切换为卡片列表）
const { isMobile } = useDevice()

// 因子列表（接口一次返回全量，前端筛选，无分页）
const factors = ref([])
// 列表加载态
const loading = ref(false)
// 因子类别列表：从已加载数据去重得到，作为筛选栏与新增/编辑表单的类别选项
// 因此新类别只能随已有因子进入库中，页面不提供类别字典维护
const categories = computed(() => [...new Set(factors.value.map(f => f.category))])
// 筛选条件响应式状态：排放源名称关键字 + 类别
const filters = reactive({ source: '', category: '' })
// 经筛选后的因子列表计算属性：排放源为包含匹配，类别为精确匹配，两者叠加生效
const filtered = computed(() => factors.value.filter(f =>
  (!filters.source || f.source.includes(filters.source)) &&
  (!filters.category || f.category === filters.category)
))

// 是否显示新增/编辑对话框
const showForm = ref(false)
// 表单引用，用于触发校验
const formRef = ref()
// 提交按钮加载态
const submitting = ref(false)
// 表单响应式状态：id 为空表示新增
// 类别只能从 categories 选择；编辑时 Object.assign 会一并带回 status 等非表单字段，提交 {...form} 回传
const form = reactive({ id: null, source: '', category: '', factor: 0, unit: '', standard: '' })
// 排放因子单位预设值：与因子库现有口径保持一致，避免手工输入产生不一致的单位
// tCO₂/t 每吨物料、tCO₂/GJ 每吉焦热量、tCO₂/MWh 每兆瓦时电量、tCO₂/万m³ 每万立方米燃气
const UNIT_OPTIONS = ['tCO₂/t', 'tCO₂/GJ', 'tCO₂/MWh', 'tCO₂/万m³']
// 单位下拉选项：编辑历史数据时若单位不在预设列表中，临时补充显示，避免下拉框显示为空
// 仅影响下拉候选与展示，不会改写原记录的单位值；提交仍使用 form.unit
const unitOptions = computed(() => {
  const list = [...UNIT_OPTIONS]
  if (form.unit && !list.includes(form.unit)) list.push(form.unit)
  return list
})
// 表单校验规则：必填字段
// 参考标准 standard 非必填；factor 使用 el-input-number，0 也是有效输入
const rules = {
  source: [{ required: true, message: '请输入排放源名称', trigger: 'blur' }],
  category: [{ required: true, message: '请选择类别', trigger: 'change' }],
  factor: [{ required: true, message: '请输入排放因子', trigger: 'blur' }],
  unit: [{ required: true, message: '请选择单位', trigger: 'change' }]
}

// 拉取因子列表（全量，前端筛选）
async function load() {
  loading.value = true
  try {
    factors.value = await getEmissionFactors()
  } finally {
    loading.value = false
  }
}

// 打开新增/编辑对话框：传入 row 为编辑模式，否则为新增模式并重置表单
function openForm(row) {
  if (row) Object.assign(form, row)
  else Object.assign(form, { id: null, source: '', category: '', factor: 0, unit: '', standard: '' })
  showForm.value = true
}
// 提交表单：校验通过后，根据 id 判断执行更新或新增操作；成功后刷新列表
async function submit() {
  const v = await formRef.value.validate().catch(() => false)
  if (!v) return
  submitting.value = true
  try {
    if (form.id) {
      await updateEmissionFactor(form.id, { ...form })
      ElMessage.success('修改成功')
    } else {
      await createEmissionFactor({ ...form })
      ElMessage.success('新增成功')
    }
    showForm.value = false
    await load()
  } finally {
    submitting.value = false
  }
}
// 切换因子启用/停用状态（调用更新接口后刷新列表）
// status 映射：active → inactive（停用），其余 → active（启用）
async function toggle(row) {
  const status = row.status === 'active' ? 'inactive' : 'active'
  await updateEmissionFactor(row.id, { ...row, status })
  ElMessage.success(status === 'active' ? '已启用' : '已停用')
  await load()
}

// 初始化加载
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>
