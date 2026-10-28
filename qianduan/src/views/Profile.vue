<!--
  Profile.vue —— 个人中心
  用途：登录用户管理个人资料、修改登录密码、更换绑定手机号以及配置账号偏好设置。
  路由：/profile（Layout 子路由，企业端/审核端/监管端/管理员均可访问）。
  左侧展示用户信息卡（头像/角色/所属机构/注册时间），右侧为四个功能标签页。
-->
<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <h2>个人中心</h2>
      <p>管理个人资料、登录密码、绑定手机与账号偏好设置</p>
    </div>

    <el-row :gutter="20">
      <!-- 左侧：用户信息卡（占 40% 宽度） -->
      <el-col :span="8" class="profile-col-left">
        <div class="profile-card panel">
          <div class="avatar-wrap">
            <!-- 头像 + 相机按钮：按钮绝对定位在头像右下角，无头像时回退默认图标 -->
            <el-avatar :size="84" :src="profile.avatar || undefined" :icon="UserFilled" class="big-avatar" />
            <el-button class="avatar-btn" size="small" circle :loading="uploadingAvatar" @click="changeAvatar">
              <el-icon><Camera /></el-icon>
            </el-button>
            <!-- 隐藏的图片选择框：点击相机按钮时触发 -->
            <input ref="avatarInput" type="file" accept="image/*" class="avatar-input" @change="onAvatarChange" />
          </div>
          <h3 class="p-name">{{ userStore.user?.name }}</h3>
          <div class="p-tags">
            <el-tag type="success" effect="dark" size="small">{{ userStore.portalLabel }}</el-tag>
            <el-tag effect="plain" size="small">{{ userStore.roleLabel }}</el-tag>
          </div>

          <el-divider style="margin: 18px 0" />

          <div class="meta-list">
            <div class="meta-item">
              <el-icon><User /></el-icon>
              <span class="m-label">账号</span>
              <span class="m-value">{{ userStore.user?.username }}</span>
            </div>
            <div class="meta-item">
              <el-icon><Iphone /></el-icon>
              <span class="m-label">手机</span>
              <span class="m-value">{{ profile.phone || '未绑定' }}</span>
            </div>
            <div class="meta-item">
              <el-icon>
                <OfficeBuilding v-if="role === 'enterprise'" />
                <School v-else />
              </el-icon>
              <span class="m-label">{{ role === 'enterprise' ? '企业' : '机构' }}</span>
              <span class="m-value ellipsis">{{ orgName || '-' }}</span>
            </div>
            <div class="meta-item">
              <el-icon><Calendar /></el-icon>
              <span class="m-label">注册</span>
              <span class="m-value">{{ profile.createdAt || '-' }}</span>
            </div>
          </div>
        </div>
      </el-col>

      <!-- 右侧：功能标签页（占 60% 宽度） -->
      <el-col :span="16" class="profile-col-right">
        <div class="panel profile-panel">
          <el-tabs v-model="activeTab" class="profile-tabs" :class="{ 'tabs-mobile': isMobile }">
            <!-- 个人资料 -->
            <el-tab-pane name="info">
              <template #label>
                <span class="tab-label"><el-icon><Edit /></el-icon> 个人资料</span>
              </template>
              <el-form
                ref="infoFormRef"
                :model="infoForm"
                :rules="infoRules"
                label-width="90px"
                class="tab-form"
              >
                <el-form-item label="姓名" prop="name">
                  <el-input v-model="infoForm.name" placeholder="请输入姓名" maxlength="20" show-word-limit />
                </el-form-item>
                <el-form-item label="登录账号">
                  <el-input v-model="infoForm.username" disabled />
                </el-form-item>
                <el-form-item label="角色" class="readonly-item">
                  <el-tag type="success" effect="plain">{{ userStore.roleLabel }}</el-tag>
                  <span class="readonly-tip">角色由管理员分配，不可自行修改</span>
                </el-form-item>
                <el-form-item label="电子邮箱">
                  <el-input v-model="infoForm.email" placeholder="请输入电子邮箱（选填）" />
                </el-form-item>
                <el-form-item label="个性签名">
                  <el-input
                    v-model="infoForm.signature"
                    type="textarea"
                    :rows="4"
                    maxlength="100"
                    show-word-limit
                    placeholder="介绍一下自己吧（选填）"
                  />
                </el-form-item>
                <el-form-item class="form-actions">
                  <el-button type="primary" @click="saveInfo" >保存资料</el-button>
                  <el-button @click="resetInfo">重置</el-button>
                </el-form-item>
              </el-form>
            </el-tab-pane>

            <!-- 修改密码 -->
            <el-tab-pane name="password">
              <template #label>
                <span class="tab-label"><el-icon><Lock /></el-icon> 修改密码</span>
              </template>
              <el-form
                ref="pwdFormRef"
                :model="pwdForm"
                :rules="pwdRules"
                label-width="100px"
                class="tab-form"
              >
                <el-form-item label="当前密码" prop="oldPassword">
                  <el-input v-model="pwdForm.oldPassword" type="password" placeholder="请输入当前密码" show-password />
                </el-form-item>
                <el-form-item label="新密码" prop="newPassword">
                  <el-input v-model="pwdForm.newPassword" type="password" placeholder="8-20位，含大小写字母和数字" show-password />
                </el-form-item>
                <!-- 密码强度：三段指示条与文案均由新密码实时计算 -->
                <el-form-item label="密码强度">
                  <div class="strength-bar">
                    <div
                      v-for="(lv, i) in 3"
                      :key="i"
                      class="strength-seg"
                      :class="strengthLevel > i ? strengthClass : ''"
                    ></div>
                  </div>
                  <span class="strength-text">{{ strengthText }}</span>
                </el-form-item>
                <el-form-item label="确认新密码" prop="confirmPassword">
                  <el-input v-model="pwdForm.confirmPassword" type="password" placeholder="请再次输入新密码" show-password />
                </el-form-item>
                <el-form-item class="form-actions">
                  <el-button type="primary" @click="savePassword">确认修改</el-button>
                  <el-button @click="resetPwd">清空</el-button>
                </el-form-item>
              </el-form>
            </el-tab-pane>

            <!-- 修改手机号 -->
            <el-tab-pane name="phone">
              <template #label>
                <span class="tab-label"><el-icon><Iphone /></el-icon> 修改手机号</span>
              </template>
              <el-form
                ref="phoneFormRef"
                :model="phoneForm"
                :rules="phoneRules"
                label-width="100px"
                class="tab-form"
              >
                <el-form-item label="当前手机号">
                  <el-input :model-value="maskedPhone" disabled />
                </el-form-item>
                <el-form-item label="新手机号" prop="phone">
                  <el-input v-model="phoneForm.phone" placeholder="请输入新手机号" maxlength="11" />
                </el-form-item>
                <!-- 验证码：倒计时期间按钮禁用，手机号为空时也不可点击 -->
                <el-form-item label="验证码" prop="code">
                  <div class="code-row">
                    <el-input v-model="phoneForm.code" placeholder="请输入6位验证码" maxlength="6" />
                    <el-button
                      :disabled="countdown > 0 || !phoneForm.phone"
                      @click="sendCode"
                      class="code-btn"
                    >
                      {{ countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
                    </el-button>
                  </div>
                </el-form-item>
                <el-form-item class="form-actions">
                  <el-button type="primary" @click="savePhone">绑定新手机</el-button>
                </el-form-item>
              </el-form>
            </el-tab-pane>

            <!-- 账号设置 -->
            <el-tab-pane name="settings">
              <template #label>
                <span class="tab-label"><el-icon><Setting /></el-icon> 账号设置</span>
              </template>
              <div class="tab-form settings-list">
                <!-- 设置项：标题 + 说明 + 右侧开关，最终统一提交保存 -->
                <div class="setting-item" v-for="item in settings" :key="item.key">
                  <div class="setting-text">
                    <div class="s-title">{{ item.title }}</div>
                    <div class="s-desc">{{ item.desc }}</div>
                  </div>
                  <el-switch v-model="settingsModel[item.key]" />
                </div>
                <el-divider />
                <div class="setting-actions">
                  <el-button type="primary" @click="saveSettings">保存设置</el-button>
                </div>
              </div>
            </el-tab-pane>
          </el-tabs>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// 导入 Vue 响应式 API
import { ref, reactive, computed, onMounted } from 'vue'
// 导入 Element Plus 消息提示
import { ElMessage } from 'element-plus'
// 导入默认头像图标
import { UserFilled } from '@element-plus/icons-vue'
// 导入用户状态仓库
import { useUserStore } from '@/store'
// 导入用户、企业、审核机构接口
import { getMyProfile, updateUser, changeUserPassword, getUserSettings, updateUserSettings } from '@/api/user'
// 头像上传接口
import { uploadAvatar } from '@/api/file'
// 企业与审核机构列表：仅用于把用户记录中的 ID 解析成可读的机构名称
import { getEnterprises } from '@/api/enterprise'
import { getAuditOrganizations } from '@/api/auditOrg'
// 引入设备检测：手机端为标签页附加横向滚动样式（左右分栏堆叠由全局样式处理）
import { useDevice } from '@/composables/useDevice'

const userStore = useUserStore()
// 设备类型（手机端标签页标题超出宽度时可横向滚动）
const { isMobile } = useDevice()
// 当前激活的标签页（默认个人资料）
const activeTab = ref('info')

// 当前登录用户在用户表中的完整记录
const profile = ref({})
// 企业列表与审核机构列表（用于展示所属机构名称）
const enterprises = ref([])
const auditOrganizations = ref([])
// 当前用户角色：决定「企业/机构」字段的图标与文案
const role = computed(() => userStore.role)

// 所属机构名称：企业用户取企业名，审核员取审核机构名，其余默认监管机构
const orgName = computed(() => {
  if (role.value === 'enterprise') {
    return enterprises.value.find(e => e.id === userStore.user?.enterpriseId)?.name || ''
  }
  if (role.value === 'auditor') {
    return auditOrganizations.value.find(o => o.id === userStore.user?.orgId)?.name || ''
  }
  return '碳排放监管机构'
})

// 初始化：加载企业/机构列表、当前用户记录与账号设置
async function load() {
  // 企业/机构列表互不依赖，并行拉取以缩短首屏等待
  const [ents, orgs] = await Promise.all([
    getEnterprises(),
    getAuditOrganizations()
  ])
  enterprises.value = ents || []
  auditOrganizations.value = orgs || []
  await loadProfile()
  await loadSettings()
}
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)

// ============ 个人资料 ============
const infoFormRef = ref()
// 个人资料表单：姓名、账号（只读）、邮箱、签名
const infoForm = reactive({
  name: userStore.user?.name || '',
  username: userStore.user?.username || '',
  email: userStore.user?.email || '',
  signature: userStore.user?.signature || ''
})
// 个人资料校验：姓名必填，邮箱选填但填写时需符合格式
const infoRules = {
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}
// 从数据库加载当前用户完整记录，并回填个人资料表单
async function loadProfile() {
  // 使用 /users/me 只查本人资料：企业端/审核端调用用户列表会被后端 403 拒绝
  profile.value = (await getMyProfile()) || {}
  infoForm.name = profile.value.name || ''
  infoForm.email = profile.value.email || ''
  infoForm.signature = profile.value.signature || ''
  // 将库中最新资料同步回登录快照，使左侧卡片、顶部栏等读取快照的位置也能随数据库一起刷新
  if (profile.value.username) {
    userStore.updateProfile({
      username: profile.value.username,
      name: profile.value.name,
      role: profile.value.role,
      phone: profile.value.phone,
      email: profile.value.email,
      signature: profile.value.signature,
      avatar: profile.value.avatar,
      enterpriseId: profile.value.enterpriseId,
      orgId: profile.value.orgId
    })
  }
}
// 保存个人资料：写入数据库后同步本地状态并重新加载
function saveInfo() {
  infoFormRef.value.validate(async valid => {
    if (!valid) return
    const saved = await updateUser(profile.value.id, {
      ...profile.value,
      name: infoForm.name,
      email: infoForm.email,
      signature: infoForm.signature
    })
    userStore.updateProfile({ name: saved.name, email: saved.email, signature: saved.signature })
    await loadProfile()
    ElMessage.success('个人资料已保存')
  })
}
// 重置个人资料表单为数据库当前值
function resetInfo() {
  infoForm.name = profile.value.name || ''
  infoForm.email = profile.value.email || ''
  infoForm.signature = profile.value.signature || ''
}
// 头像文件选择框引用与上传中状态
const avatarInput = ref(null)
const uploadingAvatar = ref(false)
// 更换头像：点击相机按钮触发文件选择
function changeAvatar() {
  avatarInput.value?.click()
}

// 选中图片后：先上传到后端，再把返回的图片地址保存到用户记录
async function onAvatarChange(e) {
  const file = e.target.files && e.target.files[0]
  if (!file) return
  // 仅接受图片类型，避免把非图片文件上传到头像接口
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请选择图片文件')
    e.target.value = ''
    return
  }
  uploadingAvatar.value = true
  try {
    const res = await uploadAvatar(file)
    const saved = await updateUser(profile.value.id, { ...profile.value, avatar: res.url })
    profile.value = { ...profile.value, avatar: saved.avatar || res.url }
    userStore.updateProfile({ avatar: profile.value.avatar })
    ElMessage.success('头像已更新')
  } catch (err) {
    // 错误提示由请求拦截器统一处理
  } finally {
    uploadingAvatar.value = false
    // 清空文件框，保证下次选择同一张图片仍能触发 change
    e.target.value = ''
  }
}

