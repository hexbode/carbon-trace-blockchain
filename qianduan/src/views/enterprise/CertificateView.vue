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

    <!-- 证书卡片列表：点击打开证书详情弹窗；手机端用卡片列表，桌面端用卡片网格 -->
    <div v-if="isMobile" v-loading="loading" class="m-list">
      <el-empty v-if="!certs.length" description="暂无数据" :image-size="70" />
      <div v-for="cert in certs" :key="cert.id" class="m-card">
        <div class="m-card-head">
          <span class="m-card-title">{{ cert.id }}</span>
          <el-tag type="success" size="small"><el-icon><Lock /></el-icon> 已上链</el-tag>
        </div>
        <div class="m-row"><span class="m-key">报告期</span><span class="m-val">{{ cert.period }} 期</span></div>
        <div class="m-row"><span class="m-key">排放量</span><span class="m-val text-primary" style="font-weight: 600">{{ cert.amount }} tCO₂</span></div>
        <div class="m-row"><span class="m-key">电子防伪码</span><span class="m-val hash-text">{{ cert.fingerprint }}</span></div>
        <div class="m-row"><span class="m-key">认证时间</span><span class="m-val">{{ cert.certifiedAt.slice(0, 10) }}</span></div>
        <div class="m-card-actions">
          <el-button text type="primary" size="small" @click="viewCert(cert)">查看证书</el-button>
        </div>
      </div>
    </div>

    <div v-else class="cert-grid" v-loading="loading">
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
      <div class="certificate" :class="{ 'is-mobile': isMobile }" v-if="current">
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
            <el-descriptions-item label="链上合规判定">
              <el-tag v-if="compliance" :type="compliance.compliant ? 'success' : 'danger'" size="small">
                {{ compliance.compliant ? '合规' : '命中异常规则' }}
              </el-tag>
              <span v-else class="compliance-none">—</span>
            </el-descriptions-item>
            <el-descriptions-item label="认证时间">{{ current.certifiedAt }}</el-descriptions-item>
          </el-descriptions>
        </div>
        <div class="cert-bottom">
          <!-- 证书核验二维码：手机扫码进入公共查询端核验本证书 -->
          <div class="cert-qr">
            <img v-if="qrDataUrl" :src="qrDataUrl" class="cert-qr-img" alt="证书核验二维码" />
            <div class="cert-qr-text">
              <div class="cert-qr-title">扫码核验</div>
              <div class="cert-qr-sub">使用手机相机或扫码工具扫描，进入公共查询端核验本证书与链上存证</div>
            </div>
          </div>
          <div class="chain-proof">
            <el-icon color="#10b981"><CircleCheck /></el-icon>
            <span>本证书数据已上链存证，哈希值可在区块链浏览器验证，不可篡改</span>
          </div>
          <!-- 认证专用章：与 PDF 中的印章保持同一设计语言 -->
          <div class="cert-seal-wrap">
            <svg class="cert-seal-svg" viewBox="0 0 170 170" role="img" aria-label="碳排放认证专用章">
              <defs>
                <path id="certSealArc" d="M 33 85 A 52 52 0 0 1 137 85" />
                <!-- 轻微噪点位移，让圆环边缘更像真实盖印 -->
                <filter id="certSealRough" x="-12%" y="-12%" width="124%" height="124%">
                  <feTurbulence type="fractalNoise" baseFrequency="0.85" numOctaves="1" result="noise" />
                  <feDisplacementMap in="SourceGraphic" in2="noise" scale="0.7"
                                     xChannelSelector="R" yChannelSelector="G" />
                </filter>
              </defs>
              <g fill="none" stroke="#b23a33" filter="url(#certSealRough)">
                <circle cx="85" cy="85" r="72" stroke-width="5" />
                <circle cx="85" cy="85" r="62" stroke-width="1.5" />
              </g>
              <text fill="#b23a33" font-size="13.5" font-weight="700"
                    font-family="'Microsoft YaHei','PingFang SC',sans-serif" letter-spacing="1">
                <textPath href="#certSealArc" startOffset="50%" text-anchor="middle">碳链通碳排放认证中心</textPath>
              </text>
              <polygon fill="#b23a33"
                       points="85,57 88.8,68.4 100.8,68.4 91,75.4 94.8,87 85,80 75.2,87 79,75.4 69.2,68.4 81.2,68.4" />
              <text x="85" y="104" text-anchor="middle" fill="#b23a33" font-size="13" font-weight="700"
                    font-family="'SimSun','Songti SC',serif" letter-spacing="0.5">认证专用章</text>
              <text x="85" y="121" text-anchor="middle" fill="#b23a33" font-size="8"
                    font-family="Consolas,monospace">{{ (current.certifiedAt || '').slice(0, 10) }}</text>
            </svg>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="showCert = false">关闭</el-button>
        <el-button type="primary" :loading="downloading" @click="download">下载证书</el-button>
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
// 引入链上合规判定接口
import { getCertificateCompliance } from '@/api/compliance'
// 引入 jsPDF：把绘制好的证书画布打包成 PDF 文件
import { jsPDF } from 'jspdf'
// 引入二维码生成库：为证书卡片与 PDF 生成可扫码的核验二维码
import QRCode from 'qrcode'
// 引入设备检测：手机端渲染卡片列表并自适应证书详情
import { useDevice } from '@/composables/useDevice'

