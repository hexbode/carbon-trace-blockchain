<!--
  QrVerify.vue —— 二维码验证页（公共查询端，无需登录）
  用途：输入认证证书上的电子防伪码，系统依据防伪码生成对应二维码，
        模拟扫码后核验该证书的碳排放数据认证信息、电子防伪码与区块链存证状态。
  说明：二维码内容即电子防伪码；公共端不再支持按认证编号解析。
-->
<template>
  <div class="public-page">
    <!-- 页面标题区 -->
    <div class="hero">
      <h1>二维码验证</h1>
      <p>对准证书二维码扫码，或上传二维码图片，核验碳排放认证信息与链上存证</p>
    </div>

    <!-- 二维码与操作区：左侧真实二维码预览，右侧输入防伪码并执行验证 -->
    <div class="verify-box panel" :class="{ 'is-mobile': isMobile }">
      <!-- 真实二维码：依据防伪码实时生成，可被手机相机或任意扫码器识别 -->
      <div class="qr-wrap" :class="{ 'is-mobile': isMobile }">
        <img v-if="qrDataUrl" :src="qrDataUrl" class="qr-image" alt="证书二维码" />
        <div v-else class="qr-placeholder">
          <el-icon :size="34"><FullScreen /></el-icon>
          <span>输入防伪码后生成二维码</span>
        </div>
      </div>

      <!-- 操作区：输入防伪码 + 示例 + 验证按钮 + 结果提示 -->
      <div class="qr-actions">
        <div class="verify-text">
          <h3>输入电子防伪码</h3>
          <p>输入或选择证书上的电子防伪码，系统会生成真实二维码，可直接用手机相机扫描，也可在本页扫码核验。</p>
        </div>

        <el-input v-model="inputFp" placeholder="请输入电子防伪码，如 nYDELr7rmCRl" clearable @keyup.enter="verify">
          <template #prefix><el-icon><Key /></el-icon></template>
        </el-input>

        <div class="quick">
          <span class="q-label">示例防伪码：</span>
          <el-tag
            v-for="c in samples"
            :key="c.id"
            effect="plain"
            class="q-tag"
            @click="useSample(c)"
          >{{ c.fingerprint }}</el-tag>
        </div>

        <!-- 真实扫码区：摄像头识别 / 上传二维码图片识别 -->
        <div class="scan-block">
          <div class="scan-head">
            <span class="scan-title">扫码核验</span>
            <span class="scan-sub">实时扫描，或拍照 / 上传二维码图片识别</span>
          </div>

          <!-- 摄像头预览：识别成功后自动停止 -->
          <div v-if="cameraOn" class="camera-box">
            <video ref="videoRef" class="camera-video" playsinline muted></video>
            <div class="scan-frame"></div>
            <el-button class="camera-stop" size="small" type="danger" plain @click="stopCamera">停止扫描</el-button>
          </div>

          <div class="scan-actions">
            <el-button type="primary" plain @click="cameraOn ? stopCamera() : startCamera()">
              <el-icon><Camera /></el-icon> {{ cameraOn ? '停止摄像头' : '打开摄像头扫码' }}
            </el-button>
            <el-button plain @click="fileRef?.click()">
              <el-icon><Camera /></el-icon> 拍照 / 上传二维码
            </el-button>
            <!-- 隐藏的文件选择框：支持相册选择或直接调用相机拍照 -->
            <input ref="fileRef" type="file" accept="image/*" capture="environment" hidden @change="onFileChange" />
          </div>

          <p v-if="!secureContext" class="scan-tip">
            <span class="tip-line">HTTP 页面无法调用网页实时摄像头，但可点「拍照 / 上传二维码」调起手机相机拍照识别。</span>
            <span class="tip-line">需要实时扫码请使用 HTTPS 地址：<a class="scan-link" :href="httpsUrl">{{ httpsUrl }}</a></span>
            <span class="tip-line">首次会提示证书风险，可先 <a class="scan-link" :href="certUrl">下载证书</a> 安装信任。</span>
          </p>
        </div>

        <el-button type="primary" :disabled="!inputFp.trim()" style="width: 100%" @click="verify">
          <el-icon><FullScreen /></el-icon> 验证该防伪码
        </el-button>

        <!-- 验证结果：成功时展示证书关键信息与链上存证状态 -->
        <div v-if="result" class="verify-result" :class="result.valid ? 'valid' : 'invalid'">
          <div class="vr-title">
            <el-icon><component :is="result.valid ? 'CircleCheck' : 'CircleClose'" /></el-icon>
            {{ result.valid ? '验证通过' : '验证失败' }}
          </div>
          <div class="vr-msg">{{ result.msg }}</div>
          <el-descriptions v-if="result.cert" :column="1" border size="small" style="margin-top: 12px">
            <el-descriptions-item label="认证编号">{{ result.cert.id }}</el-descriptions-item>
            <el-descriptions-item label="企业">{{ result.cert.enterpriseName }}</el-descriptions-item>
            <el-descriptions-item label="报告日期">{{ formatPeriod(result.cert.period) }}</el-descriptions-item>
            <el-descriptions-item label="认证排放量">{{ result.cert.amount }} tCO₂</el-descriptions-item>
            <el-descriptions-item label="电子防伪码">{{ result.cert.fingerprint }}</el-descriptions-item>
            <el-descriptions-item label="所在区块">第 {{ result.cert.blockIndex }} 号区块</el-descriptions-item>
          </el-descriptions>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// 引入 Vue 响应式 API
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
// 引入 Element Plus 消息提示
import { ElMessage } from 'element-plus'
// 引入二维码生成库：把电子防伪码生成真实可扫描的二维码
import QRCode from 'qrcode'
// 引入纯 JS 二维码识别库：用于摄像头画面与上传图片的解码
import jsQR from 'jsqr'
// 引入公共证书查询接口与固定示例证书接口
import { qrResolve, getSampleCerts } from '@/api/publicApi'
// 引入设备检测：手机端二维码与操作区改为上下排列
import { useDevice } from '@/composables/useDevice'

