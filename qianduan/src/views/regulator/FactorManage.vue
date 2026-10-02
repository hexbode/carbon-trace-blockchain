<!--
  文件：FactorManage.vue 碳排放因子管理页面
  所属端：监管端
  功能描述：维护碳排放核算因子库（排放源、类别、排放因子值、
           单位、参考标准等），统一各企业的核算口径，确保排放数据可比性。
           支持按排放源与类别筛选、新增/编辑因子、启用/停用因子状态。
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
        <el-table :data="filtered" stripe v-loading="loading">
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
    <el-dialog v-model="showForm" :title="form.id ? '编辑排放因子' : '新增排放因子'" width="520px">
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
        <el-form-item label="单位" prop="unit"><el-input v-model="form.unit" placeholder="如 tCO₂/t" /></el-form-item>
        <el-form-item label="参考标准" prop="standard"><el-input v-model="form.standard" /></el-form-item>
      </el-form>
      <template #footer>
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

// 因子列表
const factors = ref([])
// 列表加载态
const loading = ref(false)
// 因子类别列表：从已加载数据去重得到
const categories = computed(() => [...new Set(factors.value.map(f => f.category))])
// 筛选条件响应式状态：排放源名称关键字 + 类别
const filters = reactive({ source: '', category: '' })
// 经筛选后的因子列表计算属性
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
const form = reactive({ id: null, source: '', category: '', factor: 0, unit: '', standard: '' })
// 表单校验规则：必填字段
const rules = {
  source: [{ required: true, message: '请输入排放源名称', trigger: 'blur' }],
  category: [{ required: true, message: '请选择类别', trigger: 'change' }],
  factor: [{ required: true, message: '请输入排放因子', trigger: 'blur' }],
  unit: [{ required: true, message: '请输入单位', trigger: 'blur' }]
}

// 拉取因子列表
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