// ============ 修改密码 ============
const pwdFormRef = ref()
// 修改密码表单：当前密码、新密码、确认密码
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

// 新密码自定义校验：必填、8-20 位且同时含大小写字母与数字、不得为初始密码，与确认密码联动校验
const validateNewPwd = (rule, value, callback) => {
  if (!value) {
    callback(new Error('请输入新密码'))
  } else if (value === '123456') {
    callback(new Error('新密码不能使用初始密码 123456'))
  } else if (!/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).{8,20}$/.test(value)) {
    callback(new Error('密码须为8-20位且同时包含大小写字母和数字'))
  } else if (pwdForm.confirmPassword && value !== pwdForm.confirmPassword) {
    pwdFormRef.value.validateField('confirmPassword')
    callback()
  } else {
    callback()
  }
}
// 确认密码校验：必填且须与新密码一致
const validateConfirmPwd = (rule, value, callback) => {
  if (!value) callback(new Error('请再次输入新密码'))
  else if (value !== pwdForm.newPassword) callback(new Error('两次输入的密码不一致'))
  else callback()
}
// 密码表单校验：旧密码必填；新密码走自定义复杂度校验；确认密码与新密码联动
const pwdRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [{ required: true, validator: validateNewPwd, trigger: 'blur' }],
  confirmPassword: [{ required: true, validator: validateConfirmPwd, trigger: 'blur' }]
}

