<!--
  Login.vue —— 登录页
  用途：系统统一登录入口，用户选择角色（企业端/审核端/监管端/管理员）
  并输入账号密码完成登录，登录成功后根据角色跳转对应端首页。
  路由：/login；登录成功后由 handleLogin 按角色跳转到对应端首页。
  左侧展示品牌与特性介绍，右侧为登录表单，并提供演示账号快捷填充。
-->
<template>
  <div class="login-page">
    <!-- 背景层：网格 + 两处高光光晕 -->
    <div class="login-bg">
      <div class="bg-grid"></div>
      <div class="bg-glow glow-1"></div>
      <div class="bg-glow glow-2"></div>
    </div>

    <div class="login-wrapper">
      <!-- 左侧品牌介绍区：logo + 系统名称 + 特性列表 -->
      <div class="brand-section">
        <div class="logo">
          <el-icon :size="28"><Connection /></el-icon>
        </div>
        <h1 class="brand-name">碳链通</h1>
        <p class="brand-sub">基于区块链的企业碳排放数据认证与追溯系统</p>
        <div class="brand-features">
          <div class="feature-item">
            <el-icon><Lock /></el-icon>
            <span>数据上链 · 不可篡改</span>
          </div>
          <div class="feature-item">
            <el-icon><Stamp /></el-icon>
            <span>权威认证 · 全流程留痕</span>
          </div>
          <div class="feature-item">
            <el-icon><Search /></el-icon>
            <span>全程追溯 · 来源可查</span>
          </div>
        </div>
      </div>

      <!-- 右侧登录卡片：角色选择 + 账号密码表单 + 演示账号 -->
      <div class="login-card">
        <div class="login-header">
          <h2>欢迎登录</h2>
          <p>请选择角色并输入账号信息</p>
        </div>

        <!-- 登录表单 -->
        <el-form ref="formRef" :model="form" :rules="rules" size="large" @submit.prevent>
          <!-- 角色选择：企业端/审核端/监管端/管理员 -->
          <el-form-item prop="role">
            <el-radio-group v-model="form.role" class="role-group">
              <el-radio-button value="enterprise">
                <el-icon><OfficeBuilding /></el-icon>
                <span>企业端</span>
              </el-radio-button>
              <el-radio-button value="auditor">
                <el-icon><Stamp /></el-icon>
                <span>审核端</span>
              </el-radio-button>
              <el-radio-button value="regulator">
                <el-icon><View /></el-icon>
                <span>监管端</span>
              </el-radio-button>
              <el-radio-button value="admin">
                <el-icon><Setting /></el-icon>
                <span>管理员</span>
              </el-radio-button>
            </el-radio-group>
          </el-form-item>

          <!-- 账号输入框 -->
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="请输入账号" :prefix-icon="User" />
          </el-form-item>

          <!-- 密码输入框：支持显示/隐藏，回车触发登录 -->
          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="请输入密码" :prefix-icon="Lock" show-password @keyup.enter="handleLogin" />
          </el-form-item>

          <!-- 记住登录状态选项 -->
          <el-form-item>
            <el-checkbox v-model="form.remember">记住登录状态</el-checkbox>
          </el-form-item>

          <!-- 登录按钮：带 loading 状态 -->
          <el-button type="primary" class="login-btn" :loading="loading" @click="handleLogin">
            登 录
          </el-button>
        </el-form>

        <!-- 演示账号区：点击快捷填充，并提供公共查询端入口 -->
        <div class="demo-hint">
          <div class="hint-title">演示账号（密码均为 123456）</div>
          <div class="hint-list">
            <span @click="fillDemo('enterprise01')">enterprise01</span>
            <span @click="fillDemo('auditor01')">auditor01</span>
            <span @click="fillDemo('regulator01')">regulator01</span>
            <span @click="fillDemo('admin')">admin</span>
          </div>
          <div class="public-entry" @click="goPublic">
            <el-icon><Search /></el-icon> 进入公共查询端（无需登录）
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// 导入 Vue 响应式 API
import { ref, reactive } from 'vue'
// 导入路由实例
import { useRouter } from 'vue-router'
// 导入 Element Plus 消息提示
import { ElMessage } from 'element-plus'
// 导入账号、密码输入框前缀图标
import { User, Lock } from '@element-plus/icons-vue'
// 导入用户状态仓库
import { useUserStore } from '@/store'
// 导入登录接口
import { login } from '@/api/auth'