// 用户 store 实例
const userStore = useUserStore()
// 设备类型（手机端切换为卡片列表）
const { isMobile } = useDevice()
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
// 当前证书的核验二维码（data URL）
const qrDataUrl = ref('')
// PDF 生成中的按钮状态
const downloading = ref(false)

// 核验页面基地址：HTTP 访问时统一指向 HTTPS(8443)，扫码后进入安全上下文
function verificationBase() {
  if (window.location.protocol === 'https:') return window.location.origin
  return `https://${window.location.hostname}:8443`
}

// 二维码内容：公共查询端核验链接，手机扫码后自动带出防伪码并查询
function buildQrPayload(fingerprint) {
  return `${verificationBase()}/#/public/query?fp=${encodeURIComponent(fingerprint)}`
}

// 生成证书核验二维码
async function prepareQr(fingerprint) {
  if (!fingerprint) {
    qrDataUrl.value = ''
    return
  }
  try {
    qrDataUrl.value = await QRCode.toDataURL(buildQrPayload(fingerprint), {
      width: 360,
      margin: 1,
      errorCorrectionLevel: 'M',
      color: { dark: '#071a16', light: '#ffffff' }
    })
  } catch (e) {
    qrDataUrl.value = ''
  }
}

// 加载图片（用于把二维码绘制到 PDF 画布）
function loadImage(src) {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = reject
    img.src = src
  })
}
// 当前证书的链上合规判定快照
const compliance = ref(null)
// 打开证书详情弹窗：记录当前证书并读取其链上合规判定
async function viewCert(cert) {
  current.value = cert
  showCert.value = true
  qrDataUrl.value = ''
  // 生成证书核验二维码（异步，不阻塞弹窗展示）
  prepareQr(cert.fingerprint)
  compliance.value = null
  try {
    compliance.value = await getCertificateCompliance(cert.id)
  } catch (e) {
    compliance.value = null
  }
}
// 文本超宽时截断并加省略号（用于 canvas 绘制时防止溢出）
function clipText(ctx, text, maxWidth) {
  if (ctx.measureText(text).width <= maxWidth) return text
  let s = text
  while (s.length > 1 && ctx.measureText(s + '…').width > maxWidth) s = s.slice(0, -1)
  return s + '…'
}

