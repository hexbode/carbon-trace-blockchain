<!--
  PublicLayout.vue —— 公共查询端布局
  用途：面向社会公众的公开查询页面框架，无需登录即可访问。
  包含顶部品牌导航栏、中间路由出口内容区、底部页脚信息说明。
-->
<template>
  <div class="public-layout">
    <!-- 顶部公共头部：品牌 logo + 导航 + 登录入口 -->
    <header class="public-header">
      <div class="public-container header-inner">
        <!-- 品牌区：点击返回认证编号查询首页 -->
        <div class="brand" @click="$router.push('/public/query')">
          <div class="logo"><el-icon :size="22"><Connection /></el-icon></div>
          <div>
            <div class="brand-name">碳链通</div>
            <div class="brand-sub">公共查询端</div>
          </div>
        </div>
        <!-- 公共导航：认证查询/二维码验证/企业信息/有效性验证 -->
        <nav class="public-nav">
          <router-link
            v-for="item in navItems"
            :key="item.path"
            :to="item.path"
            class="nav-item"
            active-class="active"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.title }}</span>
          </router-link>
        </nav>
        <!-- 头部右侧：链上数据标识 + 登录后台按钮 -->
        <div class="header-right">
          <el-tag type="success" effect="plain" round>
            <span class="dot"></span> 链上数据 · 公开可验
          </el-tag>
          <el-button text @click="$router.push('/login')">登录后台</el-button>
        </div>
      </div>
    </header>

    <!-- 主体内容区：路由出口 -->
    <main class="public-main">
      <div class="public-container">
        <router-view />
      </div>
    </main>

    <!-- 页脚：系统说明与上链存证提示 -->
    <footer class="public-footer">
      <div class="public-container">
        <p>碳链通 · 基于区块链的企业碳排放数据认证与追溯系统 | 公共查询服务</p>
        <p class="muted">所有认证数据均已上链存证，电子防伪码与交易哈希可在区块链浏览器中核验</p>
      </div>
    </footer>
  </div>
</template>

<script setup>
// 导入路由实例（用于品牌区跳转等场景）
import { useRouter } from 'vue-router'

const router = useRouter()
// 公共导航菜单项：路径、标题、对应图标组件名
const navItems = [
  { path: '/public/query', title: '电子防伪码查询', icon: 'Search' },
  { path: '/public/qr', title: '二维码验证', icon: 'FullScreen' },
  { path: '/public/enterprise', title: '企业碳排放信息', icon: 'OfficeBuilding' },
  { path: '/public/validity', title: '认证有效性验证', icon: 'CircleCheck' }
]
</script>

<style scoped>
/* 公共布局根容器：纵向排列，占满视口高度 */
.public-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f1f5f9;
}
/* 内容居中容器：限制最大宽度并左右留白 */
.public-container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 24px;
}
/* 顶部公共头部：吸顶定位、深色背景、阴影 */
.public-header {
  background: #0f172a;
  color: #fff;
  position: sticky;
  top: 0;
  z-index: 10;
  box-shadow: 0 2px 12px rgba(0,0,0,.1);
}
.header-inner {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
/* 品牌区：logo + 名称，可点击 */
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}
.brand .logo {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: linear-gradient(135deg, #10b981, #0ea5e9);
  display: flex;
  align-items: center;
  justify-content: center;
}
.brand-name {
  font-size: 18px;
  font-weight: 700;
}
.brand-sub {
  font-size: 11px;
  color: #94a3b8;
}
/* 公共导航条 */
.public-nav {
  display: flex;
  gap: 4px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 8px;
  color: #94a3b8;
  text-decoration: none;
  font-size: 14px;
  transition: all .2s;
}
.nav-item:hover {
  color: #fff;
  background: rgba(255,255,255,.06);
}
.nav-item.active {
  color: #10b981;
  background: rgba(16, 185, 129, .12);
}
/* 头部右侧操作区 */
.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}
.header-right .el-tag {
  display: flex;
  align-items: center;
  gap: 6px;
}
.header-right .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #10b981;
}
/* 主体内容区：弹性填充 */
.public-main {
  flex: 1;
  padding: 32px 0;
}
/* 页脚：深色背景、居中文字 */
.public-footer {
  background: #0f172a;
  color: #64748b;
  text-align: center;
  padding: 24px 0;
  font-size: 13px;
}
.public-footer p { margin: 4px 0; }
.muted { color: #475569; font-size: 12px; }
</style>
