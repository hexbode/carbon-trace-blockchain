<!--
  Layout.vue —— 后台主框架布局
  用途：登录后各角色（企业端/审核端/监管端/管理员）共用的整体布局。
  路由：作为 / 下各角色后台页面的父布局，默认重定向 /enterprise/home。
  桌面端：左侧可折叠侧边栏 + 顶部面包屑 + 内容区；
  移动端：顶部精简 AppBar + 抽屉式菜单 + 底部标签栏，页面结构单独渲染。
-->
<template>
  <div class="layout" :class="{ 'layout-mobile': isMobile }">
    <!-- ============ 桌面端：左侧侧边栏 ============ -->
    <aside v-if="!isMobile" class="sidebar" :class="{ collapsed }">
      <div class="sidebar-header">
        <!-- 品牌标识：折叠后仅保留链形图标，文字随之隐藏 -->
        <div class="logo">
          <el-icon :size="22"><Connection /></el-icon>
          <span v-show="!collapsed" class="logo-text">碳链通</span>
        </div>
        <!-- 折叠开关：图标随折叠状态在 Fold / Expand 间切换 -->
        <el-icon class="collapse-btn" @click="collapsed = !collapsed">
          <Fold v-if="!collapsed" />
          <Expand v-else />
        </el-icon>
      </div>

      <!-- 菜单：开启 router 模式后 index 即路由路径，点击由 vue-router 接管跳转 -->
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

      <!-- 侧边栏底部：联盟链出块状态（区块数由定时轮询刷新） -->
      <div class="sidebar-footer" v-show="!collapsed">
        <div class="chain-status">
          <span class="dot"></span>
          <span>联盟链 · {{ blockCount }} 个区块</span>
        </div>
      </div>
    </aside>

    <!-- ============ 右侧主区域 ============ -->
    <div class="main-area">
      <!-- 桌面端顶部栏：面包屑 + 链状态 + 用户菜单 -->
      <header v-if="!isMobile" class="topbar">
        <div class="topbar-left">
          <!-- 面包屑：门户首页（可点击返回）/ 当前页面标题 -->
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: homePath }">{{ portalTitle }}</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="topbar-right">
          <!-- 区块链状态徽标：数字为当前区块高度，超出 999 以 999+ 显示 -->
          <el-tooltip content="区块链状态正常">
            <el-badge :value="blockCount" :max="999" class="chain-badge">
              <el-icon :size="20" color="#10b981"><Link /></el-icon>
            </el-badge>
          </el-tooltip>

          <!-- 用户下拉：展示姓名与角色，菜单项由 handleCommand 统一处理 -->
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

      <!-- 移动端顶部栏：菜单按钮 + 页面标题 + 链状态/账号 -->
      <header v-else class="mobile-topbar">
        <!-- 菜单按钮：打开包含全部菜单项的抽屉 -->
        <el-icon class="mobile-icon-btn" :size="22" @click="drawerVisible = true"><MenuIcon /></el-icon>
        <!-- 标题优先取当前页 meta.title，缺失时回退门户名 -->
        <span class="mobile-title">{{ currentTitle || portalTitle }}</span>
        <!-- 右侧操作：区块徽标 + 头像下拉（复用桌面端的命令处理） -->
        <div class="mobile-actions">
          <el-badge :value="blockCount" :max="999">
            <el-icon :size="18" color="#10b981"><Link /></el-icon>
          </el-badge>
          <el-dropdown @command="handleCommand">
            <el-avatar :size="30" :icon="UserFilled" />
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <!-- 内容区：路由出口 -->
      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>

      <!-- 移动端底部标签栏：常用菜单 + 更多 -->
      <nav v-if="isMobile" class="mobile-tabbar">
        <div
          v-for="item in tabItems"
          :key="item.path"
          class="tab-item"
          :class="{ active: activeMenu === item.path }"
          @click="router.push(item.path)"
        >
          <el-icon :size="20"><component :is="item.icon" /></el-icon>
          <span class="tab-text">{{ item.short }}</span>
        </div>
        <!-- “更多”：固定第 5 项，打开抽屉查看未放进底栏的其余菜单项 -->
        <div class="tab-item" :class="{ active: drawerVisible }" @click="drawerVisible = true">
          <el-icon :size="20"><MenuIcon /></el-icon>
          <span class="tab-text">更多</span>
        </div>
      </nav>
    </div>

    <!-- ============ 移动端：抽屉式完整菜单 ============ -->
    <el-drawer
      v-if="isMobile"
      v-model="drawerVisible"
      direction="ltr"
      size="76%"
      :with-header="false"
      class="mobile-drawer"
    >
      <div class="drawer-inner">
        <!-- 抽屉头部：品牌标识与当前门户名称 -->
        <div class="drawer-brand">
          <div class="drawer-logo"><el-icon :size="20"><Connection /></el-icon></div>
          <div>
            <div class="drawer-name">碳链通</div>
            <div class="drawer-sub">{{ portalTitle }}</div>
          </div>
        </div>

        <el-menu
          :default-active="activeMenu"
          background-color="#0f172a"
          text-color="#94a3b8"
          active-text-color="#10b981"
          router
          class="drawer-menu"
          @select="drawerVisible = false"
        >
          <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>{{ item.title }}</template>
          </el-menu-item>
        </el-menu>

        <!-- 抽屉底部：链状态 + 当前登录用户身份 -->
        <div class="drawer-footer">
          <div class="chain-status">
            <span class="dot"></span>
            <span>联盟链 · {{ blockCount }} 个区块</span>
          </div>
          <div class="drawer-user">{{ userStore.user?.name }} · {{ userStore.roleLabel }}</div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