// 下载证书：先生成核验二维码，再绘制证书画布并打包成 PDF 下载
async function download() {
  const c = current.value
  if (!c || downloading.value) return
  downloading.value = true
  try {
    // 二维码图片：生成后加载为 Image，供画布直接绘制
    let qrImage = null
    if (c.fingerprint) {
      try {
        const qrUrl = await QRCode.toDataURL(buildQrPayload(c.fingerprint), {
          width: 360,
          margin: 1,
          errorCorrectionLevel: 'M',
          color: { dark: '#071a16', light: '#ffffff' }
        })
        qrImage = await loadImage(qrUrl)
      } catch (e) {
        qrImage = null
      }
    }

    // 绘制证书画布（3 倍缩放，PDF 打印更清晰）
    const { canvas, W, H } = drawCertificate(c, qrImage)

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
  } finally {
    downloading.value = false
  }
}

// 绘制证书画布：底色、边框、标题、明细、二维码、印章与底部说明
function drawCertificate(c, qrImage) {
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

  // 核验二维码：放在右侧空白区（印章上方），与证书正文形成左右平衡
  if (qrImage) {
    const qrSize = 120
    const qx = W - 200
    const qy = 226
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(qx - 6, qy - 6, qrSize + 12, qrSize + 12)
    ctx.drawImage(qrImage, qx, qy, qrSize, qrSize)
    ctx.strokeStyle = '#e2e8f0'
    ctx.lineWidth = 1
    ctx.strokeRect(qx - 6, qy - 6, qrSize + 12, qrSize + 12)
    ctx.textAlign = 'center'
    ctx.fillStyle = '#64748b'
    ctx.font = '13px "Microsoft YaHei", sans-serif'
    ctx.fillText('扫码核验证书', qx + qrSize / 2, qy + qrSize + 26)
  }

  // 认证专用章（右下角）：双环 + 弧形文字 + 五角星 + 日期，略倾斜模拟盖印效果
  drawSeal(ctx, W - 170, H - 170, c.certifiedAt)

  // 底部链上存证说明
  ctx.textAlign = 'center'
  ctx.fillStyle = '#10b981'
  ctx.font = '14px "Microsoft YaHei", sans-serif'
  ctx.fillText('本证书数据已上链存证，哈希值可在区块链浏览器验证，不可篡改', W / 2, H - 62)

  return { canvas, W, H }
}

// 沿圆弧逐字绘制文字：形成印章的环形文字效果
function drawArcText(ctx, text, cx, cy, radius, startAngle, endAngle, font) {
  const chars = [...text]
  ctx.save()
  ctx.font = font
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  const step = chars.length > 1 ? (endAngle - startAngle) / (chars.length - 1) : 0
  chars.forEach((ch, i) => {
    const angle = startAngle + step * i
    ctx.save()
    ctx.translate(cx + Math.cos(angle) * radius, cy + Math.sin(angle) * radius)
    ctx.rotate(angle + Math.PI / 2)
    ctx.fillText(ch, 0, 0)
    ctx.restore()
  })
  ctx.restore()
}

// 绘制五角星（印章中央标识）
function drawStar(ctx, cx, cy, radius) {
  ctx.beginPath()
  for (let i = 0; i < 10; i++) {
    const r = i % 2 === 0 ? radius : radius * 0.42
    const angle = -Math.PI / 2 + (Math.PI / 5) * i
    const x = cx + Math.cos(angle) * r
    const y = cy + Math.sin(angle) * r
    if (i === 0) ctx.moveTo(x, y)
    else ctx.lineTo(x, y)
  }
  ctx.closePath()
  ctx.fill()
}

