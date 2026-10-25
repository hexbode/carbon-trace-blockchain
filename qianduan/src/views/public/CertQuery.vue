<!--
  CertQuery.vue —— 电子防伪码查询页（公共查询端，无需登录）
  用途：社会公众输入认证证书上的电子防伪码，查询该企业碳排放数据的认证信息与链上存证详情。
  安全说明：认证编号为连续编号、可被遍历枚举，因此公共端不提供按认证编号查询的能力，
            仅允许使用电子防伪码（12 位随机串）查询。
-->
<template>
  <div class="public-page">
    <!-- 页面标题区 -->
    <div class="hero">
      <h1>电子防伪码查询</h1>
      <p>输入证书上的电子防伪码，查询碳排放数据认证信息与链上存证</p>
    </div>

    <!-- 搜索区：输入防伪码 + 示例防伪码快捷查询 -->
    <div class="search-box panel">
      <el-input v-model="query" size="large" placeholder="请输入电子防伪码，如 nYDELr7rmCRl" clearable @keyup.enter="doSearch">
        <template #prefix><el-icon><Search /></el-icon></template>
        <template #append><el-button type="primary" @click="doSearch">查询</el-button></template>
      </el-input>
      <div class="quick">
        <span class="qs-label">示例防伪码：</span>
        <el-tag
          v-for="c in samples"
          :key="c.id"
          class="qs-tag"
          effect="plain"
          @click="query = c.fingerprint; doSearch()"
        >{{ c.fingerprint }}</el-tag>
      </div>
      <div class="query-tip">
        <el-icon><InfoFilled /></el-icon>
        电子防伪码位于企业认证证书上，认证编号不支持直接查询。
      </div>
    </div>

    <!-- 查询结果卡片：展示认证企业、排放量、电子防伪码与区块链存证信息 -->
    <div v-if="result" class="result-card panel">
      <div class="result-header" :class="{ 'is-mobile': isMobile }">
        <el-icon :size="isMobile ? 32 : 40" color="#10b981"><Stamp /></el-icon>
        <div class="result-title">
          <h3>{{ result.enterpriseName }}</h3>
          <p>认证编号：{{ result.id }} · {{ result.certifiedAt }}</p>
        </div>
        <el-tag :type="result.status === 'revoked' ? 'danger' : 'success'" effect="dark" :size="isMobile ? 'default' : 'large'">
          <el-icon><CircleClose v-if="result.status === 'revoked'" /><CircleCheck v-else /></el-icon>
          {{ result.status === 'revoked' ? '已撤销' : '认证有效' }}
        </el-tag>
      </div>
      <!-- 手机端：字段较多，改用卡片纵向列表展示，长文本可完整换行 -->
      <div v-if="isMobile" class="m-list mobile-result">
        <div class="m-card">
          <div class="m-row"><span class="m-key">报告日期</span><span class="m-val">{{ formatPeriod(result.period) }}</span></div>
          <div class="m-row"><span class="m-key">认证排放量</span><span class="m-val text-primary" style="font-weight: 700">{{ result.amount }} tCO₂</span></div>
          <div class="m-row m-row-block"><span class="m-key">电子防伪码</span><span class="m-val fp-text">{{ result.fingerprint }}</span></div>
          <div class="m-row m-row-block"><span class="m-key">区块链交易哈希</span><span class="m-val hash-text">{{ result.txHash }}</span></div>
          <div class="m-row"><span class="m-key">所在区块</span><span class="m-val">第 {{ result.blockIndex }} 号区块</span></div>
          <div class="m-row"><span class="m-key">审核人</span><span class="m-val">{{ result.auditor }}</span></div>
          <div class="m-row m-row-block"><span class="m-key">审核意见</span><span class="m-val">{{ result.auditOpinion }}</span></div>
          <div class="m-card-actions">
            <el-button size="small" text type="primary" @click="copyTxHash">
              <el-icon><CopyDocument /></el-icon> 复制交易哈希
            </el-button>
          </div>
        </div>
      </div>
      <el-descriptions v-else :column="2" border>
        <el-descriptions-item label="报告日期">{{ formatPeriod(result.period) }}</el-descriptions-item>
        <el-descriptions-item label="认证排放量"><span class="text-primary" style="font-weight:700">{{ result.amount }} tCO₂</span></el-descriptions-item>
        <el-descriptions-item label="电子防伪码" :span="2"><span class="fp-text">{{ result.fingerprint }}</span></el-descriptions-item>
        <el-descriptions-item label="区块链交易哈希" :span="2">
          <span class="hash-text">{{ result.txHash }}</span>
          <el-button text type="primary" size="small" class="copy-btn" @click="copyTxHash">
            <el-icon><CopyDocument /></el-icon> 复制
          </el-button>
        </el-descriptions-item>
        <el-descriptions-item label="所在区块">第 {{ result.blockIndex }} 号区块</el-descriptions-item>
        <el-descriptions-item label="审核人">{{ result.auditor }}</el-descriptions-item>
        <el-descriptions-item label="审核意见" :span="2">{{ result.auditOpinion }}</el-descriptions-item>
      </el-descriptions>
    </div>

    <!-- 查询无结果时的空状态 -->
    <el-empty v-else-if="searched" description="未查询到该电子防伪码，请核对防伪码后重试" />
  </div>
