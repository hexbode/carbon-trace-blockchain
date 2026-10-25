<!--
  Alerts.vue —— 异常告警页
  用途：展示系统自动监测出的碳排放数据异常告警（高危/中危/低危），
  提供告警列表的状态筛选、详情查看与标记处理功能。
  路由：/regulator/alerts（name: Alerts，roles: regulator / admin）
  依赖接口：@/api/alert 的 getAlerts（支持 level/status 参数，本页取全量后在前端筛选）、
           resolveAlert（PUT /alerts/{id}/resolve，把 pending 置为 resolved）
  字段口径：level = high / medium / low；status = pending / resolved；
           告警由链上合规判定产生，type 取 data_anomaly（数据异常）/ factor_mismatch（因子不匹配）/
           late_report（迟报）/ chain_failed（链上同步失败），typeLabel 为后端返回的中文类型名，
           与 message 一起构成本次判定结果的文案快照（本页不再单独存储快照字段）。
-->
<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <h2>异常告警</h2>
      <p>系统自动监测碳排放数据异常，及时发现数据错报、漏报与波动异常</p>
    </div>

    <!-- 顶部统计卡片：高危 / 中危 / 低危 / 待处理（点击卡片筛选下方列表） -->
    <!-- 前三张卡片控制级别筛选 levelFilter，“待处理”卡片控制状态筛选 statusFilter，两个维度可叠加 -->
    <el-row :gutter="16" class="mb-16">
      <el-col :span="6" v-for="s in stats" :key="s.label">
        <div
          class="stat-card clickable"
          :class="[s.cls, { active: isActive(s) }]"
          @click="toggleFilter(s)"
        >
          <div class="label"><el-icon><component :is="s.icon" /></el-icon> {{ s.label }}</div>
          <div class="value">{{ s.value }}</div>
        </div>
      </el-col>
    </el-row>

    <!-- 告警列表面板：含状态筛选与表格 -->
    <!-- 状态单选与“待处理”统计卡片绑定同一个 statusFilter，二者选中态保持同步 -->
    <div class="panel">
      <div class="panel-header">
        <span class="title">告警列表</span>
        <el-radio-group v-model="statusFilter" size="small">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="pending">待处理</el-radio-button>
          <el-radio-button value="resolved">已处理</el-radio-button>
        </el-radio-group>
      </div>
      <div class="panel-body" style="padding: 0">
        <!-- 手机端卡片列表 -->
        <div v-if="isMobile" v-loading="loading" class="m-list" style="padding: 12px">
          <el-empty v-if="!filteredAlerts.length" description="暂无数据" :image-size="70" />
          <div v-for="row in filteredAlerts" :key="row.id" class="m-card">
            <div class="m-card-head">
              <span class="m-card-title">{{ row.enterpriseName }}</span>
              <el-tag :type="levelType(row.level)" size="small" effect="dark">{{ levelLabel(row.level) }}</el-tag>
            </div>
            <div class="m-card-sub">{{ row.typeLabel }}</div>
            <div class="m-row"><span class="m-key">报告日期</span><span class="m-val">{{ row.period || '-' }}</span></div>
            <div class="m-row"><span class="m-key">告警内容</span><span class="m-val">{{ row.message }}</span></div>
            <div class="m-row"><span class="m-key">触发时间</span><span class="m-val">{{ row.createdAt || '-' }}</span></div>
            <div class="m-row">
              <span class="m-key">状态</span>
              <span class="m-val">
                <el-tag :type="row.status === 'resolved' ? 'success' : 'warning'" size="small">
                  {{ row.status === 'resolved' ? '已处理' : '待处理' }}
                </el-tag>
              </span>
            </div>
            <div class="m-card-actions">
              <el-button text type="primary" size="small" @click="viewAlert(row)">详情</el-button>
              <el-button v-if="row.status === 'pending'" text type="success" size="small" @click="resolve(row)">处理</el-button>
            </div>
          </div>
        </div>

        <!-- 告警表格：级别/类型/企业/报告期/内容/时间/状态/操作 -->
        <!-- 桌面端表格（>767px）：级别用标签着色，仅 pending 状态显示“处理”按钮 -->
        <el-table v-else :data="filteredAlerts" stripe v-loading="loading">
          <el-table-column label="级别" min-width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="levelType(row.level)" size="small" effect="dark">{{ levelLabel(row.level) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="typeLabel" label="告警类型" min-width="110" align="center" />
          <el-table-column prop="enterpriseName" label="企业" min-width="180" show-overflow-tooltip align="center" />
          <el-table-column prop="period" label="报告日期" min-width="100" align="center" />
          <el-table-column prop="message" label="告警内容" min-width="320" show-overflow-tooltip align="center" />
          <el-table-column prop="createdAt" label="触发时间" min-width="170" align="center" />
          <el-table-column label="状态" min-width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'resolved' ? 'success' : 'warning'" size="small">
                {{ row.status === 'resolved' ? '已处理' : '待处理' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="140" fixed="right" align="center">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="viewAlert(row)">详情</el-button>
              <el-button v-if="row.status === 'pending'" text type="success" size="small" @click="resolve(row)">处理</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <!-- 告警详情对话框：头部级别标识 + 描述列表 + 处理建议 -->
    <!-- 详情直接复用列表行数据（current），不再单独请求接口；仅 pending 告警提供“标记已处理” -->
    <el-dialog v-model="showDetail" title="告警详情" width="520px">
      <!-- 内容区：手机端限制高度并允许滚动，保证详情可完整查看 -->
      <div v-if="current" :style="isMobile ? { maxHeight: '60vh', overflowY: 'auto' } : null">
        <div class="alert-detail-header" :class="current.level">
          <el-icon :size="32"><WarningFilled /></el-icon>
          <div>
            <div class="adh-title">{{ levelLabel(current.level) }}告警 · {{ current.typeLabel }}</div>
            <div class="adh-time">{{ current.createdAt }}</div>
          </div>
        </div>
        <el-descriptions :column="1" border style="margin-top: 16px">
          <el-descriptions-item label="告警企业">{{ current.enterpriseName }}</el-descriptions-item>
          <el-descriptions-item label="报告日期">{{ current.period }}</el-descriptions-item>
          <el-descriptions-item label="告警内容">{{ current.message }}</el-descriptions-item>
        </el-descriptions>
        <div class="suggestion">
          <div class="sg-title"><el-icon><Opportunity /></el-icon> 处理建议</div>
          <ul>
            <li>联系企业核实 {{ current.period }} 期排放数据填报情况</li>
            <li>核对活动数据原始凭证与排放因子取值依据</li>
            <li>如数据无误，在审计模块中标记异常解除；否则要求企业重新填报</li>
          </ul>
        </div>
      </div>
      <template #footer>
        <!-- 关闭仅收起弹窗；“标记已处理”同时完成处理并关闭，避免重复进入详情 -->
        <el-button @click="showDetail = false">关闭</el-button>
        <el-button v-if="current?.status === 'pending'" type="primary" @click="resolve(current); showDetail = false">标记已处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// 导入 Vue 响应式 API
import { ref, computed, onMounted } from 'vue'
// 导入 Element Plus 消息提示
import { ElMessage } from 'element-plus'
// 导入告警接口
import { getAlerts, resolveAlert } from '@/api/alert'
// 引入设备检测：手机端渲染卡片列表，桌面端渲染表格
import { useDevice } from '@/composables/useDevice'

// 设备类型（手机端切换为卡片列表）
const { isMobile } = useDevice()

// 告警列表（接口一次返回全量，级别/状态筛选均在前端完成）
// 行字段：level、status、type、typeLabel、message、period、enterpriseName、createdAt
const alerts = ref([])
// 列表加载态
const loading = ref(false)
// 级别筛选值：'' | 'high' | 'medium' | 'low'
const levelFilter = ref('')
// 告警状态筛选值：'' | 'pending' | 'resolved'（空字符串表示全部，与下方 radio-group 联动）
const statusFilter = ref('')

// 按级别与状态筛选后的告警列表（两个维度叠加生效）
// 统计卡片设置的 levelFilter / statusFilter 与列表共用同一份数据，因此计数始终为全量口径
const filteredAlerts = computed(() =>
  alerts.value.filter(a =>
    (!levelFilter.value || a.level === levelFilter.value) &&
    (!statusFilter.value || a.status === statusFilter.value)
  )
)

// 高危告警总数（按级别统计，不再限定待处理）
const highCount = computed(() => alerts.value.filter(a => a.level === 'high').length)
// 中危告警总数
const mediumCount = computed(() => alerts.value.filter(a => a.level === 'medium').length)
// 低危告警总数
const lowCount = computed(() => alerts.value.filter(a => a.level === 'low').length)
// 待处理告警总数（按状态统计）
const pendingCount = computed(() => alerts.value.filter(a => a.status === 'pending').length)

// 顶部统计卡片数据：dim 表示筛选维度（level 按级别 / status 按状态），val 为对应筛选值
const stats = computed(() => [
  { label: '高危告警', value: highCount.value, icon: 'WarningFilled', cls: 'danger', dim: 'level', val: 'high' },
  { label: '中危告警', value: mediumCount.value, icon: 'Bell', cls: 'warning', dim: 'level', val: 'medium' },
  { label: '低危告警', value: lowCount.value, icon: 'InfoFilled', cls: 'info', dim: 'level', val: 'low' },
  { label: '待处理', value: pendingCount.value, icon: 'Clock', cls: 'pending', dim: 'status', val: 'pending' }
])

// 判断某张卡片当前是否处于选中（筛选）态
function isActive(s) {
  return s.dim === 'level' ? levelFilter.value === s.val : statusFilter.value === s.val
}

// 点击统计卡片：按对应维度/值切换筛选；再次点击同一卡片则取消筛选
function toggleFilter(s) {
  if (s.dim === 'level') {
    levelFilter.value = levelFilter.value === s.val ? '' : s.val
  } else {
    statusFilter.value = statusFilter.value === s.val ? '' : s.val
  }
}

// 告警级别中文标签
function levelLabel(l) { return { high: '高危', medium: '中危', low: '低危' }[l] }
// 告警级别对应的标签颜色类型
function levelType(l) { return { high: 'danger', medium: 'warning', low: 'info' }[l] }

// 拉取告警列表
async function load() {
  loading.value = true
  try {
    alerts.value = await getAlerts()
  } finally {
    loading.value = false
  }
}

// 标记告警为已处理（调用后端接口后刷新列表）
// 后端把 status 由 pending 改为 resolved；处理后该行不再显示“处理”按钮
async function resolve(row) {
  await resolveAlert(row.id)
  ElMessage.success('告警已标记为处理')
  await load()
}

// 详情对话框显示状态与当前查看的告警
const showDetail = ref(false)
const current = ref(null)
// 打开告警详情对话框
function viewAlert(row) { current.value = row; showDetail.value = true }

// 初始化加载
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>

<style scoped>
/* ===== 桌面端（>767px）：基础样式，本页无 scoped 断点规则 ===== */
/* ===== 移动端（≤767px）：由 isMobile 切换卡片列表与弹窗滚动高度，卡片外观来自全局 mobile.css ===== */
/* 统计卡片标签颜色：按告警级别/状态区分 */
.stat-card.danger .label { color: #ef4444; }
.stat-card.warning .label { color: #f59e0b; }
.stat-card.info .label { color: #3b82f6; }
.stat-card.pending .label { color: #8b5cf6; }
/* 可点击的统计卡片：手型光标提示可交互 */
.stat-card.clickable { cursor: pointer; }
/* 统计卡片选中态：主题色边框 + 浅绿背景，标识当前筛选维度 */
.stat-card.active {
  border-color: var(--primary);
  background: #f0fdf9;
}

/* 详情对话框头部：按告警级别着色 */
.alert-detail-header {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 20px;
  border-radius: 12px;
}
.alert-detail-header.high { background: #fef2f2; color: #dc2626; }
.alert-detail-header.medium { background: #fffbeb; color: #d97706; }
.alert-detail-header.low { background: #eff6ff; color: #2563eb; }
.adh-title { font-size: 16px; font-weight: 600; }
.adh-time { font-size: 12px; opacity: 0.7; margin-top: 2px; }

/* 处理建议区 */
.suggestion {
  margin-top: 16px;
  padding: 16px;
  background: #f8fafc;
  border-radius: 8px;
}
.sg-title {
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.suggestion ul {
  margin: 0;
  padding-left: 20px;
  font-size: 13px;
  color: #475569;
  line-height: 1.8;
}
</style>