// 密码强度（0-3 级）：非空即 1 级，长度 ≥10 加 1 级，同时含大小写与符号再加 1 级
const strengthLevel = computed(() => {
  const p = pwdForm.newPassword
  if (!p) return 0
  let level = 1
  if (p.length >= 10) level++
  if (/[^A-Za-z0-9]/.test(p) && /[A-Z]/.test(p) && /[a-z]/.test(p)) level++
  return level
})
// 密码强度对应的样式类
const strengthClass = computed(() => ['weak', 'medium', 'strong'][strengthLevel.value - 1])
// 密码强度文字
const strengthText = computed(() => ['', '弱', '中', '强'][strengthLevel.value])

// 保存密码：校验通过后调用后端修改（旧密码错误由拦截器统一提示）
function savePassword() {
  pwdFormRef.value.validate(async valid => {
    if (!valid) return
    try {
      await changeUserPassword(profile.value.id, {
        oldPassword: pwdForm.oldPassword,
        newPassword: pwdForm.newPassword
      })
      ElMessage.success('密码修改成功，下次登录请使用新密码')
      resetPwd()
    } catch (e) {
      // 错误提示由请求拦截器统一处理，此处不重复弹出
    }
  })
}
// 清空修改密码表单
function resetPwd() {
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirmPassword = ''
}