</template>

<script setup>
// ===== 依赖引入 =====
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
// 认证记录接口与固定示例证书接口
import { getCertificationByFingerprint } from '@/api/certification'
import { getSampleCerts } from '@/api/publicApi'
// 引入设备检测：手机端结果改用卡片列表展示
import { useDevice } from '@/composables/useDevice'

// 设备类型（手机端切换结果展示方式）
const { isMobile } = useDevice()
// 当前路由：用于接收扫码链接中的 fp 参数（自动查询）
const route = useRoute()

// ===== 查询状态 =====
const query = ref('')       // 输入的电子防伪码
const searched = ref(false) // 是否已执行过查询（用于控制空状态显示）
const result = ref(null)    // 查询结果
// 示例防伪码（从固定示例接口获取，点击可快捷填充）
const samples = ref([])

// 执行查询：调用后端按电子防伪码精确查询接口
async function doSearch() {
  const q = query.value.trim()
  if (!q) return
  searched.value = true
  try {
    result.value = await getCertificationByFingerprint(q)
  } catch (e) {
    // 错误提示由请求拦截器统一处理，这里仅置空结果
    result.value = null
  }
}

// 初始化：优先处理扫码链接中的 fp 参数，再加载示例证书（避免示例接口较慢时阻塞自动查询）
onMounted(async () => {
  const fp = String(route.query.fp || '').trim()
  if (fp) {
    query.value = fp
    doSearch()
  }
  try {
    samples.value = await getSampleCerts()
  } catch (e) {
    samples.value = []
  }
})

// 将 '2024-08' 形式的报告期格式化为 '2024年8月'（年月形式，去掉月份前导零）
function formatPeriod(p) {
  if (!p) return '-'
  const [year, month] = p.split('-')
  return `${year}年${Number(month)}月`
}

// 复制区块链交易哈希：优先使用剪贴板 API，失败时回退到 textarea + execCommand
async function copyTxHash() {
  const text = result.value?.txHash
  if (!text) return
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      fallbackCopy(text)
    }
    ElMessage.success('区块链交易哈希已复制')
  } catch (e) {
    ElMessage.error('复制失败，请手动复制')
  }
}
// 兼容非安全上下文的复制方式
function fallbackCopy(text) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  document.execCommand('copy')
  document.body.removeChild(ta)
}
</script>

<style scoped>
/* 页面容器：限制最大宽度并居中 */
.public-page { max-width: 800px; margin: 0 auto; }
/* 标题区 */
.hero { text-align: center; margin-bottom: 28px; }
.hero h1 { font-size: 28px; color: #0f172a; margin: 0 0 8px; }
.hero p { color: #64748b; margin: 0; }
/* 搜索区 */
.search-box { padding: 24px; margin-bottom: 20px; }
.quick { margin-top: 14px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.qs-label { font-size: 12px; color: #94a3b8; }
.qs-tag { cursor: pointer; }
/* 凭据说明：仅电子防伪码可查 */
.query-tip {
  margin-top: 14px; padding: 10px 14px; background: #f8fafc; border-radius: 8px;
  font-size: 12px; color: #64748b; display: flex; align-items: center; gap: 6px;
}
/* 结果卡片 */
.result-card { padding: 0; overflow: hidden; }
.result-header {
  display: flex; align-items: center; gap: 16px;
  padding: 24px; border-bottom: 1px solid var(--border);
  background: linear-gradient(135deg, #ecfdf5, #fff);
}
.result-header h3 { margin: 0; font-size: 20px; color: #0f172a; word-break: break-all; }
.result-header p { margin: 4px 0 0; font-size: 13px; color: #64748b; word-break: break-all; }
.result-header :deep(.el-tag) { margin-left: auto; }
:deep(.el-descriptions) { padding: 20px; }
/* 电子防伪码：等宽字体并加粗，区别于长哈希；长文本允许换行 */
.fp-text { font-family: 'Consolas', 'Monaco', monospace; font-weight: 700; color: #059669; letter-spacing: .5px; word-break: break-all; }
/* 复制按钮：与交易哈希文本保持间距并垂直对齐 */
.copy-btn { margin-left: 12px; }

/* 手机端：头部允许换行，标题按需收缩，状态标签不再强制右推 */
.result-header.is-mobile { flex-wrap: wrap; align-items: flex-start; padding: 16px; gap: 12px; }
.result-header.is-mobile .result-title { flex: 1; min-width: 0; }
.result-header.is-mobile :deep(.el-tag) { margin-left: 0; }

@media (max-width: 767px) {
  /* 标题与面板内边距收紧，避免超宽标题与冗余留白 */
  .hero { margin-bottom: 18px; }
  .hero h1 { font-size: 22px; }
  .hero p { font-size: 13px; line-height: 1.6; }
  .search-box { padding: 14px; }
  .mobile-result { padding: 12px 14px; }
  /* 防伪码 / 哈希 / 审核意见等长文本：标签与内容上下排列，完整换行 */
  .mobile-result .m-row-block { flex-direction: column; gap: 0; }
  .mobile-result .m-row-block .m-val { text-align: left; }
}
</style>
