<!--
  Layout.vue —— 后台主框架布局
  用途：登录后各角色（企业端/审核端/监管端/管理员）共用的整体布局，
  包含左侧可折叠侧边栏、顶部面包屑与用户信息、中间路由出口内容区。
  侧边栏菜单根据当前用户角色从 router 中的 menuConfig 动态渲染。
-->
<template>
  <div class="layout">
    <!-- 左侧侧边栏（支持折叠/展开） -->
    <aside class="sidebar" :class="{ collapsed }">
      <!-- 侧边栏顶部：Logo 与折叠按钮 -->
      <div class="sidebar-header">
        <div class="logo">
          <el-icon :size="22"><Connection /></el-icon>
          <span v-show="!collapsed" class="logo-text">碳链通</span>
        </div>
        <el-icon class="collapse-btn" @click="collapsed = !collapsed">
          <Fold v-if="!collapsed" />
          <Expand v-else />
        </el-icon>
      </div>

      <!-- 主导航菜单：根据角色 portalKey 取对应菜单项，router 模式点击即跳转 -->
      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed"
        :collapse-transition="false"
        background-color="#0f172a"
        text-color="#94a3b8"
        active-text-color="#10b981"
        router
      >
        <div class="portal-title" v-show="!collapsed">{{ portalTitle }}</div>
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>

      <!-- 侧边栏底部：联盟链区块数量状态展示 -->
      <div class="sidebar-footer" v-show="!collapsed">
        <div class="chain-status">
          <span class="dot"></span>
          <span>联盟链 · {{ blockCount }} 个区块</span>
        </div>
      </div>
    </aside>

    <!-- 右侧主区域：顶部栏 + 内容区 -->
    <div class="main-area">
      <!-- 顶部栏：面包屑 + 链状态徽章 + 用户下拉菜单 -->
      <header class="topbar">
        <div class="topbar-left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: homePath }">{{ portalTitle }}</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="topbar-right">
          <!-- 区块链状态徽章（显示区块数） -->
          <el-tooltip content="区块链状态正常">
            <el-badge :value="blockCount" :max="999" class="chain-badge">
              <el-icon :size="20" color="#10b981"><Link /></el-icon>
            </el-badge>
          </el-tooltip>

          <!-- 用户信息下拉菜单：个人中心 / 退出登录 -->
          <el-dropdown @command="handleCommand">
            <div class="user-info">
              <el-avatar :size="32" :icon="UserFilled" />
              <div class="user-text">
                <div class="user-name">{{ userStore.user?.name }}</div>
                <div class="user-role">{{ userStore.roleLabel }}</div>
              </div>
              <el-icon><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <!-- 内容区：路由出口，带淡入淡出过渡动画 -->
      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
// 导入 Vue 响应式 API
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
// 导入路由相关方法
import { useRouter, useRoute } from 'vue-router'
// 导入 Element Plus 消息提示组件
import { ElMessageBox, ElMessage } from 'element-plus'
// 导入用户默认头像图标
import { UserFilled } from '@element-plus/icons-vue'
// 导入全局用户状态仓库
import { useUserStore } from '@/store'
// 导入各角色菜单配置
import { menuConfig } from '@/router'
// 导入区块链统计接口
import { getBlockchainStats } from '@/api/blockchain'
// 导入全局数据刷新信号（各页面注册自己的加载函数后即可被自动刷新）
import { refreshTick } from '@/composables/useAutoRefresh'

// 路由实例与当前路由
const router = useRouter()
const route = useRoute()
// 用户状态
const userStore = useUserStore()
// 侧边栏折叠状态
const collapsed = ref(false)
// 区块链区块数量
const blockCount = ref(0)

// ============ 数据自动刷新（定时轮询）============
// 轮询间隔：每 15 秒通知当前页面通过接口重新拉取一次数据
const REFRESH_INTERVAL = 15000
// 定时器句柄
let refreshTimer = null

// 离开布局时停止轮询
onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = null
})

// 挂载后拉取链上区块数量，并启动数据自动刷新轮询
onMounted(async () => {
  try {
    const stats = await getBlockchainStats()
    blockCount.value = stats.blockCount || 0
  } catch (e) {
    blockCount.value = 0
  }
  refreshTimer = setInterval(() => {
    // 标签页不可见时不打扰，避免无谓请求
    if (document.hidden) return
    // 自增刷新信号：已注册的页面会用接口重新拉取数据，组件不重新挂载
    refreshTick.value++
  }, REFRESH_INTERVAL)
})

