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
      <p>输入电子防伪码生成二维码，模拟扫码核验碳排放认证信息与链上存证</p>
    </div>

    <!-- 二维码与操作区：左侧二维码预览，右侧输入防伪码并执行验证 -->
    <div class="verify-box panel">
      <!-- 二维码点阵预览：20×20 网格，图案由电子防伪码确定性生成 -->
      <div class="qr-wrap">
        <div class="qr-matrix">
          <div
            v-for="(cell, i) in matrix"
            :key="i"
            class="qr-cell"
            :class="{ dark: cell }"
          ></div>
        </div>
        <!-- 三个定位角：模拟二维码的定位图案 -->
        <div class="qr-corner tl"></div>
        <div class="qr-corner tr"></div>
        <div class="qr-corner bl"></div>
      </div>

      <!-- 操作区：输入防伪码 + 示例 + 验证按钮 + 结果提示 -->
      <div class="qr-actions">
        <div class="verify-text">
          <h3>输入电子防伪码</h3>
          <p>输入或选择证书上的电子防伪码，系统会依据防伪码生成对应二维码，点击下方按钮模拟扫码验证。</p>
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

        <el-button type="primary" :disabled="!inputFp.trim()" style="width: 100%" @click="verify">
          <el-icon><FullScreen /></el-icon> 模拟扫码验证
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
import { ref, computed, onMounted } from 'vue'
// 引入公共证书查询接口与固定示例证书接口
import { qrResolve, getSampleCerts } from '@/api/publicApi'

// 当前输入的电子防伪码
const inputFp = ref('')
// 示例证书（固定 3 张演示证书）
const samples = ref([])
// 验证结果
const result = ref(null)

// 二维码点阵：依据电子防伪码确定性生成 20×20 的明暗格子
const matrix = computed(() => {
  const seed = inputFp.value.trim() || '0'
  // 简单线性同余伪随机，保证同一防伪码每次生成的图案一致
  let state = 0
  for (let i = 0; i < seed.length; i++) state = (state * 31 + seed.charCodeAt(i)) >>> 0
  const cells = []
  for (let i = 0; i < 400; i++) {
    state = (state * 1103515245 + 12345) >>> 0
    cells.push(((state >>> 16) & 1) === 1)
  }
  return cells
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
/* 二维码点阵网格：20×20 */
.qr-matrix {
  width: 100%; height: 100%;
  display: grid; grid-template-columns: repeat(20, 1fr); grid-template-rows: repeat(20, 1fr);
}
.qr-cell { background: #fff; }
.qr-cell.dark { background: #0f172a; }
/* 二维码三个定位角 */
.qr-corner {
  position: absolute;
  width: 46px;
  height: 46px;
  border: 6px solid #0f172a;
  background: #fff;
  border-radius: 4px;
}
.qr-corner.tl { top: 8px; left: 8px; }
.qr-corner.tr { top: 8px; right: 8px; }
.qr-corner.bl { bottom: 8px; left: 8px; }

/* 右侧操作区 */
.qr-actions { flex: 1; min-width: 280px; display: flex; flex-direction: column; gap: 14px; }
.verify-text h3 { margin: 0 0 6px; font-size: 18px; color: #0f172a; }
.verify-text p { margin: 0; font-size: 13px; color: #64748b; line-height: 1.7; }
/* 示例防伪码 */
.quick { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.q-label { font-size: 12px; color: #94a3b8; }
.q-tag { cursor: pointer; }

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
</style>