// 导入 Vue 响应式 API
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
// 导入路由相关方法
import { useRouter, useRoute } from 'vue-router'
// 导入 Element Plus 消息提示组件
import { ElMessageBox, ElMessage } from 'element-plus'
// 导入用户默认头像图标与移动端菜单图标
import { UserFilled, Menu as MenuIcon } from '@element-plus/icons-vue'
// 导入全局用户状态仓库
import { useUserStore } from '@/store'
// 导入各角色菜单配置
import { menuConfig } from '@/router'
// 导入区块链统计接口
import { getBlockchainStats } from '@/api/blockchain'
// 导入全局数据刷新信号（各页面注册自己的加载函数后即可被自动刷新）
import { refreshTick } from '@/composables/useAutoRefresh'
// 导入设备检测（移动端渲染不同布局）
import { useDevice } from '@/composables/useDevice'

// 路由实例与当前路由
const router = useRouter()
const route = useRoute()
// 用户状态
const userStore = useUserStore()
// 侧边栏折叠状态（桌面端）
const collapsed = ref(false)
// 移动端抽屉菜单开关
const drawerVisible = ref(false)
// 设备类型：断点由 useDevice 统一维护（视口 ≤767px），命中后整页切换为独立的移动端结构
const { isMobile } = useDevice()
// 区块链区块数量
const blockCount = ref(0)

// ============ 数据自动刷新（定时轮询）============
// 区块高度需要保持"实时"观感，故固定 15 秒轮询一次；
// 轮询只递增全局刷新信号，具体页面数据由各自注册的加载函数响应，布局本身不重复拉取业务数据。
const REFRESH_INTERVAL = 15000
// 轮询定时器句柄（组件卸载时清理，避免路由切走后仍在后台执行）
let refreshTimer = null

// 卸载前清除定时器
onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = null
})

// 挂载：先取一次区块高度，再启动定时轮询
onMounted(async () => {
  try {
    const stats = await getBlockchainStats()
    blockCount.value = stats.blockCount || 0
  } catch (e) {
    blockCount.value = 0
  }
  refreshTimer = setInterval(() => {
    // 标签页处于后台时跳过本次刷新，减少无意义请求
    if (document.hidden) return
    refreshTick.value++
  }, REFRESH_INTERVAL)
})