// ============ 修改手机号 ============
const phoneFormRef = ref()
// 修改手机号表单：新手机号、验证码
const phoneForm = reactive({ phone: '', code: '' })
// 验证码倒计时秒数
const countdown = ref(0)
// 倒计时定时器句柄
let timer = null

// 脱敏显示当前手机号（中间四位替换为 *）
const maskedPhone = computed(() => {
  const p = profile.value.phone || ''
  return p ? p.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2') : '未绑定'
})

// 手机号表单校验：11 位大陆手机号；验证码为 6 位数字
const phoneRules = {
  phone: [
    { required: true, message: '请输入新手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为6位数字', trigger: 'blur' }
  ]
}

// 发送验证码：校验手机号后启动 60s 倒计时
function sendCode() {
  if (!/^1[3-9]\d{9}$/.test(phoneForm.phone)) {
    ElMessage.warning('请先输入正确的新手机号')
    return
  }
  ElMessage.success(`验证码已发送至 ${phoneForm.phone}（演示验证码：123456）`)
  countdown.value = 60
  timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) clearInterval(timer)
  }, 1000)
}

// 绑定新手机号：校验验证码（演示码 123456）通过后更新用户手机号
async function savePhone() {
  const valid = await phoneFormRef.value.validate().catch(() => false)
  if (!valid) return
  if (phoneForm.code !== '123456') {
    ElMessage.error('验证码不正确')
    return
  }
  userStore.updateProfile({ phone: phoneForm.phone })
  // 同步更新后端用户记录
  if (profile.value.id) {
    await updateUser(profile.value.id, { ...profile.value, phone: phoneForm.phone })
    profile.value = { ...profile.value, phone: phoneForm.phone }
  }
  ElMessage.success('手机号绑定成功')
  phoneForm.phone = ''
  phoneForm.code = ''
}