// 设备类型（手机端切换布局）
const { isMobile } = useDevice()

// 当前输入的电子防伪码
const inputFp = ref('')
// 示例证书（固定 3 张演示证书）
const samples = ref([])
// 验证结果
const result = ref(null)

// ===== 扫码相关状态 =====
// 摄像头视频元素与隐藏的文件选择框
const videoRef = ref(null)
const fileRef = ref(null)
// 摄像头是否正在扫描
const cameraOn = ref(false)
// 是否为安全上下文（HTTPS 或 localhost）；HTTP 局域网下浏览器禁用摄像头
const secureContext = window.isSecureContext
// HTTP 访问时提示的 HTTPS 地址（8443 已映射到 nginx 443，自签证书）
const httpsUrl = computed(() => `https://${window.location.hostname}:8443/#/public/qr`)
// 自签证书下载地址：手机安装并信任后可不再出现安全警告
const certUrl = computed(() => `${window.location.origin}/carbon.crt`)
// 摄像头媒体流与动画帧句柄
let stream = null
let rafId = 0

// 真实二维码图片（data URL）：由电子防伪码实时生成
const qrDataUrl = ref('')
// 防抖句柄：避免输入过程中频繁生成
let qrTimer = null

// 核验页面基地址：HTTP 访问时统一指向 HTTPS(8443)，保证扫码打开的是安全上下文（网页摄像头可用）
function verificationBase() {
  if (window.location.protocol === 'https:') return window.location.origin
  return `https://${window.location.hostname}:8443`
}

// 二维码内容：编码为可直接打开的核验链接，手机相机扫码后可跳转并自动查询
function buildQrPayload(fp) {
  return `${verificationBase()}/#/public/query?fp=${encodeURIComponent(fp)}`
}