// 路由切换后关闭移动端抽屉，避免遮挡新页面
watch(() => route.path, () => {
  drawerVisible.value = false
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
// 当前页面标题（取路由 meta.title，用于面包屑/标题栏）
const currentTitle = computed(() => route.meta.title || '')

// 移动端底部标签栏的中文短标题（底部空间有限，长标题会截断）
const shortTitleMap = {
  企业首页: '首页',
  碳排放数据填报: '填报',
  排放趋势统计: '趋势',
  报告提交记录: '报告',
  认证申请: '申请',
  认证证书查看: '证书',
  'CBAM 合规申报': 'CBAM',
  审核工作台: '工作台',
  待审核报告: '待审',
  今日审核: '今日',
  审核历史: '历史',
  监管总览: '总览',
  企业管理: '企业',
  审核机构管理: '机构',
  碳排放因子管理: '因子',
  认证管理: '认证',
  'CBAM 申报管理': 'CBAM',
  行业碳排放统计: '行业',
  异常数据监控: '异常',
  链上治理: '治理',
  用户管理: '用户'
}

// 底部标签栏：取当前角色菜单的前 4 项，第 5 项固定为“更多”
const tabItems = computed(() =>
  menuItems.value.slice(0, 4).map(item => ({
    ...item,
    short: shortTitleMap[item.title] || item.title.slice(0, 3)
  }))
)

// 各端首页路径
const homePath = computed(() => {
  const map = { enterprise: '/enterprise/home', auditor: '/auditor/home', regulator: '/regulator/home', admin: '/regulator/home' }
  return map[userStore.role] || '/enterprise/home'
})

// 用户下拉菜单命令处理
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
/* ============ 桌面端布局 ============ */
.layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* 左侧侧边栏 */
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

/* 侧边栏头部：品牌与折叠按钮，高度与右侧顶栏对齐 */
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

.collapse-btn {
  color: #64748b;
  cursor: pointer;
  font-size: 18px;
}
.collapse-btn:hover {
  color: #fff;
}

/* 菜单撑满剩余高度，去掉 Element 默认的右侧边框 */
.sidebar :deep(.el-menu) {
  border-right: none;
  flex: 1;
}
.portal-title {
  padding: 12px 20px 4px;
  font-size: 11px;
  color: #475569;
  letter-spacing: 2px;
  text-transform: uppercase;
  font-weight: 600;
}
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

/* 右侧主区域 */
.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
}

/* 桌面端顶部栏 */
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

/* 内容区 */
.content {
  flex: 1;
  overflow-y: auto;
  background: var(--bg);
  -webkit-overflow-scrolling: touch;
}

.fade-enter-active, .fade-leave-active {
  transition: opacity .2s;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}

/* ============ 移动端布局（独立设计：碳账本 / 精密仪表） ============ */
/* 无媒体查询：由 isMobile（视口 ≤767px）直接切换类名，桌面与移动端结构互斥渲染 */
.layout-mobile {
  flex-direction: column;
  height: 100vh;
  background: var(--m-paper, #f3f6f2);
}
@supports (height: 100dvh) {
  .layout,
  .layout-mobile {
    height: 100dvh;
  }
}

/* 顶部栏：半透明纸面 + 细分割线 */
.mobile-topbar {
  height: 54px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
  padding-top: env(safe-area-inset-top);
  box-sizing: content-box;
  background: rgba(243, 246, 242, 0.9);
  -webkit-backdrop-filter: blur(14px) saturate(1.2);
  backdrop-filter: blur(14px) saturate(1.2);
  border-bottom: 1px solid rgba(7, 26, 22, 0.07);
  position: relative;
  z-index: 20;
}
.mobile-icon-btn {
  color: var(--m-ink, #071a16);
  padding: 4px;
  border-radius: 9px;
}
.mobile-icon-btn:active {
  background: rgba(7, 26, 22, 0.06);
}
/* 页面标题：占满中间空间，超长时单行省略，避免挤压右侧操作区 */
.mobile-title {
  flex: 1;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.01em;
  color: var(--m-ink, #071a16);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* 右侧操作区：区块徽标与头像保持 12px 间距 */
.mobile-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.mobile-actions .el-badge {
  display: flex;
}
/* 区块数徽标：改用青柠信号色，与主题一致 */
.mobile-actions :deep(.el-badge__content) {
  background: var(--m-signal, #c8f169);
  border: none;
  color: #071a16;
  font-family: var(--m-mono, ui-monospace, monospace);
  font-weight: 700;
}
.mobile-actions .el-avatar {
  box-shadow: 0 0 0 2px #fff, 0 0 0 3px rgba(7, 26, 22, 0.08);
}

/* 内容区：纸张底纹 + 青色信号光晕，底部预留悬浮导航空间（仅移动端） */
.layout-mobile .content {
  flex: 1;
  overflow-y: auto;
  padding-bottom: 94px;
  background:
    radial-gradient(120% 50% at 100% 0, rgba(15, 157, 118, 0.1), transparent 58%),
    radial-gradient(90% 45% at 0 100%, rgba(200, 241, 105, 0.12), transparent 60%),
    var(--m-paper, #f3f6f2);
  -webkit-overflow-scrolling: touch;
}

/* 底部悬浮胶囊导航 */
.mobile-tabbar {
  position: fixed;
  left: 12px;
  right: 12px;
  bottom: calc(10px + env(safe-area-inset-bottom));
  height: 62px;
  display: flex;
  align-items: stretch;
  padding: 0 4px;
  border-radius: 20px;
  background: rgba(7, 26, 22, 0.94);
  -webkit-backdrop-filter: blur(16px);
  backdrop-filter: blur(16px);
  border: 1px solid rgba(200, 241, 105, 0.14);
  box-shadow: 0 14px 34px rgba(7, 26, 22, 0.28);
  z-index: 40;
}
.tab-item {
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  padding: 6px 2px;
  border-radius: 14px;
  color: #83998f;
  font-size: 10px;
  cursor: pointer;
  user-select: none;
  transition: color 0.2s ease;
}
.tab-item .el-icon {
  transition: transform 0.2s ease;
}
.tab-item.active {
  color: var(--m-signal, #c8f169);
}
.tab-item.active .el-icon {
  transform: translateY(-1px) scale(1.06);
}
.tab-item.active::after {
  content: '';
  position: absolute;
  bottom: 6px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--m-signal, #c8f169);
  box-shadow: 0 0 8px var(--m-signal, #c8f169);
}
.tab-item:active {
  background: rgba(255, 255, 255, 0.04);
}
.tab-text {
  line-height: 1.2;
  white-space: nowrap;
}

/* 抽屉菜单：墨绿面板 + 序号索引 */
.drawer-inner {
  display: flex;
  flex-direction: column;
  height: 100%;
  background:
    radial-gradient(120% 45% at 100% 0, rgba(200, 241, 105, 0.1), transparent 55%),
    #071a16;
}
/* 抽屉品牌区：顶部留白大于底部，形成"封面"观感 */
.drawer-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 24px 18px 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}
.drawer-logo {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: linear-gradient(140deg, #c8f169, #0f9d76);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #071a16;
}
.drawer-name {
  font-family: var(--m-serif, "Songti SC", serif);
  font-size: 21px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: #f2f7f3;
}
.drawer-sub {
  margin-top: 2px;
  font-size: 10px;
  letter-spacing: 0.16em;
  color: #7f978d;
}
.drawer-menu {
  flex: 1;
  border-right: none;
  overflow-y: auto;
  padding: 8px 0;
  counter-reset: nav;
}
.drawer-inner :deep(.el-menu-item) {
  height: 48px;
  margin: 2px 10px;
  border-radius: 12px;
  font-size: 14px;
  color: #a9bdb4;
}
/* 菜单序号：01 / 02 / ... */
.drawer-inner :deep(.el-menu-item)::before {
  counter-increment: nav;
  content: counter(nav, decimal-leading-zero);
  width: 18px;
  margin-right: 12px;
  font-family: var(--m-mono, ui-monospace, monospace);
  font-size: 10px;
  color: #4f6b61;
}
.drawer-inner :deep(.el-menu-item.is-active) {
  background: rgba(200, 241, 105, 0.09) !important;
  color: var(--m-signal, #c8f169) !important;
  box-shadow: inset 0 0 0 1px rgba(200, 241, 105, 0.14);
}
.drawer-inner :deep(.el-menu-item.is-active)::before {
  color: var(--m-signal, #c8f169);
}
/* 抽屉底部：底部内边距额外叠加安全区，适配全面屏手势条 */
.drawer-footer {
  padding: 16px 18px calc(18px + env(safe-area-inset-bottom));
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.drawer-user {
  font-size: 11px;
  letter-spacing: 0.04em;
  color: #7f978d;
}
</style>

<style>
/* 抽屉容器本身由 Element Plus 渲染在 body 下，需要非 scoped 样式 */
.mobile-drawer .el-drawer__body {
  padding: 0;
  background: #071a16;
}
</style>