// ============ 账号设置 ============
// 账号偏好设置项配置：站内消息、短信、异地登录提醒、周报推送
const settings = [
  { key: 'msgNotify', title: '站内消息通知', desc: '审核进度、认证结果等站内消息提醒' },
  { key: 'smsNotify', title: '短信通知', desc: '关键节点通过短信通知到绑定手机' },
  { key: 'loginRemind', title: '异地登录提醒', desc: '检测到非常用设备登录时发送提醒' },
  { key: 'weeklyReport', title: '周报推送', desc: '每周一推送上一周碳排放数据周报' }
]
// 账号设置开关状态（默认开启，挂载时从数据库加载覆盖）
const settingsModel = reactive({
  msgNotify: true,
  smsNotify: true,
  loginRemind: true,
  weeklyReport: true
})
// 从数据库加载账号设置
async function loadSettings() {
  try {
    const s = await getUserSettings(profile.value.id)
    settingsModel.msgNotify = s.msgNotify
    settingsModel.smsNotify = s.smsNotify
    settingsModel.loginRemind = s.loginRemind
    settingsModel.weeklyReport = s.weeklyReport
  } catch (e) {
    // 错误提示由请求拦截器统一处理
  }
}
// 保存账号设置到数据库，并用返回值刷新开关
async function saveSettings() {
  try {
    const s = await updateUserSettings(profile.value.id, { ...settingsModel })
    settingsModel.msgNotify = s.msgNotify
    settingsModel.smsNotify = s.smsNotify
    settingsModel.loginRemind = s.loginRemind
    settingsModel.weeklyReport = s.weeklyReport
    ElMessage.success('账号设置已保存')
  } catch (e) {
    // 错误提示由请求拦截器统一处理
  }
}
</script>

