<!--
  CertValidity.vue —— 认证有效性验证页（公共查询端，无需登录）
  用途：社会公众输入认证电子防伪码或交易哈希，通过区块链哈希比对校验证书的有效性与完整性，
  并识别证书是否已被撤销。
-->
<template>
  <div class="public-page">
    <!-- 页面标题区 -->
    <div class="hero">
      <h1>认证有效性验证</h1>
      <p>通过区块链哈希校验，验证碳排放认证证书的有效性与完整性</p>
    </div>

    <!-- 验证输入区：输入哈希 + 验证按钮 + 原理说明 -->
    <div class="verify-box panel">
      <div class="verify-input">
        <el-input v-model="inputHash" type="textarea" :rows="3" placeholder="请输入认证电子防伪码或交易哈希进行验证" />
        <el-button type="primary" size="large" style="margin-top: 12px" @click="verify">
          <el-icon><Key /></el-icon> 区块链验证
        </el-button>
      </div>
      <div class="verify-tip">
        <el-icon><InfoFilled /></el-icon>
        验证原理：系统将输入的哈希与区块链上存证的指纹进行比对，确认数据未被篡改且认证状态有效。
      </div>
    </div>

    <!-- 验证结果面板：展示通过/失败状态及证书详情 -->
    <div v-if="result" class="result-panel panel" :class="result.valid ? 'valid' : 'invalid'">
      <div class="result-top">
        <el-icon :size="52"><component :is="result.valid ? 'CircleCheck' : 'CircleClose'" /></el-icon>
        <div class="result-text">
          <h2>{{ result.valid ? '验证通过' : '验证失败' }}</h2>
          <p>{{ result.msg }}</p>
        </div>
      </div>
      <div v-if="result.valid && result.cert" class="result-detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="认证编号">{{ result.cert.id }}</el-descriptions-item>
          <el-descriptions-item label="企业">{{ result.cert.enterpriseName }}</el-descriptions-item>
          <el-descriptions-item label="报告日期">{{ result.cert.period }}</el-descriptions-item>
          <el-descriptions-item label="排放量">{{ result.cert.amount }} tCO₂</el-descriptions-item>
          <el-descriptions-item label="电子防伪码" :span="2"><span class="hash-text">{{ result.cert.fingerprint }}</span></el-descriptions-item>
          <el-descriptions-item label="交易哈希" :span="2"><span class="hash-text">{{ result.cert.txHash }}</span></el-descriptions-item>
          <el-descriptions-item label="所在区块">第 {{ result.cert.blockIndex }} 号区块</el-descriptions-item>
          <el-descriptions-item label="认证状态">
            <el-tag :type="result.cert.status === 'revoked' ? 'danger' : 'success'" size="small">
              {{ result.cert.status === 'revoked' ? '已撤销' : '有效' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <div class="chain-proof">
          <el-icon><Link /></el-icon>
          该电子防伪码已通过区块链完整性校验，与链上存证一致，未被篡改。
        </div>
        <!-- 链上合规判定：判定由智能合约执行，结果随认证存证一起上链 -->
        <div class="compliance-box">
          <div class="compliance-head">
            <h3>链上合规判定</h3>
            <el-tag v-if="compliance" :type="compliance.compliant ? 'success' : 'danger'" size="small">
              {{ compliance.compliant ? '合规' : '命中异常规则' }}
            </el-tag>
            <span v-else class="compliance-loading">读取中…</span>
          </div>
          <ul v-if="compliance && compliance.items && compliance.items.length" class="compliance-list">
            <li v-for="item in compliance.items" :key="item.rule">
              {{ item.typeLabel }}
              <span class="level">（{{ levelLabel(item.level) }}）</span>
            </li>
          </ul>
          <p v-else-if="compliance" class="compliance-ok">该项排放数据未命中任何异常规则。</p>
        </div>
      </div>
    </div>

    <!-- 示例哈希区：未验证时展示可点击填充的示例哈希 -->
    <div class="samples panel" v-if="!result">
      <h3>可用于验证的示例哈希</h3>
      <div class="sample-list">
        <div class="sample-item" v-for="c in samples" :key="c.id" @click="inputHash = c.fingerprint">
          <div class="s-label">{{ c.id }}</div>
          <div class="s-hash hash-text">{{ c.fingerprint }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
// ===== 依赖引入 =====
import { ref, onMounted } from 'vue'
// 认证校验接口与固定示例证书接口
import { verifyCertification } from '@/api/certification'
import { getSampleCerts } from '@/api/publicApi'
import { getPublicCompliance } from '@/api/compliance'

// ===== 验证状态 =====
const inputHash = ref('')     // 输入的哈希
const result = ref(null)      // 验证结果
const samples = ref([])       // 示例哈希（从公共接口获取真实数据）
const compliance = ref(null)  // 链上合规判定快照

// 执行验证：调用后端校验接口比对防伪码 / 交易哈希
async function verify() {
  const hash = inputHash.value.trim()
  if (!hash) return
  compliance.value = null
  try {
    result.value = await verifyCertification({ hash })
  } catch (e) {
    result.value = null
  }
  // 验证通过后补充读取该证书的链上合规判定快照
  const fingerprint = result.value?.valid ? result.value?.cert?.fingerprint : null
  if (fingerprint) {
    try {
      compliance.value = await getPublicCompliance(fingerprint)
    } catch (e) {
      compliance.value = null
    }
  }
}

// 告警级别中文名
function levelLabel(level) {
  return { high: '高危', medium: '中危', low: '低危' }[level] || level
}

// 初始化：拉取固定示例证书用于展示示例防伪码
onMounted(async () => {
  try {
    const list = await getSampleCerts()
    samples.value = list.map(c => ({ id: c.id, fingerprint: c.fingerprint }))
  } catch (e) {
    samples.value = []
  }
})
</script>

<style scoped>
/* 页面容器：限制最大宽度并居中 */
.public-page { max-width: 800px; margin: 0 auto; }
/* 标题区 */
.hero { text-align: center; margin-bottom: 28px; }
.hero h1 { font-size: 28px; color: #0f172a; margin: 0 0 8px; }
.hero p { color: #64748b; margin: 0; }
/* 验证输入区 */
.verify-box { padding: 28px; }
.verify-input { text-align: center; }
.verify-input :deep(.el-textarea) { text-align: left; }
.verify-tip {
  margin-top: 16px; padding: 12px 16px; background: #f0f9ff; border-radius: 8px;
  font-size: 13px; color: #0369a1; display: flex; align-items: center; gap: 6px;
}
/* 验证结果面板：通过/失败两种配色 */
.result-panel { padding: 24px; margin-top: 20px; }
.result-panel.valid { border-color: #10b981; background: #ecfdf5; }
.result-panel.invalid { border-color: #ef4444; background: #fef2f2; }
.result-top { display: flex; align-items: center; gap: 16px; }
.result-panel.valid .result-top { color: #10b981; }
.result-panel.invalid .result-top { color: #ef4444; }
.result-text h2 { margin: 0 0 4px; font-size: 22px; }
.result-text p { margin: 0; color: #475569; font-size: 13px; }
.result-detail { margin-top: 20px; }
/* 链上存证完整性提示 */
.chain-proof {
  margin-top: 16px; padding: 12px 16px; background: #fff; border-radius: 8px;
  font-size: 13px; color: #059669; display: flex; align-items: center; gap: 6px;
}
/* 链上合规判定区块 */
.compliance-box {
  margin-top: 16px; padding: 14px 16px; background: #fff; border-radius: 8px;
  border: 1px solid var(--border);
}
.compliance-head { display: flex; align-items: center; gap: 10px; }
.compliance-head h3 { margin: 0; font-size: 14px; color: #0f172a; }
.compliance-loading { font-size: 12px; color: #94a3b8; }
.compliance-list { margin: 10px 0 0; padding-left: 18px; font-size: 13px; color: #b45309; }
.compliance-list .level { color: #94a3b8; }
.compliance-ok { margin: 10px 0 0; font-size: 13px; color: #059669; }
/* 示例哈希区 */
.samples { padding: 20px; margin-top: 20px; }
.samples h3 { margin: 0 0 12px; font-size: 15px; }
.sample-item {
  padding: 10px 14px; background: #f8fafc; border-radius: 8px; margin-bottom: 8px;
  cursor: pointer; transition: background .2s;
}
.sample-item:hover { background: #ecfdf5; }
.s-label { font-size: 13px; font-weight: 600; color: #0f172a; margin-bottom: 4px; }
.s-hash { font-size: 11px; }
</style>
