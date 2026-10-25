<!--
  UserManage.vue —— 用户管理页（管理员）
  用途：管理后台系统用户账号，支持按用户名/角色筛选，新增、编辑、重置密码与删除用户，
  并为企业用户关联所属企业。
  路由：/regulator/users（name: UserManage，roles: regulator / admin）
  依赖接口：@/api/user 的 getUsers、createUser、updateUser、deleteUser、resetUserPassword；
           @/api/enterprise 的 getEnterprises（“所属企业”下拉选项与名称回显）
  字段口径：role = enterprise / auditor / regulator / admin；status = active 视为正常，其余值一律视为禁用；
           用户名仅在新增时填写（编辑时禁用）；密码不在本页维护，重置后统一为 123456。
  说明：列表一次拉取全量数据，用户名/角色筛选在前端 computed 完成，本页无分页组件。
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
    <!-- 筛选在前端即时生效；角色下拉未包含“审核人员”，该角色账号需在“全部”下查看 -->
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
    <!-- 状态字段：status === 'active' 显示“正常”，其余显示“禁用”；本页不提供启停用开关 -->
    <div class="panel">
      <div class="panel-body" style="padding: 0">
        <!-- 手机端卡片列表 -->
        <div v-if="isMobile" v-loading="loading" class="m-list" style="padding: 12px">
          <el-empty v-if="!filteredList.length" description="暂无数据" :image-size="70" />
          <div v-for="row in filteredList" :key="row.id" class="m-card">
            <div class="m-card-head">
              <span class="m-card-title">{{ row.username }}</span>
              <el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">
                {{ row.status === 'active' ? '正常' : '禁用' }}
              </el-tag>
            </div>
            <div class="m-card-sub">{{ row.name }}</div>
            <div class="m-row"><span class="m-key">角色</span><span class="m-val"><el-tag :type="roleType(row.role)" size="small">{{ roleLabel(row.role) }}</el-tag></span></div>
            <div class="m-row"><span class="m-key">所属企业</span><span class="m-val">{{ enterpriseName(row.enterpriseId) }}</span></div>
            <div class="m-row"><span class="m-key">联系电话</span><span class="m-val">{{ row.phone || '-' }}</span></div>
            <div class="m-row"><span class="m-key">创建时间</span><span class="m-val">{{ row.createdAt || '-' }}</span></div>
            <div class="m-card-actions">
              <el-button text type="primary" size="small" @click="openForm(row)">编辑</el-button>
              <el-button text type="warning" size="small" @click="resetPwd(row)">重置密码</el-button>
              <el-button text type="danger" size="small" @click="remove(row)">删除</el-button>
            </div>
          </div>
        </div>

        <!-- 桌面端表格（>767px）：角色用标签区分颜色，所属企业由 enterpriseId 反查企业名称展示 -->
        <el-table v-else :data="filteredList" stripe v-loading="loading">
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
    <!-- 用户名编辑时禁用；切换角色会清空已选企业，避免非企业用户残留 enterpriseId -->
    <el-dialog v-model="showForm" :title="form.id ? '编辑用户' : '新增用户'" width="520px">
      <!-- 内容区：手机端限制高度并允许滚动，保证表单可完整填写 -->
      <div :style="isMobile ? { maxHeight: '58vh', overflowY: 'auto', paddingRight: '4px' } : null">
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
      </div>
      <template #footer>
        <!-- 保存：先做必填校验，再按 form.id 分派新增或更新 -->
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
// 引入设备检测：手机端渲染卡片列表，桌面端渲染表格
import { useDevice } from '@/composables/useDevice'

// 设备类型（手机端切换为卡片列表）
const { isMobile } = useDevice()

// ===== 筛选与列表 =====
// 筛选条件：用户名关键字 + 角色
const filters = reactive({ username: '', role: '' })
// 用户列表（接口一次返回全量，前端筛选，无分页）
const users = ref([])
// 企业列表（用于展示与选择所属企业）
const enterprises = ref([])
// 列表加载态
const loading = ref(false)

// 根据用户名与角色条件过滤用户列表
// 用户名为包含匹配，角色为精确匹配，两者同时生效
const filteredList = computed(() =>
  users.value.filter(u =>
    (!filters.username || u.username.includes(filters.username)) &&
    (!filters.role || u.role === filters.role)
  )
)

// 角色值 → 中文标签
// 兼容 auditor（审核人员）：该角色存在账号数据，但筛选下拉与新增表单未提供此选项
function roleLabel(r) { return { enterprise: '企业用户', auditor: '审核人员', regulator: '监管人员', admin: '系统管理员' }[r] }
// 角色值 → 标签颜色类型
// enterprise 与 auditor 未配置颜色，回退为 el-tag 默认样式
function roleType(r) { return { enterprise: '', regulator: 'warning', admin: 'danger' }[r] }
// 根据企业 ID 获取企业名称，未找到返回 "-"
function enterpriseName(id) { return enterprises.value.find(e => e.id === id)?.name || '-' }

// ===== 新增/编辑表单 =====
const showForm = ref(false)
const formRef = ref()
// 表单响应式状态：id 为空表示新增
// 编辑时 Object.assign 会一并复制 status 等非表单字段，提交 {...form} 回传，保存不会丢失账号状态
const form = reactive({ id: '', username: '', name: '', role: 'enterprise', enterpriseId: '', phone: '' })
// 提交按钮加载态
const submitting = ref(false)
// 表单校验规则：用户名、姓名、角色、联系电话必填
// enterpriseId 未配置校验规则，即企业用户可不选所属企业；新增用户由后端设置默认密码 123456
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
  phone: [{ required: true, message: '请输入联系电话', trigger: 'blur' }]
}

// 拉取用户与企业数据
// 两个接口并行；企业列表仅用于所属企业的下拉与名称回显
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
// 先重置为空表单再覆盖 row，避免上一次编辑的 enterpriseId 等字段残留
function openForm(row) {
  if (row) Object.assign(form, row)
  else Object.assign(form, { id: '', username: '', name: '', role: 'enterprise', enterpriseId: '', phone: '' })
  showForm.value = true
}

// 校验通过后保存：有 id 则更新已有用户，否则新增；成功后刷新列表
// 校验失败直接中断；提交期间保存按钮处于 loading，防止重复提交
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