<style scoped>
/* 左右分栏：左 40% / 右 60%（el-col 的 span 只能取 1/24 的整数倍，无法精确表达 40%，故用自定义宽度覆盖默认栅格） */
.profile-col-left {
  flex: 0 0 40%;
  max-width: 40%;
}
.profile-col-right {
  flex: 0 0 60%;
  max-width: 60%;
}

/* 左侧资料卡：填满所在列 */
.profile-card {
  width: 100%;
  padding: 28px 24px;
  text-align: center;
}
/* 右侧标签页面板：填满所在列 */
.profile-panel {
  width: 100%;
}
/* 头像容器：宽度锁定为头像尺寸，供相机按钮绝对定位 */
.avatar-wrap {
  position: relative;
  width: 84px;
  margin: 0 auto;
}
/* 隐藏原生文件选择框，点击相机按钮时由代码触发 */
.avatar-input {
  display: none;
}
/* 无头像时的默认底色 */
.big-avatar {
  background: linear-gradient(135deg, #10b981, #0ea5e9);
}
/* 相机按钮：叠在头像右下角，白色描边与卡片背景分隔 */
.avatar-btn {
  position: absolute;
  right: -6px;
  bottom: 0;
  border: 2px solid #fff;
}
/* 姓名与角色标签 */
.p-name {
  margin: 14px 0 10px;
  font-size: 19px;
}
.p-tags {
  display: flex;
  justify-content: center;
  gap: 6px;
}
/* 资料元信息列表：整体左对齐，抵消卡片的居中文本 */
.meta-list {
  text-align: left;
}
/* 信息列表：字号 15px，配合 1.8 行高拉开各行间距 */
.meta-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  line-height: 1.8;
  padding: 10px 0;
  color: #64748b;
}
/* 标签列宽随字号同步加宽，避免"账号/手机/企业/注册"被挤压换行 */
.meta-item .m-label {
  color: #94a3b8;
  width: 56px;
  flex-shrink: 0;
}
.meta-item .m-value {
  color: #334155;
}
/* 机构名称过长时单行省略，避免撑破卡片宽度 */
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 右侧标签导航 */
.profile-tabs { padding: 0 8px; }
.profile-tabs :deep(.el-tabs__header) {
  margin: 0;
  padding: 0 12px;
}
/* 用标签间距替代内边距，使下划线宽度正好等于文字宽度 */
.profile-tabs :deep(.el-tabs__nav) {
  gap: 32px;
}
.profile-tabs :deep(.el-tabs__item) {
  padding: 0;
  font-weight: 400;
  color: #64748b;
}
/* 选中项：加粗字重，配合下方等宽下划线突出当前页 */
.profile-tabs :deep(.el-tabs__item.is-active) {
  font-weight: 600;
}
.tab-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
/* 手机端标签页：缩小标题间距与字号；标题超出面板宽度时由 Element Plus 自带的横向滑动/箭头滚动 */
.profile-tabs.tabs-mobile :deep(.el-tabs__nav) {
  gap: 18px;
}
.profile-tabs.tabs-mobile :deep(.el-tabs__item) {
  font-size: 13px;
}

