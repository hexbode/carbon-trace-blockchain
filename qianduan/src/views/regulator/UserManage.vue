<!--
  UserManage.vue —— 用户管理页（管理员）
  用途：管理后台系统用户账号，支持按用户名/角色筛选，新增、编辑、重置密码与删除用户，
  并为企业用户关联所属企业。
-->
<template>
  <div class="page-container">
    <!-- 页面标题区：标题说明 + 新增用户按钮 -->
    <div class="page-header flex-between">
      <div>
        <h2>用户管理</h2>
        <p>管理系统用户账号、角色权限与关联企业</p>
      </div>
      <el-button type="primary" @click="openForm()">
        <el-icon><Plus /></el-icon> 新增用户
      </el-button>
    </div>

    <!-- 筛选区：按用户名、角色过滤用户列表 -->
    <div class="panel mb-16">
      <div class="panel-body">
        <el-form :inline="true" :model="filters">
          <el-form-item label="用户名">
            <el-input v-model="filters.username" placeholder="请输入" clearable style="width: 160px" />
          </el-form-item>
          <el-form-item label="角色">
            <el-select v-model="filters.role" placeholder="全部" clearable style="width: 140px">
              <el-option label="企业用户" value="enterprise" />
              <el-option label="监管人员" value="regulator" />
              <el-option label="系统管理员" value="admin" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <!-- 用户列表：展示用户名、角色、所属企业、状态与操作 -->
    <div class="panel">
      <div class="panel-body" style="padding: 0">
        <el-table :data="filteredList" stripe v-loading="loading">
          <el-table-column prop="username" label="用户名" min-width="140" />
          <el-table-column prop="name" label="姓名" min-width="120" />
          <el-table-column label="角色" min-width="120" align="center">
            <template #default="{ row }">
              <el-tag :type="roleType(row.role)" size="small">{{ roleLabel(row.role) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="所属企业" min-width="200">
            <template #default="{ row }">
              {{ enterpriseName(row.enterpriseId) }}
            </template>
          </el-table-column>
          <el-table-column prop="phone" label="联系电话" min-width="150" />
          <el-table-column label="状态" min-width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">
                {{ row.status === 'active' ? '正常' : '禁用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" min-width="120" />
          <el-table-column label="操作" min-width="180" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openForm(row)">编辑</el-button>
              <el-button text type="warning" size="small" @click="resetPwd(row)">重置密码</el-button>
              <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 新增/编辑用户弹窗：角色为企业用户时需选择所属企业 -->
    <el-dialog v-model="showForm" :title="form.id ? '编辑用户' : '新增用户'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item label="姓名" prop="name">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="form.role" style="width: 100%" @change="form.enterpriseId = ''">
            <el-option label="企业用户" value="enterprise" />
            <el-option label="监管人员" value="regulator" />
            <el-option label="系统管理员" value="admin" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.role === 'enterprise'" label="所属企业" prop="enterpriseId">
          <el-select v-model="form.enterpriseId" style="width: 100%" filterable>
            <el-option v-for="e in enterprises" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="联系电话" prop="phone">
          <el-input v-model="form.phone" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showForm = false">取消</el-button>
        <el-button type="primary" @click="submitForm" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// ===== 依赖引入 =====
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
// 用户与企业接口
import { getUsers, createUser, updateUser, deleteUser, resetUserPassword } from '@/api/user'
import { getEnterprises } from '@/api/enterprise'

// ===== 筛选与列表 =====
const filters = reactive({ username: '', role: '' })
// 用户列表
const users = ref([])
// 企业列表（用于展示与选择所属企业）
const enterprises = ref([])
// 列表加载态
const loading = ref(false)

// 根据用户名与角色条件过滤用户列表
const filteredList = computed(() =>
  users.value.filter(u =>
    (!filters.username || u.username.includes(filters.username)) &&
    (!filters.role || u.role === filters.role)
  )
)

// 角色值 → 中文标签
function roleLabel(r) { return { enterprise: '企业用户', auditor: '审核人员', regulator: '监管人员', admin: '系统管理员' }[r] }
// 角色值 → 标签颜色类型
function roleType(r) { return { enterprise: '', regulator: 'warning', admin: 'danger' }[r] }
// 根据企业 ID 获取企业名称，未找到返回 "-"
function enterpriseName(id) { return enterprises.value.find(e => e.id === id)?.name || '-' }

// ===== 新增/编辑表单 =====
const showForm = ref(false)
const formRef = ref()
const form = reactive({ id: '', username: '', name: '', role: 'enterprise', enterpriseId: '', phone: '' })
// 提交按钮加载态
const submitting = ref(false)
// 表单校验规则
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  phone: [{ required: true, message: '请输入联系电话', trigger: 'blur' }]
}

// 拉取用户与企业数据
async function load() {
  loading.value = true
  try {
    const [us, ents] = await Promise.all([getUsers(), getEnterprises()])
    users.value = us || []
    enterprises.value = ents || []
  } finally {
    loading.value = false
  }
}

// 打开表单：传入 row 为编辑，否则为新增（重置表单）
function openForm(row) {
  if (row) Object.assign(form, row)
  else Object.assign(form, { id: '', username: '', name: '', role: 'enterprise', enterpriseId: '', phone: '' })
  showForm.value = true
}

// 校验通过后保存：有 id 则更新已有用户，否则新增；成功后刷新列表
async function submitForm() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (form.id) {
      await updateUser(form.id, { ...form })
      ElMessage.success('修改成功')
    } else {
      await createUser({ ...form })
      ElMessage.success('新增成功')
    }
    showForm.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

// 重置密码：确认后调用后端重置接口（密码重置为 123456）
function resetPwd(row) {
  ElMessageBox.confirm(`确定重置用户「${row.name}」的密码为 123456 吗？`, '重置密码', { type: 'warning' }).then(async () => {
    await resetUserPassword(row.id)
    ElMessage.success('密码已重置为 123456')
  }).catch(() => {})
}

// 删除用户：禁止删除 admin 管理员账号，其余调用后端删除接口
function remove(row) {
  if (row.username === 'admin') {
    ElMessage.warning('不能删除管理员账号')
    return
  }
  ElMessageBox.confirm(`确定删除用户「${row.name}」吗？`, '提示', { type: 'warning' }).then(async () => {
    await deleteUser(row.id)
    ElMessage.success('已删除')
    await load()
  }).catch(() => {})
}

// 初始化加载
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>