// 演示账号（纯 UI 快捷填充便利，登录校验仍走后端）
const demoAccounts = [
  { username: 'enterprise01', password: '123456', role: 'enterprise', name: '张建国', enterpriseId: 'ENT20240001' },
  { username: 'auditor01', password: '123456', role: 'auditor', name: '陈审核', orgId: 'AUD001' },
  { username: 'regulator01', password: '123456', role: 'regulator', name: '周主任' },
  { username: 'admin', password: '123456', role: 'admin', name: '系统管理员' }
]

// 路由实例：登录成功后按角色跳转
const router = useRouter()
// 用户状态仓库：保存 token 与用户信息快照
const userStore = useUserStore()
// 表单引用（用于校验）
const formRef = ref()
// 登录按钮 loading 状态
const loading = ref(false)

// 登录表单数据：角色、账号、密码、记住登录
const form = reactive({
  role: 'enterprise',
  username: '',
  password: '',
  remember: true
})

// 表单校验规则：账号、密码必填
const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

// 演示账号快捷填充：自动填入账号密码并匹配角色
function fillDemo(username) {
  form.username = username
  form.password = '123456'
  const acc = demoAccounts.find(a => a.username === username)
  if (acc) form.role = acc.role
}

// 跳转公共查询端（无需登录）
function goPublic() {
  router.push('/public/query')
}

// 登录处理：校验通过后调用后端登录接口，成功则写入用户状态并按角色跳转首页
async function handleLogin() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const { token, user } = await login({
      username: form.username,
      password: form.password,
      role: form.role
    })
    userStore.login(user, token)
    ElMessage.success('登录成功')
    // 各角色登录后跳转首页映射
    const redirectMap = { enterprise: '/enterprise/home', auditor: '/auditor/home', regulator: '/regulator/home', admin: '/regulator/home' }
    router.push(redirectMap[user.role] || '/enterprise/home')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* 登录页根容器：占满视口，深色背景 */
.login-page {
  position: relative;
  height: 100vh;
  width: 100%;
  overflow: hidden;
  background: #0f172a;
}

/* 背景层：绝对定位覆盖全屏 */
.login-bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
}

/* 背景网格线 */
.bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(16, 185, 129, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(16, 185, 129, 0.06) 1px, transparent 1px);
  background-size: 48px 48px;
}

/* 背景光晕：模糊圆形渐变 */
.bg-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.5;
}
.glow-1 {
  width: 500px; height: 500px;
  background: #10b981;
  top: -100px; left: -100px;
  opacity: 0.25;
}
.glow-2 {
  width: 600px; height: 600px;
  background: #0ea5e9;
  bottom: -200px; right: -150px;
  opacity: 0.2;
}

/* 登录内容包裹层：居中、左右品牌与卡片布局 */
.login-wrapper {
  position: relative;
  z-index: 2;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 80px;
  padding: 40px;
}

/* 左侧品牌介绍区 */
.brand-section {
  color: #fff;
  max-width: 420px;
}

