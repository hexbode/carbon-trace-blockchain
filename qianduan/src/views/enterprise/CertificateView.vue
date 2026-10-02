<!--
  CertificateView.vue - 认证证书查看页（企业端）
  用途：展示已通过区块链认证的碳排放报告电子证书，支持证书卡片浏览、详情查看与链上存证信息验证
-->
<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <h2>认证证书查看</h2>
      <p>已通过区块链认证的碳排放报告，可查看电子证书并验证链上存证</p>
    </div>

    <!-- 汇总统计卡片区：证书总数、累计认证排放量、最新认证日期 -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="8"><div class="stat-card"><div class="label">证书总数</div><div class="value">{{ certs.length }}<span class="unit">张</span></div></div></el-col>
      <el-col :span="8"><div class="stat-card"><div class="label">累计认证排放量</div><div class="value">{{ totalAmount.toFixed(0) }}<span class="unit">tCO₂</span></div></div></el-col>
      <el-col :span="8"><div class="stat-card"><div class="label">最新认证</div><div class="value">{{ latest?.certifiedAt?.slice(0, 10) || '-' }}</div></div></el-col>
    </el-row>

    <!-- 证书卡片网格：点击打开证书详情弹窗 -->
    <div class="cert-grid" v-loading="loading">
      <div class="cert-card" v-for="cert in certs" :key="cert.id" @click="viewCert(cert)">
        <div class="cert-header">
          <el-icon :size="32" color="#10b981"><Stamp /></el-icon>
          <div class="cert-no">{{ cert.id }}</div>
        </div>
        <div class="cert-body">
          <div class="cert-period">{{ cert.period }} 期</div>
          <div class="cert-amount">{{ cert.amount }} <span>tCO₂</span></div>
          <div class="cert-fp">电子防伪码：{{ cert.fingerprint }}</div>
        </div>
        <div class="cert-footer">
          <el-tag type="success" size="small"><el-icon><Lock /></el-icon> 已上链</el-tag>
          <span class="cert-date">{{ cert.certifiedAt.slice(0, 10) }}</span>
        </div>
      </div>
      <el-empty v-if="!certs.length" description="暂无认证证书" style="grid-column: 1/-1" />
    </div>

    <!-- 证书详情对话框：展示电子证书正文与区块链存证信息 -->
    <el-dialog v-model="showCert" title="碳排放认证证书" width="600px">
      <div class="certificate" v-if="current">
        <div class="cert-top">
          <div class="cert-seal">
            <el-icon :size="48" color="#10b981"><Stamp /></el-icon>
          </div>
          <div class="cert-title">企业碳排放数据认证证书</div>
          <div class="cert-id">证书编号：{{ current.id }}</div>
        </div>
        <div class="cert-content">
          <p>兹证明 <strong>{{ current.enterpriseName }}</strong> 提交的 <strong>{{ current.period }}</strong> 期碳排放数据，经审核机构核验，数据真实、核算方法合规，排放量为：</p>
          <div class="cert-big-amount">{{ current.amount }} tCO₂</div>
          <el-descriptions :column="1" border size="small" style="margin-top: 16px">
            <el-descriptions-item label="电子防伪码"><span class="hash-text">{{ current.fingerprint }}</span></el-descriptions-item>
            <el-descriptions-item label="区块链交易哈希"><span class="hash-text">{{ current.txHash }}</span></el-descriptions-item>
            <el-descriptions-item label="所在区块">第 {{ current.blockIndex }} 号区块</el-descriptions-item>
            <el-descriptions-item label="审核人">{{ current.auditor }}</el-descriptions-item>
            <el-descriptions-item label="审核意见">{{ current.auditOpinion }}</el-descriptions-item>
            <el-descriptions-item label="认证时间">{{ current.certifiedAt }}</el-descriptions-item>
          </el-descriptions>
        </div>
        <div class="cert-bottom">
          <div class="chain-proof">
            <el-icon color="#10b981"><CircleCheck /></el-icon>
            <span>本证书数据已上链存证，哈希值可在区块链浏览器验证，不可篡改</span>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="showCert = false">关闭</el-button>
        <el-button type="primary" @click="download">下载证书</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// 引入 Vue 响应式 API：ref 基本值，computed 计算属性
import { ref, computed, onMounted } from 'vue'
// 引入 Element Plus 消息提示组件
import { ElMessage } from 'element-plus'
// 引入用户 store，获取当前登录企业
import { useUserStore } from '@/store'
// 引入认证记录接口
import { getCertifications } from '@/api/certification'
// 引入 jsPDF：把绘制好的证书画布打包成 PDF 文件
import { jsPDF } from 'jspdf'

