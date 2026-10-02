/**
 * @file vite.config.js
 * @description Vite 构建工具配置文件
 * @module 构建配置
 *
 * 文件用途：
 *   定义 Vite 开发服务器与生产构建的核心配置，包括 Vue 插件启用、
 *   路径别名映射以及本地开发服务器行为。本文件为碳排放管理系统的
 *   前端工程化基础配置入口。
 */

// 引入 Vite 配置定义辅助函数，提供类型提示与配置校验
import { defineConfig } from 'vite'
// 引入官方 Vue 单文件组件编译插件，使 Vite 能够解析 .vue 文件
import vue from '@vitejs/plugin-vue'
// 引入 Node.js 内置 path 模块，用于处理与拼接文件路径
import path from 'path'

// 导出 Vite 配置对象，defineConfig 包裹后可获 IDE 类型提示
export default defineConfig({
  // 插件列表：启用 Vue SFC 编译支持
  plugins: [vue()],
  // 路径解析相关配置
  resolve: {
    // 别名配置：将 '@' 指向 src 目录，便于在项目中以 '@/xxx' 方式引用源码
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  // 本地开发服务器配置
  server: {
    // 监听端口，默认 5173
    port: 5173,
    // 监听所有网络地址（0.0.0.0），允许局域网内其他设备通过 IP 访问进行联调
    host: '0.0.0.0',
    // 启动开发服务器后自动在浏览器中打开页面
    open: true,
    // 接口代理：将 /api 前缀请求转发到后端服务，避免跨域
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      // 上传文件（头像等）的静态资源同样转发到后端
      '/uploads': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
