<!--
  ChangePassword.vue —— 首次登录强制改密页
  用途：演示账号与管理员重置密码后，会话被后端标记为 mustChangePassword=true，
        前端在登录成功后直接跳转本页，未完成改密前无法进入任何业务布局。
  路由：/change-password（独立路由，不经过 Layout）。
  交互：校验新密码强度（≥8 位、含大小写字母与数字、不得为 123456）→ 调用
        POST /api/auth/change-password → 清理本地登录态 → 跳转登录页重新登录。
  说明：改密成功后旧令牌仍带强制改密声明，服务端会继续拒绝其访问业务接口，
        因此必须重新登录获取新令牌，这一步不是前端“多此一举”。
-->
<template>
  <div class="cp-page">
    <!-- 背景层：网格 + 两处光晕，与登录页保持同一视觉语言 -->
    <div class="cp-bg">
      <div class="cp-grid"></div>
      <div class="cp-glow g1"></div>
      <div class="cp-glow g2"></div>
    </div>

    <div class="cp-card">
      <div class="cp-logo">
        <el-icon :size="26"><Lock /></el-icon>
      </div>
      <h1>首次登录 · 修改密码</h1>
      <p class="cp-sub">当前账号仍在使用初始密码，为保障数据安全，请先设置新密码</p>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" label-position="top" @submit.prevent>
        <!-- 当前账号只读展示，账号本身不在本页修改 -->
        <el-form-item label="账号">
          <el-input :model-value="username" disabled />
        </el-form-item>

        <el-form-item label="当前密码" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入当前密码" />
        </el-form-item>

        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password placeholder="8-20 位，含大小写字母和数字" />
        </el-form-item>

        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="请再次输入新密码" @keyup.enter="submit" />
        </el-form-item>

        <!-- 密码规则实时提示：满足一项即高亮，降低提交后反复报错的挫败感 -->
        <ul class="cp-rules">
          <li :class="{ ok: ruleState.length }">长度不少于 8 位</li>
          <li :class="{ ok: ruleState.caseMix }">同时包含大写字母与小写字母</li>
          <li :class="{ ok: ruleState.digit }">包含数字</li>
          <li :class="{ ok: ruleState.notDefault }">不能使用初始密码 123456</li>
        </ul>

        <el-button type="primary" class="cp-btn" :loading="loading" @click="submit">确认修改</el-button>
        <el-button text class="cp-back" @click="backToLogin">返回登录</el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup>
// 导入 Vue 响应式 API
import { ref, reactive, computed } from 'vue'
// 导入路由实例：改密成功后跳转登录页
import { useRouter } from 'vue-router'
// 导入 Element Plus 消息提示
import { ElMessage } from 'element-plus'
// 导入页面使用的锁图标
import { Lock } from '@element-plus/icons-vue'
// 导入用户状态仓库：读取当前账号、改密后清理登录态
import { useUserStore } from '@/store'
// 导入强制改密接口
import { changePassword } from '@/api/auth'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref()
const loading = ref(false)
// 当前账号取自登录快照，仅用于展示，不接受用户修改
const username = computed(() => userStore.user?.username || '')

// 改密表单：当前密码、新密码、确认新密码
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

// 密码规则实时状态：与后端校验规则保持一致
const ruleState = computed(() => {
  const pwd = form.newPassword.trim()
  return {
    length: pwd.length >= 8,
    caseMix: /[A-Z]/.test(pwd) && /[a-z]/.test(pwd),
    digit: /\d/.test(pwd),
    notDefault: pwd.length > 0 && pwd !== '123456'
  }
})

// 新密码自定义校验：必填 + 四项强度规则；与确认密码联动校验
const validateNewPwd = (rule, value, callback) => {
  const pwd = (value || '').trim()
  if (!pwd) {
    callback(new Error('请输入新密码'))
    return
  }
  if (pwd.length < 8) {
    callback(new Error('新密码长度不能少于 8 位'))
    return
  }
  if (pwd === '123456') {
    callback(new Error('新密码不能使用初始密码 123456'))
    return
  }
  if (!(/[A-Z]/.test(pwd) && /[a-z]/.test(pwd) && /\d/.test(pwd))) {
    callback(new Error('新密码需同时包含大写字母、小写字母和数字'))
    return
  }
  // 新密码变化后，若确认框已填写则同步触发一次一致性校验
  if (form.confirmPassword) {
    formRef.value.validateField('confirmPassword')
  }
  callback()
}

// 确认密码校验：必填且与新密码完全一致
const validateConfirmPwd = (rule, value, callback) => {
  if (!value) callback(new Error('请再次输入新密码'))
  else if (value !== form.newPassword) callback(new Error('两次输入的密码不一致'))
  else callback()
}