// 当前用户的门户 key：admin 复用监管端菜单，其余取自身角色
const portalKey = computed(() => {
  const role = userStore.role
  if (role === 'admin') return 'regulator'
  return role
})
// 当前门户标题
const portalTitle = computed(() => menuConfig[portalKey.value]?.title || '')
// 当前门户对应的菜单项列表
const menuItems = computed(() => menuConfig[portalKey.value]?.items || [])

// 当前激活的菜单项（即当前路由路径）
const activeMenu = computed(() => route.path)
// 当前页面标题（取路由 meta.title，用于面包屑）
const currentTitle = computed(() => route.meta.title || '')

// 各端首页路径（用于面包屑）
const homePath = computed(() => {
  const map = { enterprise: '/enterprise/home', auditor: '/auditor/home', regulator: '/regulator/home', admin: '/regulator/home' }
  return map[userStore.role] || '/enterprise/home'
})

// 用户下拉菜单命令处理：profile 跳个人中心；logout 确认后登出并跳登录页
function handleCommand(cmd) {
  if (cmd === 'logout') {
    ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    }).then(() => {
      userStore.logout()
      ElMessage.success('已退出登录')
      router.push('/login')
    }).catch(() => {})
  } else if (cmd === 'profile') {
    router.push('/profile')
  }
}
</script>

<style scoped>
/* 整体布局容器：左右分栏，占满视口 */
.layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* 左侧侧边栏：深色背景，支持宽度过渡动画 */
.sidebar {
  width: 230px;
  background: #0f172a;
  display: flex;
  flex-direction: column;
  transition: width .25s ease;
  flex-shrink: 0;
}
.sidebar.collapsed {
  width: 64px;
}

/* 侧边栏头部：Logo 区与折叠按钮 */
.sidebar-header {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #10b981;
  font-weight: 700;
}
.logo-text {
  font-size: 18px;
  color: #fff;
}

/* 折叠/展开按钮 */
.collapse-btn {
  color: #64748b;
  cursor: pointer;
  font-size: 18px;
}
.collapse-btn:hover {
  color: #fff;
}

/* 侧边栏菜单：去除右边框，占满剩余高度 */
.sidebar :deep(.el-menu) {
  border-right: none;
  flex: 1;
}
/* 门户分组标题 */
.portal-title {
  padding: 12px 20px 4px;
  font-size: 11px;
  color: #475569;
  letter-spacing: 2px;
  text-transform: uppercase;
  font-weight: 600;
}
/* 菜单项：圆角、外边距、激活态与悬停态高亮 */
.sidebar :deep(.el-menu-item) {
  margin: 4px 8px;
  border-radius: 8px;
}
.sidebar :deep(.el-menu-item.is-active) {
  background: rgba(16, 185, 129, 0.12) !important;
}
.sidebar :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.04) !important;
}

/* 侧边栏底部：联盟链状态区 */
.sidebar-footer {
  padding: 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}
.chain-status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #64748b;
}
/* 链状态指示灯：脉冲动画 */
.chain-status .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #10b981;
  box-shadow: 0 0 8px #10b981;
  animation: pulse 2s infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

/* 右侧主区域容器 */
.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 顶部栏：面包屑与右侧操作区 */
.topbar {
  height: 60px;
  background: #fff;
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  flex-shrink: 0;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 24px;
}

.chain-badge {
  cursor: pointer;
}

/* 用户信息区：头像 + 名称角色 + 下拉箭头 */
.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  padding: 4px 10px;
  border-radius: 8px;
  transition: background .2s;
}
.user-info:hover {
  background: #f1f5f9;
}
.user-text {
  line-height: 1.2;
}
.user-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.user-role {
  font-size: 11px;
  color: var(--text-secondary);
}

/* 内容区：可滚动，承载路由出口 */
.content {
  flex: 1;
  overflow-y: auto;
  background: var(--bg);
}

/* 路由切换淡入淡出过渡 */
.fade-enter-active, .fade-leave-active {
  transition: opacity .2s;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}
</style>
