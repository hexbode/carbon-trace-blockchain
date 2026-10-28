<!--
  ChainGovernance.vue —— 链上合约治理
  用途：查看认证存证 / 合规判定 / CBAM 锚点三类合约的 owner 与写入白名单，
        并由管理员增删 writer（真实写链，后端签名账户必须是 owner）。
  权限：查询 regulator / admin；变更仅 admin。
-->
<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>链上治理</h2>
        <p>查看合约 owner 与写入白名单；白名单变更会真实写链，仅管理员可执行。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-alert v-if="status.mode === 'stub'" type="info" :closable="false" show-icon class="mb"
              title="当前为 stub 离线桩模式"
              description="治理数据来自进程内模拟，仅用于页面联调；真实权限以 WeBASE 链上合约状态为准。" />

    <div class="cards">
      <el-card v-for="c in contracts" :key="c.kind" shadow="never" class="card">
        <div class="card-head">
          <span class="card-title">{{ c.label }}</span>
          <el-tag size="small" :type="c.configured ? 'success' : 'info'">
            {{ c.configured ? '已配置' : '未配置' }}
          </el-tag>
        </div>
        <div class="kv"><span>合约类型</span><b>{{ c.kind }}</b></div>
        <div class="kv"><span>合约地址</span><b class="hash">{{ c.address || '-' }}</b></div>
        <div v-if="c.dataAddress" class="kv"><span>数据合约</span><b class="hash">{{ c.dataAddress }}</b></div>
        <div class="kv"><span>Owner</span><b class="hash">{{ c.owner || '-' }}</b></div>
        <div class="kv">
          <span>签名账户可写</span>
          <el-tag v-if="c.signerIsWriter === true" size="small" type="success">是</el-tag>
          <el-tag v-else-if="c.signerIsWriter === false" size="small" type="danger">否</el-tag>
          <el-tag v-else size="small" type="info">未知</el-tag>
        </div>
        <div v-if="c.error" class="err">{{ c.error }}</div>
      </el-card>
    </div>

    <el-card shadow="never" class="query-card">
      <div class="query-title">地址权限查询</div>
      <div class="query-row">
        <el-select v-model="query.kind" class="kind-select">
          <el-option v-for="c in contracts" :key="c.kind" :label="c.label" :value="c.kind" />
        </el-select>
        <el-input v-model="query.account" placeholder="0x + 40 位十六进制地址" clearable class="account-input" />
        <el-button type="primary" :loading="querying" @click="doQuery">查询</el-button>
      </div>

      <div v-if="queryResult" class="query-result">
        <span>Owner：<b class="hash">{{ queryResult.owner || '-' }}</b></span>
        <span>
          该地址可写：
          <el-tag size="small" :type="queryResult.writer ? 'success' : 'danger'">
            {{ queryResult.writer ? '是' : '否' }}
          </el-tag>
        </span>
      </div>

      <div v-if="isAdmin" class="write-row">
        <el-button type="success" :disabled="!queryResult || queryResult.writer" :loading="writing"
                   @click="changeWriter(true)">加入白名单</el-button>
        <el-button type="danger" :disabled="!queryResult || !queryResult.writer" :loading="writing"
                   @click="changeWriter(false)">移出白名单</el-button>
        <span class="hint">后端签名账户必须是该合约 owner，否则链上会 revert NOT_OWNER。</span>
      </div>
      <div v-else class="hint">当前账号只读；白名单变更需管理员执行。</div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store'
import { getGovernanceStatus, getWriterStatus, setWriter } from '@/api/governance'

const userStore = useUserStore()
const isAdmin = computed(() => userStore.role === 'admin')

const loading = ref(false)
const querying = ref(false)
const writing = ref(false)
const status = ref({ mode: '', logicVersion: null, signerLabel: '', signerAddress: '', contracts: [] })
const queryResult = ref(null)

const query = reactive({ kind: 'registry', account: '' })
const contracts = computed(() => status.value.contracts || [])

async function load() {
  loading.value = true
  try {
    status.value = await getGovernanceStatus()
    if (!query.account && status.value.signerAddress) {
      query.account = status.value.signerAddress
    }
  } catch (e) {
    ElMessage.error(e?.message || '治理状态加载失败')
  } finally {
    loading.value = false
  }
}

async function doQuery() {
  if (!query.account) {
    ElMessage.warning('请输入要查询的账户地址')
    return
  }
  querying.value = true
  queryResult.value = null
  try {
    queryResult.value = await getWriterStatus({ kind: query.kind, account: query.account.trim() })
  } catch (e) {
    ElMessage.error(e?.message || '权限查询失败')
  } finally {
    querying.value = false
  }
}

async function changeWriter(allowed) {
  const action = allowed ? '加入' : '移出'
  try {
    await ElMessageBox.confirm(
      `确认将 ${queryResult.value.account} ${action}「${labelOf(query.kind)}」的写入白名单？该操作会真实写链。`,
      `${action}白名单`, { type: 'warning' })
  } catch {
    return
  }
  writing.value = true
  try {
    await setWriter({ kind: query.kind, account: queryResult.value.account, allowed })
    ElMessage.success(`已${action}写入白名单`)
    await doQuery()
    await load()
  } catch (e) {
    ElMessage.error(e?.message || `${action}失败`)
  } finally {
    writing.value = false
  }
}

function labelOf(kind) {
  return contracts.value.find(c => c.kind === kind)?.label || kind
}

onMounted(load)
</script>

<style scoped>
.page { padding: 20px; }
.page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 16px; }
.page-header h2 { margin: 0 0 6px; font-size: 20px; color: #0f172a; }
.page-header p { margin: 0; color: #64748b; font-size: 13px; }
.mb { margin-bottom: 16px; }
.cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 14px; margin-bottom: 16px; }
.card { border-radius: 10px; }
.card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.card-title { font-weight: 700; color: #0f172a; }
.kv { display: flex; justify-content: space-between; gap: 12px; padding: 5px 0; font-size: 13px; color: #64748b; border-bottom: 1px dashed #e2e8f0; }
.kv:last-child { border-bottom: none; }
.kv b { color: #0f172a; text-align: right; }
.hash { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; word-break: break-all; }
.err { margin-top: 8px; color: #dc2626; font-size: 12px; }
.query-card { border-radius: 10px; }
.query-title { font-weight: 700; color: #0f172a; margin-bottom: 12px; }
.query-row { display: flex; gap: 10px; flex-wrap: wrap; }
.kind-select { width: 180px; }
.account-input { flex: 1; min-width: 260px; }
.query-result { display: flex; gap: 20px; flex-wrap: wrap; margin-top: 14px; font-size: 13px; color: #475569; }
.write-row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-top: 14px; }
.hint { color: #94a3b8; font-size: 12px; }
@media (max-width: 767px) {
  .page { padding: 14px; }
  .page-header { flex-direction: column; }
  .kind-select, .account-input { width: 100%; }
}
</style>
