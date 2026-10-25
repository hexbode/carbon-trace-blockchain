<!--
  文件：AuditOrgManage.vue 审核机构管理页面
  所属端：监管端  
  功能描述：管理碳排放认证审核机构的资质证书、业务范围、
           联系人与状态等信息。支持新增机构、编辑机构信息、启用/停用机构，
           确保审核机构资质合规、业务范围明确，便于监管端统一调度与监督。
  路由：/regulator/audit-org（name: AuditOrgManage，roles: regulator / admin）
  依赖接口：@/api/auditOrg 的 getAuditOrganizations、createAuditOrganization、
           updateAuditOrganization（编辑与启用/停用共用同一更新接口）
  说明：列表一次拉取全量数据，本页无筛选与分页；状态字段 status 取 active（正常）/ inactive（停用），
       页面只提供启用/停用，不提供删除入口。
-->
<template>
  <div class="page-container">
    <!-- 页面标题区：左侧标题说明，右侧"新增机构"按钮 -->
    <div class="page-header flex-between">
      <div>
        <h2>审核机构管理</h2>
        <p>管理碳排放认证审核机构资质、业务范围与状态</p>
      </div>
      <el-button type="primary" @click="openForm()"><el-icon><Plus /></el-icon> 新增机构</el-button>
    </div>

    <!-- 机构列表表格：展示机构编号、名称、资质、业务范围、联系人、电话、入驻时间、状态及操作 -->
    <!-- 状态字段：status === 'active' 展示“正常”（绿色），其余视为“停用”（灰色）；操作列按钮文案与颜色随状态互换 -->
    <div class="panel">
      <div class="panel-body" style="padding: 0">
        <!-- 手机端卡片列表 -->
        <div v-if="isMobile" v-loading="loading" class="m-list" style="padding: 12px">
          <el-empty v-if="!list.length" description="暂无数据" :image-size="70" />
          <div v-for="row in list" :key="row.id" class="m-card">
            <div class="m-card-head">
              <span class="m-card-title">{{ row.name }}</span>
              <el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">{{ row.status === 'active' ? '正常' : '停用' }}</el-tag>
            </div>
            <div class="m-card-sub">机构编号：{{ row.id }}</div>
            <div class="m-row"><span class="m-key">资质证书号</span><span class="m-val">{{ row.qualification || '-' }}</span></div>
            <div class="m-row"><span class="m-key">业务范围</span><span class="m-val">{{ row.scope || '-' }}</span></div>
            <div class="m-row"><span class="m-key">联系人</span><span class="m-val">{{ row.contact || '-' }}</span></div>
            <div class="m-row"><span class="m-key">联系电话</span><span class="m-val">{{ row.phone || '-' }}</span></div>
            <div class="m-row"><span class="m-key">入驻时间</span><span class="m-val">{{ row.createdAt || '-' }}</span></div>
            <div class="m-card-actions">
              <el-button text type="primary" size="small" @click="openForm(row)">编辑</el-button>
              <el-button text :type="row.status === 'active' ? 'warning' : 'success'" size="small" @click="toggle(row)">
                {{ row.status === 'active' ? '停用' : '启用' }}
              </el-button>
            </div>
          </div>
        </div>

        <!-- 桌面端表格（>767px）：列顺序为编号、名称、资质、业务范围、联系人、电话、入驻时间、状态、操作 -->
        <el-table v-else :data="list" stripe v-loading="loading">
          <el-table-column prop="id" label="机构编号" min-width="110" align="center" />
          <el-table-column prop="name" label="机构名称" min-width="190" show-overflow-tooltip align="center" />
          <el-table-column prop="qualification" label="资质证书号" min-width="130" align="center" />
          <el-table-column prop="scope" label="业务范围" min-width="200" show-overflow-tooltip align="center" />
          <el-table-column prop="contact" label="联系人" min-width="100" align="center" />
          <el-table-column prop="phone" label="联系电话" min-width="140" align="center" />
          <el-table-column prop="createdAt" label="入驻时间" min-width="120" align="center" />
          <el-table-column label="状态" min-width="90" align="center">
            <template #default="{ row }"><el-tag :type="row.status === 'active' ? 'success' : 'info'" size="small">{{ row.status === 'active' ? '正常' : '停用' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" min-width="180" fixed="right" align="center">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openForm(row)">编辑</el-button>
              <el-button text :type="row.status === 'active' ? 'warning' : 'success'" size="small" @click="toggle(row)">
                {{ row.status === 'active' ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 新增/编辑机构对话框：根据 form.id 是否存在切换标题 -->
    <!-- 表单字段与审核机构实体对应；业务范围按行业列举，用顿号或逗号分隔，便于检索 -->
    <el-dialog v-model="showForm" :title="form.id ? '编辑审核机构' : '新增审核机构'" width="560px">
      <div :style="isMobile ? { maxHeight: '58vh', overflowY: 'auto', paddingRight: '4px' } : null">
        <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
          <el-form-item label="机构名称" prop="name"><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="资质证书号" prop="qualification"><el-input v-model="form.qualification" /></el-form-item>
          <el-form-item label="业务范围" prop="scope"><el-input v-model="form.scope" placeholder="如：钢铁、化工、电力" /></el-form-item>
          <el-form-item label="联系人" prop="contact"><el-input v-model="form.contact" /></el-form-item>
          <el-form-item label="联系电话" prop="phone"><el-input v-model="form.phone" /></el-form-item>
        </el-form>
      </div>
      <template #footer>
        <!-- 保存：先做必填校验，再按 form.id 分派新增或更新 -->
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
import { ref, reactive, onMounted } from 'vue'
// 引入 Element Plus 消息提示组件
import { ElMessage } from 'element-plus'
// 引入审核机构接口
import { getAuditOrganizations, createAuditOrganization, updateAuditOrganization } from '@/api/auditOrg'
// 引入设备检测：手机端渲染卡片列表，桌面端渲染表格
import { useDevice } from '@/composables/useDevice'

// 设备类型（手机端切换为卡片列表）
const { isMobile } = useDevice()

// 机构列表
const list = ref([])
// 列表加载态
const loading = ref(false)
// 是否显示新增/编辑对话框
const showForm = ref(false)
// 表单引用，用于触发校验
const formRef = ref()
// 提交按钮加载态
const submitting = ref(false)
// 表单响应式状态：id 为空表示新增
// 打开编辑时 Object.assign 会把 status 等非表单字段一并复制进来，提交 {...form} 回传，保存不会丢失启停用状态
const form = reactive({ id: '', name: '', qualification: '', scope: '', contact: '', phone: '' })
// 表单校验规则：必填字段
// scope（业务范围）非必填；phone 仅校验非空，不校验号码格式
const rules = {
  name: [{ required: true, message: '请输入机构名称', trigger: 'blur' }],
  qualification: [{ required: true, message: '请输入资质证书号', trigger: 'blur' }],
  contact: [{ required: true, message: '请输入联系人', trigger: 'blur' }],
  phone: [{ required: true, message: '请输入联系电话', trigger: 'blur' }]
}

// 拉取机构列表（接口一次返回全量，前端不分页）
async function load() {
  loading.value = true
  try {
    list.value = await getAuditOrganizations()
  } finally {
    loading.value = false
  }
}

// 打开新增/编辑对话框：传入 row 为编辑模式，否则为新增模式并重置表单
function openForm(row) {
  if (row) Object.assign(form, row)
  else Object.assign(form, { id: '', name: '', qualification: '', scope: '', contact: '', phone: '' })
  showForm.value = true
}
// 提交表单：校验通过后，根据 id 判断执行更新或新增操作；成功后刷新列表
async function submit() {
  const v = await formRef.value.validate().catch(() => false)
  if (!v) return
  submitting.value = true
  try {
    if (form.id) {
      await updateAuditOrganization(form.id, { ...form })
      ElMessage.success('修改成功')
    } else {
      await createAuditOrganization({ ...form })
      ElMessage.success('新增成功')
    }
    showForm.value = false
    await load()
  } finally {
    submitting.value = false
  }
}
// 切换机构启用/停用状态（调用更新接口后刷新列表）
// 状态映射：正常 → inactive（停用），停用 → active（重新启用）；无二次确认
async function toggle(row) {
  const status = row.status === 'active' ? 'inactive' : 'active'
  await updateAuditOrganization(row.id, { ...row, status })
  ElMessage.success(status === 'active' ? '已启用' : '已停用')
  await load()
}

// 初始化加载
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>