// 依据防伪码生成真实二维码；防伪码为空时清空预览
async function generateQr(fp) {
  if (!fp) {
    qrDataUrl.value = ''
    return
  }
  try {
    qrDataUrl.value = await QRCode.toDataURL(buildQrPayload(fp), {
      width: 480,
      margin: 1,
      errorCorrectionLevel: 'M',
      color: { dark: '#071a16', light: '#ffffff' }
    })
  } catch (e) {
    qrDataUrl.value = ''
  }
}

// 输入防伪码变化后防抖生成二维码
watch(inputFp, (value) => {
  if (qrTimer) clearTimeout(qrTimer)
  qrTimer = setTimeout(() => generateQr((value || '').trim()), 200)
}, { immediate: true })

// 页面卸载时清理防抖句柄
onBeforeUnmount(() => {
  if (qrTimer) clearTimeout(qrTimer)
})

// 点击示例防伪码：填充并立即执行验证
function useSample(cert) {
  inputFp.value = cert.fingerprint
  verify()
}

// 执行验证：按电子防伪码解析证书并判断状态
async function verify() {
  const fp = inputFp.value.trim()
  if (!fp) return
  try {
    const cert = await qrResolve({ fingerprint: fp })
    if (cert.status === 'revoked') {
      result.value = { valid: false, msg: '该证书已被监管机构撤销，认证已失效。', cert }
      return
    }
    result.value = { valid: true, msg: '二维码解析成功，电子防伪码与链上存证一致，认证有效。', cert }
  } catch (e) {
    result.value = { valid: false, msg: '二维码解析失败，未匹配到有效认证信息，请核对电子防伪码。' }
  }
}

// ===== 扫码：摄像头识别 =====
// 复用的解码画布（摄像头逐帧与图片解码共用）
let decodeCanvas = null
function getDecodeCanvas() {
  if (!decodeCanvas) decodeCanvas = document.createElement('canvas')
  return decodeCanvas
}

// 打开摄像头并开始逐帧解码；HTTP 局域网或权限被拒时给出明确指引
async function startCamera() {
  if (!secureContext || !navigator.mediaDevices?.getUserMedia) {
    ElMessage.warning('当前环境不支持实时摄像头，请点「拍照 / 上传二维码」识别')
    return
  }
  cameraOn.value = true
  await nextTick()
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: 'environment' } },
      audio: false
    })
    const video = videoRef.value
    video.srcObject = stream
    await video.play()
    scanFrame()
  } catch (e) {
    stopCamera()
    const msg = e?.name === 'NotAllowedError'
      ? '摄像头被拒绝：若使用自签 HTTPS，请先在手机安装并信任证书；也可改用「拍照 / 上传二维码」'
      : e?.name === 'NotFoundError'
        ? '未检测到可用摄像头，请改用「拍照 / 上传二维码」'
        : '摄像头启动失败，请改用「拍照 / 上传二维码」'
    ElMessage.warning(msg)
  }
}

// 停止摄像头并释放媒体资源
function stopCamera() {
  if (rafId) {
    cancelAnimationFrame(rafId)
    rafId = 0
  }
  if (stream) {
    stream.getTracks().forEach(track => track.stop())
    stream = null
  }
  if (videoRef.value) videoRef.value.srcObject = null
  cameraOn.value = false
}

// 逐帧解码摄像头画面：识别到二维码后自动停止并进入验证
function scanFrame() {
  const video = videoRef.value
  if (!video || !cameraOn.value) return
  if (video.readyState === video.HAVE_ENOUGH_DATA) {
    const canvas = getDecodeCanvas()
    canvas.width = video.videoWidth
    canvas.height = video.videoHeight
    const ctx = canvas.getContext('2d', { willReadFrequently: true })
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height)
    const image = ctx.getImageData(0, 0, canvas.width, canvas.height)
    const code = jsQR(image.data, image.width, image.height, { inversionAttempts: 'attemptBoth' })
    if (code?.data) {
      handleScan(code.data)
      return
    }
  }
  rafId = requestAnimationFrame(scanFrame)
}