// 用户 store 实例
const userStore = useUserStore()
// 当前企业的有效认证证书列表
const certs = ref([])
// 列表加载态
const loading = ref(false)
// 累计认证排放量
const totalAmount = computed(() => certs.value.reduce((s, c) => s + Number(c.amount || 0), 0))
// 最新一张证书（取列表首项）
const latest = computed(() => certs.value[0] || null)

// 证书详情弹窗显隐控制与当前选中证书
const showCert = ref(false)
const current = ref(null)
// 打开证书详情弹窗：记录当前证书并显示
function viewCert(cert) { current.value = cert; showCert.value = true }
// 文本超宽时截断并加省略号（用于 canvas 绘制时防止溢出）
function clipText(ctx, text, maxWidth) {
  if (ctx.measureText(text).width <= maxWidth) return text
  let s = text
  while (s.length > 1 && ctx.measureText(s + '…').width > maxWidth) s = s.slice(0, -1)
  return s + '…'
}

// 下载证书：用 canvas 现场绘制证书，再用 jsPDF 打包成 PDF 文件下载
function download() {
  const c = current.value
  if (!c) return

  // 画布尺寸与 3 倍缩放（PDF 打印更清晰）
  const W = 1000
  const H = 700
  const scale = 3
  const canvas = document.createElement('canvas')
  canvas.width = W * scale
  canvas.height = H * scale
  const ctx = canvas.getContext('2d')
  ctx.scale(scale, scale)

  // 底色与双层边框
  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, W, H)
  ctx.strokeStyle = '#10b981'
  ctx.lineWidth = 3
  ctx.strokeRect(24, 24, W - 48, H - 48)
  ctx.strokeStyle = '#a7f3d0'
  ctx.lineWidth = 1
  ctx.strokeRect(36, 36, W - 72, H - 72)

  // 标题区
  ctx.textAlign = 'center'
  ctx.fillStyle = '#065f46'
  ctx.font = 'bold 38px "Microsoft YaHei", "PingFang SC", sans-serif'
  ctx.fillText('企业碳排放数据认证证书', W / 2, 128)
  ctx.fillStyle = '#94a3b8'
  ctx.font = '14px "Microsoft YaHei", sans-serif'
  ctx.fillText('CARBON EMISSION DATA CERTIFICATION', W / 2, 158)
  ctx.fillStyle = '#64748b'
  ctx.font = '15px "Microsoft YaHei", sans-serif'
  ctx.fillText(`证书编号：${c.id}`, W / 2, 192)

  // 正文说明
  ctx.textAlign = 'left'
  ctx.fillStyle = '#1f2937'
  ctx.font = '17px "Microsoft YaHei", sans-serif'
  ctx.fillText(clipText(ctx, `兹证明 ${c.enterpriseName} 提交的 ${c.period} 期碳排放数据，`, W - 160), 80, 258)
  ctx.fillText('经审核机构核验，数据真实、核算方法合规，认证排放量为：', 80, 290)

  // 认证排放量
  ctx.textAlign = 'center'
  ctx.fillStyle = '#059669'
  ctx.font = 'bold 44px "Microsoft YaHei", sans-serif'
  ctx.fillText(`${c.amount} tCO₂`, W / 2, 356)

  // 明细行（左半区，避免与右下角印章重叠）
  const rows = [
    ['电子防伪码', c.fingerprint],
    ['区块链交易哈希', c.txHash],
    ['所在区块', c.blockIndex === null || c.blockIndex === undefined ? '-' : `第 ${c.blockIndex} 号区块`],
    ['审核人', c.auditor],
    ['认证时间', c.certifiedAt]
  ]
  let y = 412
  rows.forEach(([label, value]) => {
    ctx.textAlign = 'left'
    ctx.fillStyle = '#94a3b8'
    ctx.font = '14px "Microsoft YaHei", sans-serif'
    ctx.fillText(label, 80, y)
    ctx.fillStyle = '#334155'
    ctx.font = '14px Consolas, "Courier New", monospace'
    ctx.fillText(clipText(ctx, String(value === null || value === undefined || value === '' ? '-' : value), 400), 220, y)
    y += 34
  })

  // 认证印章（右下角）
  const sx = W - 172
  const sy = 505
  ctx.beginPath()
  ctx.arc(sx, sy, 64, 0, Math.PI * 2)
  ctx.strokeStyle = 'rgba(220, 38, 38, 0.7)'
  ctx.lineWidth = 3
  ctx.stroke()
  ctx.beginPath()
  ctx.arc(sx, sy, 52, 0, Math.PI * 2)
  ctx.lineWidth = 1
  ctx.stroke()
  ctx.textAlign = 'center'
  ctx.fillStyle = 'rgba(220, 38, 38, 0.85)'
  ctx.font = 'bold 16px "Microsoft YaHei", sans-serif'
  ctx.fillText('碳排放认证', sx, sy - 6)
  ctx.fillText('专用章', sx, sy + 22)

  // 底部链上存证说明
  ctx.textAlign = 'center'
  ctx.fillStyle = '#10b981'
  ctx.font = '14px "Microsoft YaHei", sans-serif'
  ctx.fillText('本证书数据已上链存证，哈希值可在区块链浏览器验证，不可篡改', W / 2, H - 62)

  // 打包成 PDF：横向 A4，图片按比例居中，四周留 8mm 边距
  const pdf = new jsPDF({ orientation: 'landscape', unit: 'mm', format: 'a4' })
  const pageW = pdf.internal.pageSize.getWidth()
  const pageH = pdf.internal.pageSize.getHeight()
  const margin = 8
  const ratio = Math.min((pageW - margin * 2) / W, (pageH - margin * 2) / H)
  const imgW = W * ratio
  const imgH = H * ratio
  pdf.addImage(canvas.toDataURL('image/jpeg', 0.95), 'JPEG', (pageW - imgW) / 2, (pageH - imgH) / 2, imgW, imgH)
  pdf.save(`碳排放认证证书-${c.id}.pdf`)
  ElMessage.success('证书 PDF 已下载')
}