/* 品牌图标：绿蓝渐变圆角方块 */
.logo {
  width: 60px;
  height: 60px;
  border-radius: 16px;
  background: linear-gradient(135deg, #10b981, #0ea5e9);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 24px;
}

/* 品牌名称：渐变文字 */
.brand-name {
  font-size: 44px;
  font-weight: 800;
  margin: 0 0 12px;
  background: linear-gradient(135deg, #6ee7b7, #7dd3fc);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.brand-sub {
  font-size: 16px;
  color: #94a3b8;
  margin: 0 0 40px;
  line-height: 1.6;
}

/* 品牌特性列表 */
.brand-features {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 单条特性：图标 + 文案横向排列 */
.feature-item {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #cbd5e1;
  font-size: 15px;
}

.feature-item .el-icon {
  color: #10b981;
  font-size: 18px;
}

/* 右侧登录卡片：圆角、阴影、毛玻璃效果 */
.login-card {
  width: 420px;
  background: rgba(255, 255, 255, 0.98);
  border-radius: 20px;
  padding: 40px 36px;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.3);
  backdrop-filter: blur(10px);
}

/* 登录卡片头部 */
.login-header {
  margin-bottom: 28px;
}
.login-header h2 {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 700;
  color: #0f172a;
}
.login-header p {
  margin: 0;
  font-size: 13px;
  color: #94a3b8;
}

/* 角色单选按钮组：等分宽度 */
.role-group {
  width: 100%;
  display: flex;
}
.role-group :deep(.el-radio-button) {
  flex: 1;
}
.role-group :deep(.el-radio-button__inner) {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border-radius: 8px !important;
}

/* 登录按钮：满宽、字间距加大 */
.login-btn {
  width: 100%;
  height: 44px;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 4px;
  border-radius: 10px;
}

/* 演示账号提示区 */
.demo-hint {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px dashed #e2e8f0;
}
.hint-title {
  font-size: 12px;
  color: #94a3b8;
  margin-bottom: 10px;
}
.hint-list {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
/* 演示账号标签：可点击，悬停高亮 */
.hint-list span {
  padding: 4px 12px;
  background: #ecfdf5;
  color: #059669;
  border-radius: 14px;
  font-size: 12px;
  cursor: pointer;
  transition: all .2s;
}
.hint-list span:hover {
  background: #10b981;
  color: #fff;
}
/* 公共查询端入口 */
.public-entry {
  margin-top: 14px;
  text-align: center;
  font-size: 13px;
  color: #10b981;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  transition: opacity .2s;
}
.public-entry:hover {
  opacity: 0.7;
  text-decoration: underline;
}

/* ============ 移动端：碳护照式登录 ============ */
/* 设计概念：深墨绿底 + 青柠信号色，像一张可核验的碳凭证 */
/* 移动端断点 ≤767px（与 useDevice 的移动端判定一致） */
@media (max-width: 767px) {
  .login-page {
    height: auto;
    min-height: 100vh;
    overflow-y: auto;
    background: #071a16;
  }
  @supports (min-height: 100dvh) {
    .login-page {
      min-height: 100dvh;
    }
  }
  /* 细线内框：证书/护照的压印边 */
  .login-page::after {
    content: '';
    position: fixed;
    inset: 10px;
    border: 1px solid rgba(200, 241, 105, 0.22);
    border-radius: 18px;
    pointer-events: none;
    z-index: 5;
  }
  .bg-grid {
    opacity: 0.55;
  }
  .glow-1 {
    background: #c8f169;
    opacity: 0.16;
    top: -140px;
    left: -120px;
  }
  .glow-2 {
    background: #0f9d76;
    opacity: 0.22;
    bottom: -180px;
    right: -120px;
  }

  .login-wrapper {
    height: auto;
    min-height: inherit;
    flex-direction: column;
    justify-content: flex-start;
    gap: 26px;
    padding: 42px 22px 30px;
  }

  .brand-section {
    max-width: none;
    text-align: left;
  }
  .logo {
    width: 46px;
    height: 46px;
    border-radius: 13px;
    margin: 0 0 16px;
    background: linear-gradient(140deg, #c8f169, #0f9d76);
    color: #071a16;
  }
  .brand-name {
    margin: 0 0 8px;
    font-family: var(--m-serif, "Songti SC", "Noto Serif SC", serif);
    font-size: 40px;
    font-weight: 700;
    letter-spacing: 0.04em;
    background: none;
    -webkit-text-fill-color: #f2f7f3;
    color: #f2f7f3;
  }
  .brand-sub {
    max-width: 300px;
    margin: 0 0 14px;
    font-size: 12px;
    line-height: 1.7;
    color: #8aa39a;
  }
  /* 机读区微文本 */
  .brand-section::after {
    content: 'BLOCKCHAIN CARBON LEDGER';
    display: block;
    font-family: var(--m-mono, ui-monospace, monospace);
    font-size: 9px;
    letter-spacing: 0.34em;
    color: rgba(200, 241, 105, 0.6);
  }
  /* 特性列表改为账本条目 */
  .brand-features {
    display: flex;
    flex-direction: column;
    gap: 0;
    margin-top: 20px;
    border-top: 1px solid rgba(255, 255, 255, 0.08);
  }
  .feature-item {
    padding: 10px 0;
    font-size: 12px;
    color: #a9bdb4;
    border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  }
  .feature-item .el-icon {
    font-size: 14px;
    color: var(--m-signal, #c8f169);
  }
  .feature-item::after {
    content: '✓';
    margin-left: auto;
    font-size: 11px;
    color: rgba(200, 241, 105, 0.5);
  }

  /* 登录卡片：深色凭证面板 */
  .login-card {
    width: 100%;
    padding: 22px 18px 20px;
    border-radius: 18px;
    background: #0d2a22;
    border: 1px solid rgba(200, 241, 105, 0.14);
    box-shadow: 0 24px 50px rgba(0, 0, 0, 0.35);
    -webkit-backdrop-filter: none;
    backdrop-filter: none;
  }
  .login-header {
    margin-bottom: 18px;
  }
  .login-header h2 {
    font-family: var(--m-serif, "Songti SC", serif);
    font-size: 22px;
    color: #f2f7f3;
  }
  .login-header p {
    font-size: 11px;
    letter-spacing: 0.04em;
    color: #7f978d;
  }

  /* 角色选择：2×2 深色方块，选中态为青柠实底 */
  .role-group {
    flex-wrap: wrap;
    gap: 8px;
  }
  .role-group :deep(.el-radio-button) {
    flex: 0 0 calc(50% - 4px);
  }
  .role-group :deep(.el-radio-button__inner) {
    width: 100%;
    height: 42px;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    background: rgba(255, 255, 255, 0.04);
    border: 1px solid rgba(255, 255, 255, 0.08) !important;
    border-radius: 12px !important;
    box-shadow: none !important;
    color: #a9bdb4;
    font-size: 13px;
  }
  .role-group :deep(.el-radio-button.is-active .el-radio-button__inner) {
    background: var(--m-signal, #c8f169);
    border-color: var(--m-signal, #c8f169) !important;
    color: #071a16;
    font-weight: 700;
  }

  /* 输入框：深底浅字 */
  .login-card :deep(.el-input__wrapper) {
    min-height: 44px;
    background: rgba(255, 255, 255, 0.05);
    box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.09) inset;
    border-radius: 12px;
  }
  .login-card :deep(.el-input__wrapper.is-focus) {
    box-shadow: 0 0 0 1.5px var(--m-signal, #c8f169) inset;
  }
  .login-card :deep(.el-input__inner) {
    color: #f2f7f3;
  }
  .login-card :deep(.el-input__inner::placeholder) {
    color: #6f847c;
  }
  .login-card :deep(.el-input__prefix) {
    color: #7f978d;
  }
  .login-card :deep(.el-checkbox__label) {
    font-size: 12px;
    color: #8aa39a;
  }
  .login-card :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
    background: var(--m-signal, #c8f169);
    border-color: var(--m-signal, #c8f169);
  }
  .login-card :deep(.el-checkbox__input.is-checked + .el-checkbox__label) {
    color: #c8f169;
  }

  .login-btn {
    width: 100%;
    height: 46px;
    border-radius: 12px;
    background: var(--m-signal, #c8f169);
    border-color: var(--m-signal, #c8f169);
    color: #071a16;
    font-size: 15px;
    font-weight: 700;
    letter-spacing: 0.28em;
  }
  .login-btn:hover,
  .login-btn:focus {
    background: #d6f78a;
    border-color: #d6f78a;
    color: #071a16;
  }

  /* 演示账号：等宽字体的账号胶囊 */
  .demo-hint {
    margin-top: 18px;
    padding-top: 16px;
    border-top: 1px dashed rgba(255, 255, 255, 0.1);
  }
  .hint-title {
    font-size: 10px;
    letter-spacing: 0.08em;
    color: #6f847c;
  }
  .hint-list {
    gap: 8px;
  }
  .hint-list span {
    padding: 5px 11px;
    border: 1px solid rgba(255, 255, 255, 0.08);
    border-radius: 8px;
    background: rgba(255, 255, 255, 0.05);
    color: #b9cdc4;
    font-family: var(--m-mono, ui-monospace, monospace);
    font-size: 11px;
  }
  .hint-list span:hover {
    background: rgba(200, 241, 105, 0.12);
    color: #c8f169;
  }
  .public-entry {
    font-size: 12px;
    color: var(--m-signal, #c8f169);
  }
}
</style>