// ===== 扫码：上传二维码图片识别 =====
function onFileChange(event) {
  const file = event.target.files && event.target.files[0]
  if (!file) return
  const objectUrl = URL.createObjectURL(file)
  const img = new Image()
  img.onload = () => {
    const canvas = getDecodeCanvas()
    // 超大图片先等比缩小，避免解码耗时过长
    const maxSide = 1400
    const scale = Math.min(1, maxSide / Math.max(img.width, img.height))
    canvas.width = Math.round(img.width * scale)
    canvas.height = Math.round(img.height * scale)
    const ctx = canvas.getContext('2d', { willReadFrequently: true })
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
    const image = ctx.getImageData(0, 0, canvas.width, canvas.height)
    const code = jsQR(image.data, image.width, image.height, { inversionAttempts: 'attemptBoth' })
    URL.revokeObjectURL(objectUrl)
    if (code?.data) {
      handleScan(code.data)
    } else {
      ElMessage.warning('未在图片中识别到二维码，请上传清晰的二维码图片')
    }
  }
  img.onerror = () => {
    URL.revokeObjectURL(objectUrl)
    ElMessage.warning('图片读取失败，请重新选择')
  }
  img.src = objectUrl
  // 允许重复选择同一个文件
  event.target.value = ''
}

// 从二维码内容中提取电子防伪码：兼容纯文本、URL 参数、JSON 三种形式
function extractFingerprint(raw) {
  const text = String(raw || '').trim()
  if (!text) return ''
  if (text.startsWith('{')) {
    try {
      const data = JSON.parse(text)
      const fp = data.fingerprint || data.fp || data.code
      if (fp) return String(fp).trim()
    } catch (e) {
      // 非合法 JSON，继续按普通文本处理
    }
  }
  if (/^https?:\/\//i.test(text) || text.includes('#/')) {
    try {
      const url = new URL(text, window.location.origin)
      const hashQuery = url.hash.includes('?') ? url.hash.slice(url.hash.indexOf('?') + 1) : ''
      const params = new URLSearchParams(hashQuery || url.search)
      const fp = params.get('fp') || params.get('fingerprint') || params.get('code')
      if (fp) return fp.trim()
    } catch (e) {
      // 非法 URL，按纯文本处理
    }
  }
  return text
}

// 扫码成功：停止摄像头、回填防伪码并立即验证
async function handleScan(raw) {
  const fp = extractFingerprint(raw)
  if (!fp) {
    ElMessage.warning('二维码内容为空，请重新扫描')
    return
  }
  stopCamera()
  inputFp.value = fp
  await verify()
  if (result.value?.valid) {
    ElMessage.success('二维码识别成功')
  } else {
    ElMessage.warning('二维码已识别，但未匹配到有效认证信息')
  }
}

// 离开页面时释放摄像头，避免后台占用
onBeforeUnmount(stopCamera)

// 初始化：拉取固定示例证书，展示其电子防伪码
onMounted(async () => {
  try {
    samples.value = await getSampleCerts()
  } catch (e) {
    samples.value = []
  }
})

// 将 '2024-08' 形式的报告期格式化为 '2024年8月'
function formatPeriod(p) {
  if (!p) return '-'
  const [year, month] = p.split('-')
  return `${year}年${Number(month)}月`
}
</script>