// 拉取当前企业有效认证证书
async function load() {
  loading.value = true
  try {
    certs.value = await getCertifications({ enterpriseId: userStore.user?.enterpriseId, status: 'valid' })
  } finally {
    loading.value = false
  }
}
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>

<style scoped>
/* 证书卡片网格布局：自适应列数 */
.cert-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 16px;
}
/* 单个证书卡片：渐变背景 + 装饰圆形 */
.cert-card {
  background: linear-gradient(135deg, #fff 0%, #f0fdf4 100%);
  border: 1px solid #a7f3d0;
  border-radius: 14px;
  padding: 20px;
  cursor: pointer;
  transition: all .25s;
  position: relative;
  overflow: hidden;
}
/* 卡片右上角装饰圆形 */
.cert-card::before {
  content: '';
  position: absolute;
  top: -30px;
  right: -30px;
  width: 100px;
  height: 100px;
  border-radius: 50%;
  background: rgba(16,185,129,.1);
}
/* 卡片悬停上浮效果 */
.cert-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 30px rgba(16,185,129,.15);
}
/* 卡片头部：印章图标与证书编号 */
.cert-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.cert-no { font-size: 13px; color: #64748b; font-family: monospace; }
/* 卡片正文：报告期、排放量、电子防伪码 */
.cert-period { font-size: 13px; color: #64748b; margin-bottom: 6px; }
.cert-amount { font-size: 28px; font-weight: 700; color: #0f172a; }
.cert-amount span { font-size: 14px; color: #94a3b8; font-weight: 500; }
.cert-fp { font-size: 11px; color: #94a3b8; margin-top: 8px; font-family: monospace; }
/* 卡片底部：上链标识与认证日期 */
.cert-footer { display: flex; align-items: center; justify-content: space-between; margin-top: 16px; padding-top: 12px; border-top: 1px dashed #a7f3d0; }
.cert-date { font-size: 12px; color: #94a3b8; }

/* 证书正文样式（弹窗内） */
.certificate { padding: 8px; }
/* 证书顶部：印章 + 标题 + 编号 */
.cert-top { text-align: center; padding: 20px 0; border-bottom: 2px solid #10b981; }
.cert-title { font-size: 22px; font-weight: 700; color: #0f172a; margin: 10px 0 4px; }
.cert-id { font-size: 13px; color: #94a3b8; }
/* 证书正文内容：证明文字 + 大号排放量 + 存证信息 */
.cert-content { padding: 20px 0; text-align: center; }
.cert-content p { color: #475569; line-height: 1.8; margin: 0 0 12px; }
.cert-big-amount { font-size: 36px; font-weight: 800; color: #10b981; margin: 12px 0; }
/* 证书底部：链上存证说明 */
.cert-bottom { text-align: center; padding-top: 16px; border-top: 1px solid #e2e8f0; }
.chain-proof { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #059669; }
</style>
