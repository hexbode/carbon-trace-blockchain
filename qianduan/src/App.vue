<!--
  App.vue —— 应用根组件
  用途：仅承载路由出口，页面框架（侧边栏 / 顶部栏）由 Layout.vue 负责。
  说明：不含 script 与 style，不持有全局状态；登录页、公共查询端与主布局
        均由路由在 router-view 中切换渲染。
-->
<template>
  <router-view />
</template>