<style scoped>
/* 页面容器：限制最大宽度并居中 */
.public-page { max-width: 900px; margin: 0 auto; }
/* 标题区 */
.hero { text-align: center; margin-bottom: 28px; }
.hero h1 { font-size: 28px; color: #0f172a; margin: 0 0 8px; }
.hero p { color: #64748b; margin: 0; }

/* 验证区：左右分栏，二维码在左、操作区在右 */
.verify-box {
  padding: 28px;
  display: flex;
  gap: 32px;
  align-items: flex-start;
}
/* 二维码外层：白色卡片 + 相对定位，用于叠加定位角 */
.qr-wrap {
  position: relative;
  width: 220px;
  height: 220px;
  padding: 12px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  flex-shrink: 0;
}
/* 真实二维码图片：完整铺满预览框 */
.qr-image {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
  border-radius: 6px;
}
/* 未输入防伪码时的占位提示 */
.qr-placeholder {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 12px;
  text-align: center;
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.5;
}

/* 右侧操作区 */
.qr-actions { flex: 1; min-width: 280px; display: flex; flex-direction: column; gap: 14px; }
.verify-text h3 { margin: 0 0 6px; font-size: 18px; color: #0f172a; }
.verify-text p { margin: 0; font-size: 13px; color: #64748b; line-height: 1.7; }
/* 示例防伪码 */
.quick { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.q-label { font-size: 12px; color: #94a3b8; }
.q-tag { cursor: pointer; }

/* ---------- 扫码区 ---------- */
.scan-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  border: 1px dashed #cbd5e1;
  border-radius: 12px;
  background: #f8fafc;
}
.scan-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}
.scan-title {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
}
.scan-sub {
  font-size: 12px;
  color: #94a3b8;
}
/* 摄像头预览与取景框 */
.camera-box {
  position: relative;
  width: 100%;
  aspect-ratio: 4 / 3;
  border-radius: 12px;
  overflow: hidden;
  background: #0f172a;
}
.camera-video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.scan-frame {
  position: absolute;
  inset: 14%;
  border: 2px solid rgba(200, 241, 105, 0.9);
  border-radius: 12px;
  box-shadow: 0 0 0 999px rgba(7, 26, 22, 0.35);
  pointer-events: none;
}
.camera-stop {
  position: absolute;
  right: 10px;
  bottom: 10px;
}
/* 扫码按钮：等宽换行 */
.scan-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.scan-actions .el-button {
  flex: 1 1 150px;
  margin-left: 0;
}
/* HTTP 环境下的摄像头限制提示 */
.scan-tip {
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: #b45309;
}
.tip-line {
  display: block;
}
.scan-link {
  color: #0f9d76;
  font-weight: 600;
  font-family: var(--m-mono, ui-monospace, monospace);
  font-size: 10px;
  overflow-wrap: anywhere;
}

/* 验证结果块：通过 / 失败两种配色 */
.verify-result { padding: 14px 16px; border-radius: 10px; font-size: 13px; }
.verify-result.valid { background: #ecfdf5; border: 1px solid #a7f3d0; }
.verify-result.invalid { background: #fef2f2; border: 1px solid #fecaca; }
.vr-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  margin-bottom: 6px;
}
.verify-result.valid .vr-title { color: #059669; }
.verify-result.invalid .vr-title { color: #dc2626; }
.vr-msg { color: #475569; line-height: 1.7; }
/* 结果中的认证编号、电子防伪码等长文本允许换行 */
.verify-result :deep(.el-descriptions__content) { word-break: break-all; }

/* 手机端：二维码与操作区上下排列，二维码居中并缩小，避免固定宽度撑出横向滚动 */
.verify-box.is-mobile {
  flex-direction: column;
  gap: 18px;
  padding: 16px;
}
.qr-wrap.is-mobile {
  width: 168px;
  height: 168px;
  margin: 0 auto;
}
/* 操作区取消最小宽度限制，输入框与按钮占满整行，保证可点击提交 */
.verify-box.is-mobile .qr-actions { min-width: 0; width: 100%; }

@media (max-width: 767px) {
  /* 标题与留白收紧，避免超大标题在窄屏溢出 */
  .hero { margin-bottom: 18px; }
  .hero h1 { font-size: 22px; }
  .hero p { font-size: 13px; line-height: 1.6; }
}
</style>