// 绘制碳排放认证专用章：双环 + 弧形文字 + 五角星 + 日期，略倾斜模拟盖印
function drawSeal(ctx, cx, cy, certifiedAt) {
  const color = 'rgba(178, 58, 51, 0.92)'
  ctx.save()
  ctx.translate(cx, cy)
  ctx.rotate((-8 * Math.PI) / 180)
  ctx.strokeStyle = color
  ctx.fillStyle = color
  ctx.lineCap = 'round'

  // 外圈轻微错位叠印，模拟盖章时墨迹不均（偏移与透明度都要克制，避免看起来像画错的两条线）
  ctx.globalAlpha = 0.18
  ctx.lineWidth = 4
  ctx.beginPath()
  ctx.arc(0.5, -0.4, 72, 0, Math.PI * 2)
  ctx.stroke()

  ctx.globalAlpha = 1
  ctx.lineWidth = 4
  ctx.beginPath()
  ctx.arc(0, 0, 72, 0, Math.PI * 2)
  ctx.stroke()
  ctx.lineWidth = 1.2
  ctx.beginPath()
  ctx.arc(0, 0, 62, 0, Math.PI * 2)
  ctx.stroke()

  // 顶部弧形单位名称
  drawArcText(ctx, '碳链通碳排放认证中心', 0, 0, 51, -Math.PI * 0.9, -Math.PI * 0.1, 'bold 12px "Microsoft YaHei", sans-serif')

  // 中央五角星与文字
  drawStar(ctx, 0, -16, 14)
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.font = 'bold 13px "SimSun", "Songti SC", serif'
  ctx.fillText('认证专用章', 0, 12)

  // 认证日期
  const date = String(certifiedAt || '').slice(0, 10)
  if (date) {
    ctx.font = '9px Consolas, "Courier New", monospace'
    ctx.fillText(date, 0, 34)
  }
  ctx.restore()
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
/* 证书核验二维码区：二维码 + 引导文案 */
.cert-qr {
  display: flex;
  align-items: center;
  gap: 12px;
  text-align: left;
  padding: 10px 12px;
  margin-bottom: 12px;
  border: 1px dashed #a7f3d0;
  border-radius: 10px;
  background: #f0fdf4;
}
.cert-qr-img {
  width: 84px;
  height: 84px;
  padding: 4px;
  background: #fff;
  border-radius: 8px;
  flex-shrink: 0;
}
.cert-qr-title { font-size: 13px; font-weight: 600; color: #065f46; }
.cert-qr-sub { font-size: 11px; color: #64748b; line-height: 1.6; }
/* 认证专用章：右对齐、略微倾斜，模拟盖印 */
.cert-seal-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 4px;
}
.cert-seal-svg {
  width: 112px;
  height: 112px;
  transform: rotate(-8deg);
  opacity: 0.93;
}
.chain-proof { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #059669; }

/* 手机端证书正文：压缩字号与留白，长哈希/长文本自动换行，避免横向溢出 */
.certificate.is-mobile {
  padding: 0;
  width: 100%;
  max-width: 100%;
  overflow-x: hidden;
}
.certificate.is-mobile .cert-top { padding: 12px 0; }
.certificate.is-mobile .cert-title { font-size: 17px; margin: 8px 0 4px; }
.certificate.is-mobile .cert-id { font-size: 12px; word-break: break-all; }
.certificate.is-mobile .cert-content { padding: 14px 0; }
.certificate.is-mobile .cert-content p { font-size: 13px; line-height: 1.7; }
.certificate.is-mobile .cert-big-amount { font-size: 26px; }
/* 手机端二维码区：缩小二维码、收紧留白 */
.certificate.is-mobile .cert-qr { gap: 10px; padding: 10px; }
.certificate.is-mobile .cert-qr-img { width: 72px; height: 72px; }
.certificate.is-mobile .cert-qr-sub { font-size: 11px; }
.certificate.is-mobile .cert-seal-svg { width: 96px; height: 96px; }
/* 明细表：缩小字号并允许标签换行，保证内容完整可见 */
.certificate.is-mobile :deep(.el-descriptions__body) { font-size: 12px; }
.certificate.is-mobile :deep(.el-descriptions__table) { table-layout: fixed; width: 100%; }
.certificate.is-mobile :deep(.el-descriptions__cell) { word-break: break-all; }
.certificate.is-mobile :deep(.el-descriptions__label) { width: 92px; }
.certificate.is-mobile :deep(.el-descriptions__content) { word-break: break-all; }
.certificate.is-mobile .chain-proof { font-size: 12px; text-align: left; }
</style>