const rules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [{ required: true, validator: validateNewPwd, trigger: 'blur' }],
  confirmPassword: [{ required: true, validator: validateConfirmPwd, trigger: 'blur' }]
}

// 提交改密：成功后必须清理本地登录态并重新登录
async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await changePassword({
      oldPassword: form.oldPassword,
      newPassword: form.newPassword
    })
    ElMessage.success('密码修改成功，请使用新密码重新登录')
    userStore.logout()
    router.replace('/login')
  } catch (e) {
    // 错误提示由请求拦截器统一处理，此处不重复弹出
  } finally {
    loading.value = false
  }
}

// 返回登录：主动清理本地令牌，避免强制改密会话残留在本地存储
function backToLogin() {
  userStore.logout()
  router.replace('/login')
}
</script>

<style scoped>
/* 页面根容器：占满视口，深色背景与登录页一致 */
.cp-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 20px;
  overflow: hidden;
  background: #0f172a;
}

/* 背景层：绝对定位覆盖全屏 */
.cp-bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
}

/* 背景网格线 */
.cp-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(16, 185, 129, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(16, 185, 129, 0.06) 1px, transparent 1px);
  background-size: 48px 48px;
}

/* 背景光晕 */
.cp-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
}
.cp-glow.g1 {
  width: 460px; height: 460px;
  background: #10b981;
  top: -120px; left: -100px;
  opacity: 0.22;
}
.cp-glow.g2 {
  width: 520px; height: 520px;
  background: #0ea5e9;
  bottom: -180px; right: -140px;
  opacity: 0.18;
}

/* 改密卡片 */
.cp-card {
  position: relative;
  z-index: 2;
  width: 440px;
  max-width: 100%;
  padding: 38px 36px 30px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.98);
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.3);
}

/* 顶部锁形图标：绿蓝渐变圆角方块 */
.cp-logo {
  width: 54px;
  height: 54px;
  border-radius: 15px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: linear-gradient(135deg, #10b981, #0ea5e9);
  margin-bottom: 20px;
}

.cp-card h1 {
  margin: 0 0 8px;
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
}

.cp-sub {
  margin: 0 0 24px;
  font-size: 13px;
  line-height: 1.6;
  color: #94a3b8;
}

/* 密码规则清单：默认灰色，满足后变绿 */
.cp-rules {
  list-style: none;
  margin: 2px 0 18px;
  padding: 12px 14px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}
.cp-rules li {
  position: relative;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.9;
  color: #94a3b8;
}
.cp-rules li::before {
  content: '○';
  position: absolute;
  left: 0;
  font-size: 10px;
}
.cp-rules li.ok {
  color: #059669;
}
.cp-rules li.ok::before {
  content: '●';
}

/* 主按钮：满宽、加粗 */
.cp-btn {
  width: 100%;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 2px;
  border-radius: 10px;
}

/* 返回登录：弱化为文字按钮 */
.cp-back {
  width: 100%;
  margin: 8px 0 0;
  color: #94a3b8;
}

/* 移动端：与登录页一致的深墨绿凭证风格 */
@media (max-width: 767px) {
  .cp-page {
    background: #071a16;
    padding: 28px 18px;
  }
  .cp-grid {
    opacity: 0.55;
  }
  .cp-glow.g1 {
    background: #c8f169;
    opacity: 0.15;
  }
  .cp-glow.g2 {
    background: #0f9d76;
    opacity: 0.2;
  }
  .cp-card {
    padding: 26px 20px 22px;
    background: #0d2a22;
    border: 1px solid rgba(200, 241, 105, 0.14);
    box-shadow: 0 24px 50px rgba(0, 0, 0, 0.35);
  }
  .cp-logo {
    background: linear-gradient(140deg, #c8f169, #0f9d76);
    color: #071a16;
  }
  .cp-card h1 {
    color: #f2f7f3;
  }
  .cp-sub {
    color: #8aa39a;
  }
  .cp-card :deep(.el-form-item__label) {
    color: #a9bdb4;
  }
  .cp-card :deep(.el-input__wrapper) {
    min-height: 44px;
    background: rgba(255, 255, 255, 0.05);
    box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.09) inset;
    border-radius: 12px;
  }
  .cp-card :deep(.el-input__inner) {
    color: #f2f7f3;
  }
  .cp-card :deep(.el-input.is-disabled .el-input__inner) {
    color: #8aa39a;
  }
  .cp-rules {
    background: rgba(255, 255, 255, 0.04);
    border-color: rgba(255, 255, 255, 0.08);
  }
  .cp-rules li {
    color: #7f978d;
  }
  .cp-rules li.ok {
    color: #c8f169;
  }
  .cp-btn {
    height: 46px;
    background: #c8f169;
    border-color: #c8f169;
    color: #071a16;
    letter-spacing: 0.28em;
  }
  .cp-back {
    color: #8aa39a;
  }
}
</style>