/* 表单区：限制最大宽度并居左对齐 */
.tab-form {
  padding: 24px 24px 8px;
  max-width: 600px;
}
/* 标签右对齐时与输入框保持固定间距，形成整齐的垂直对齐线 */
.tab-form :deep(.el-form-item__label) {
  padding-right: 16px;
}
/* 输入框与文本域：增加圆角，边框颜色调浅 */
.tab-form :deep(.el-input__wrapper),
.tab-form :deep(.el-textarea__inner) {
  border-radius: 6px;
  box-shadow: 0 0 0 1px #d9d9d9 inset;
  transition: box-shadow .2s;
}
.tab-form :deep(.el-input__wrapper:hover),
.tab-form :deep(.el-textarea__inner:hover) {
  box-shadow: 0 0 0 1px #c0c4cc inset;
}
/* 聚焦：边框变主题绿，并带一层微弱外发光 */
.tab-form :deep(.el-input__wrapper.is-focus),
.tab-form :deep(.el-textarea__inner:focus) {
  box-shadow: 0 0 0 1px var(--primary) inset, 0 0 0 2px rgba(0, 181, 120, .1);
}
/* 只读输入框：文字调浅、光标提示不可编辑 */
.tab-form :deep(.el-input.is-disabled .el-input__wrapper) {
  background: #fafafa;
  box-shadow: 0 0 0 1px #e5e7eb inset;
  cursor: not-allowed;
}
.tab-form :deep(.el-input.is-disabled .el-input__inner) {
  color: #999;
  -webkit-text-fill-color: #999;
  cursor: not-allowed;
}
/* 只读展示项（角色标签）：光标提示不可编辑 */
.readonly-item :deep(.el-tag) {
  cursor: not-allowed;
}
/* 字数统计：固定贴住文本域右下角 */
.tab-form :deep(.el-textarea .el-input__count) {
  right: 12px;
  bottom: 6px;
}
/* 角色不可修改的辅助说明 */
.readonly-tip {
  font-size: 12px;
  color: #94a3b8;
  margin-left: 10px;
}
/* 表单底部按钮组：水平居中，主次按钮间距 12px */
.form-actions :deep(.el-form-item__content) {
  justify-content: center;
  gap: 12px;
}
.form-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
/* 主按钮：适度加宽并加弱阴影，强化主操作 */
.form-actions :deep(.el-button--primary),
.setting-actions :deep(.el-button--primary) {
  padding: 0 24px;
  box-shadow: 0 2px 6px rgba(16, 185, 129, .25);
}

/* 密码强度：三段等宽色块，按 weak / medium / strong 着色 */
.strength-bar {
  display: flex;
  gap: 6px;
}
.strength-seg {
  width: 60px;
  height: 8px;
  border-radius: 4px;
  background: #e2e8f0;
}
.strength-seg.weak { background: #ef4444; }
.strength-seg.medium { background: #f59e0b; }
.strength-seg.strong { background: #10b981; }
.strength-text {
  margin-left: 10px;
  font-size: 13px;
  color: #64748b;
}

/* 验证码：输入框自适应，按钮固定宽度避免被压缩 */
.code-row {
  display: flex;
  gap: 10px;
  width: 100%;
}
.code-btn {
  flex-shrink: 0;
  width: 130px;
}

/* 账号设置：开关列表沿用表单宽度，底部为保存操作 */
.settings-list {
  max-width: 600px;
}
.setting-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 0;
}
.s-title {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
}
.s-desc {
  font-size: 12px;
  color: #94a3b8;
  margin-top: 3px;
}
.setting-actions {
  text-align: right;
  padding-bottom: 16px;
}

/* 手机端：左右两栏改为上下堆叠，并收紧留白保证输入框可用宽度 */
/* 移动端断点 ≤767px（与 useDevice 的移动端判定一致） */
@media (max-width: 767px) {
  .profile-col-left,
  .profile-col-right {
    flex: 0 0 100%;
    max-width: 100%;
  }
  .profile-card {
    padding: 20px 16px;
  }
  .tab-form {
    padding: 16px 12px 8px;
  }
  /* 验证码按钮适当收窄，避免与新手机号输入框互相挤压 */
  .code-btn {
    width: 112px;
  }
}
</style>
