<!--
  文件：PendingReports.vue
  用途：审核端 - 待审核报告页面
  功能：
    1. 展示企业提交的碳排放报告列表（待审核 / 全部报告两种视图）
    2. 通过审核弹窗对报告进行数据核验、查看凭证、填写审核意见
    3. 审核通过后数据上链存证，驳回则打回企业重报
-->
<template>
  <div class="page-container">
    <!-- 页面头部：标题、说明文字与快速跳转按钮 -->
    <div class="page-header flex-between">
      <div>
        <h2>待审核报告</h2>
        <p>对企业提交的碳排放报告进行数据核验、查看数据凭证并填写审核意见</p>
      </div>
      <!-- 角标显示待审核数量，点击切换到待审核标签页 -->
      <el-badge :value="pendingList.length" class="badge">
        <el-button type="primary" @click="activeTab = 'pending'">查看待审核</el-button>
      </el-badge>
    </div>

    <!-- 报告列表面板：通过 Tab 切换 "待审核" 与 "全部报告" -->
    <div class="panel">
      <el-tabs v-model="activeTab">
        <!-- 待审核标签页：仅展示 status=pending 的报告（手机端卡片列表，桌面端表格） -->
        <el-tab-pane label="待审核" name="pending">
          <div v-if="isMobile" v-loading="loading" class="m-list" style="padding: 4px 12px 12px">
            <el-empty v-if="!pendingList.length" description="暂无待审核报告" :image-size="70" />
            <div v-for="row in pendingList" :key="row.id" class="m-card">
              <div class="m-card-head">
                <span class="m-card-title">{{ row.id }}</span>
                <el-tag type="warning" size="small">{{ statusLabel(row.status) }}</el-tag>
              </div>
              <div class="m-row"><span class="m-key">企业</span><span class="m-val">{{ row.enterpriseName }}</span></div>
              <div class="m-row"><span class="m-key">报告日期</span><span class="m-val">{{ row.period }}</span></div>
              <div class="m-row"><span class="m-key">排放源</span><span class="m-val">{{ row.sourceName }}</span></div>
              <div class="m-row"><span class="m-key">活动数据</span><span class="m-val">{{ row.activityData ?? '-' }} {{ row.activityUnit || '' }}</span></div>
              <div class="m-row">
                <span class="m-key">排放因子</span>
                <span class="m-val m-val-inline">
                  <span>{{ row.emissionFactor ?? '-' }}</span>
                  <span class="m-unit">{{ row.unit || '-' }}/单位</span>
                </span>
              </div>
              <div class="m-row"><span class="m-key">排放量</span><span class="m-val text-primary" style="font-weight:600">{{ row.amount }} tCO₂</span></div>
              <div class="m-row"><span class="m-key">提交人</span><span class="m-val">{{ row.submitter }}</span></div>
              <div class="m-row"><span class="m-key">提交时间</span><span class="m-val">{{ row.submittedAt }}</span></div>
              <div class="m-card-actions">
                <el-button type="primary" size="small" @click="openAudit(row)">审核</el-button>
                <el-button size="small" type="info" text @click="viewEvidence(row)">凭证</el-button>
              </div>
            </div>
          </div>

          <el-table v-else :data="pendingList" stripe v-loading="loading">
            <el-table-column prop="id" label="报告编号" min-width="150" align="center" />
            <el-table-column prop="enterpriseName" label="企业" min-width="180" show-overflow-tooltip align="center" />
            <el-table-column prop="period" label="报告日期" min-width="130" align="center" />
            <el-table-column prop="sourceName" label="排放源" min-width="100"  />
            <el-table-column label="排放量" min-width="110" >
              <template #default="{ row }"><span class="text-primary" style="font-weight:600">{{ row.amount }}</span>tCO₂</template>
            </el-table-column>
            <el-table-column prop="submitter" label="提交人" min-width="90" align="center"  />
            <el-table-column prop="submittedAt" label="提交时间" min-width="170" align="center" />
            <!-- 操作列：触发审核弹窗或查看凭证弹窗 -->
            <el-table-column label="操作" min-width="180" fixed="right" align="center">
              <template #default="{ row }">
                <el-button type="primary" size="small" @click="openAudit(row)">审核</el-button>
                <el-button text type="info" size="small" @click="viewEvidence(row)">凭证</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <!-- 全部报告标签页：展示所有状态的报告，支持状态标签展示（手机端卡片列表） -->
        <el-tab-pane label="全部报告" name="all">
          <div v-if="isMobile" v-loading="loading" class="m-list" style="padding: 4px 12px 12px">
            <el-empty v-if="!allList.length" description="暂无报告记录" :image-size="70" />
            <div v-for="row in allList" :key="row.id" class="m-card">
              <div class="m-card-head">
                <span class="m-card-title">{{ row.id }}</span>
                <el-tag :type="tagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
              </div>
              <div class="m-row"><span class="m-key">企业</span><span class="m-val">{{ row.enterpriseName }}</span></div>
              <div class="m-row"><span class="m-key">报告日期</span><span class="m-val">{{ row.period }}</span></div>
              <div class="m-row"><span class="m-key">活动数据</span><span class="m-val">{{ row.activityData ?? '-' }} {{ row.activityUnit || '' }}</span></div>
              <div class="m-row">
                <span class="m-key">排放因子</span>
                <span class="m-val m-val-inline">
                  <span>{{ row.emissionFactor ?? '-' }}</span>
                  <span class="m-unit">{{ row.unit || '-' }}/单位</span>
                </span>
              </div>
              <div class="m-row"><span class="m-key">排放量</span><span class="m-val text-primary" style="font-weight:600">{{ row.amount }} tCO₂</span></div>
              <div class="m-row"><span class="m-key">审核人</span><span class="m-val">{{ row.auditor || '-' }}</span></div>
              <div class="m-card-actions">
                <el-button text type="primary" size="small" @click="viewDetail(row)">详情</el-button>
              </div>
            </div>
          </div>

          <el-table v-else :data="allList" stripe v-loading="loading">
            <el-table-column prop="id" label="报告编号" min-width="120" />
            <el-table-column prop="enterpriseName" label="企业" min-width="180" show-overflow-tooltip />
            <el-table-column prop="period" label="报告日期" min-width="100" />
            <el-table-column label="排放量" min-width="130" align="right">
              <template #default="{ row }">{{ row.amount }}tCO₂</template>
            </el-table-column>
            <el-table-column prop="auditor" label="审核人" min-width="90" />
            <!-- 状态列：根据报告状态展示对应颜色的标签 -->
            <el-table-column label="状态" min-width="90" align="center">
              <template #default="{ row }"><el-tag :type="tagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" min-width="120" fixed="right">
              <template #default="{ row }">
                <el-button text type="primary" size="small" @click="viewDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <!-- 审核弹窗：包含报告详情 + 数据凭证 + 审核意见（手机端内容区可滚动，底部按钮保持可达） -->
    <el-dialog v-model="showAudit" title="报告审核" width="760px" top="5vh">
      <div v-if="current" :class="{ 'dlg-scroll': isMobile }">
        <el-tabs v-model="auditTab">
          <!-- 报告详情：以描述列表展示报告关键字段（编号、报告期、活动数据、排放因子、电子防伪码等） -->
          <el-tab-pane label="报告详情" name="detail">
            <el-descriptions :column="isMobile ? 1 : 2" border>
              <el-descriptions-item label="报告编号">{{ current.id }}</el-descriptions-item>
              <el-descriptions-item label="报告日期">{{ current.period }}</el-descriptions-item>
              <el-descriptions-item label="企业">{{ current.enterpriseName }}</el-descriptions-item>
              <el-descriptions-item label="排放源">{{ current.sourceName }}</el-descriptions-item>
              <el-descriptions-item label="活动数据">{{ current.activityData || '-' }} {{ current.activityUnit || '' }}</el-descriptions-item>
              <el-descriptions-item label="排放因子">{{ current.emissionFactor || '-' }}</el-descriptions-item>
              <el-descriptions-item label="碳排放量" :span="2">
                <span class="text-primary" style="font-size:18px;font-weight:700">{{ current.amount }} tCO₂</span>
              </el-descriptions-item>
              <el-descriptions-item label="提交人">{{ current.submitter }}</el-descriptions-item>
              <el-descriptions-item label="提交时间">{{ current.submittedAt }}</el-descriptions-item>
              <el-descriptions-item label="电子防伪码" :span="2"><span class="hash-text">{{ current.fingerprint }}</span></el-descriptions-item>
            </el-descriptions>
          </el-tab-pane>

          <!-- 数据凭证查看：展示原始活动数据凭证、排放因子取值依据与链上电子防伪码 -->
          <el-tab-pane label="数据凭证" name="evidence">
            <div class="evidence-box">
              <div class="ev-item">
                <div class="ev-label">原始活动数据凭证</div>
                <div class="ev-files">
                  <div class="file-chip"><el-icon><Document /></el-icon> 燃煤消耗台账_202408.xlsx</div>
                  <div class="file-chip"><el-icon><Document /></el-icon> 电表抄表记录_202408.pdf</div>
                </div>
              </div>
              <div class="ev-item">
                <div class="ev-label">排放因子取值依据</div>
                <div class="ev-files">
                  <div class="file-chip"><el-icon><Document /></el-icon> 企业温室气体排放核算方法与报告指南.pdf</div>
                </div>
              </div>
              <div class="ev-item">
                <div class="ev-label">电子防伪码（链上存证）</div>
                <div class="hash-text" style="background:#f8fafc;padding:10px;border-radius:6px">{{ current.fingerprint }}</div>
              </div>
              <!-- 指纹校验通过提示，表明链上存证与本地数据一致 -->
              <el-alert type="success" :closable="false" style="margin-top:16px">
                <template #title>
                  <el-icon><CircleCheck /></el-icon> 电子防伪码校验通过，与链上存证一致
                </template>
              </el-alert>
            </div>
          </el-tab-pane>

          <!-- 审核意见填写：审核结论、三项评分（数据真实性/核算合规性/因子合理性）及审核意见 -->
          <el-tab-pane label="审核意见" name="opinion">
            <el-form :label-width="isMobile ? 'auto' : '100px'">
              <el-form-item label="审核结论">
                <el-radio-group v-model="auditResult">
                  <el-radio value="pass">通过认证</el-radio>
                  <el-radio value="reject">驳回</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="数据真实性">
                <el-rate v-model="scores.reality" :max="5" show-text />
              </el-form-item>
              <el-form-item label="核算合规性">
                <el-rate v-model="scores.compliance" :max="5" show-text />
              </el-form-item>
              <el-form-item label="因子合理性">
                <el-rate v-model="scores.factor" :max="5" show-text />
              </el-form-item>
              <el-form-item label="审核意见">
                <el-input v-model="opinion" type="textarea" :rows="5" placeholder="请填写详细审核意见..." />
              </el-form-item>
            </el-form>
          </el-tab-pane>
        </el-tabs>
      </div>
      <!-- 弹窗底部操作按钮：取消、通过并上链、驳回 -->
      <template #footer>
        <el-button @click="showAudit = false">取消</el-button>
        <el-button type="success" @click="submitAudit('pass')" :loading="submitting">通过并上链</el-button>
        <el-button type="danger" @click="submitAudit('reject')" :loading="submitting">驳回</el-button>
      </template>
    </el-dialog>

    <!-- 数据凭证查看弹窗（列表页"凭证"按钮触发的简化版查看，手机端可滚动） -->
    <el-dialog v-model="showEvidence" title="数据凭证查看" width="560px">
      <div v-if="current" :class="{ 'dlg-scroll': isMobile }">
        <div class="ev-item">
          <div class="ev-label">电子防伪码（链上存证）</div>
          <div class="hash-text" style="background:#f8fafc;padding:10px;border-radius:6px">{{ current.fingerprint }}</div>
        </div>
        <div class="ev-item" style="margin-top:16px">
          <div class="ev-label">上传凭证文件</div>
          <div class="ev-files">
            <div class="file-chip"><el-icon><Document /></el-icon> 原始数据凭证.pdf</div>
          </div>
        </div>
      </div>
    </el-dialog>

    <!-- 报告详情抽屉：在"全部报告"标签页点击"详情"时打开 -->
    <el-drawer v-model="showDetail" title="报告详情" size="500px">
      <el-descriptions :column="1" border v-if="current">
        <el-descriptions-item label="报告编号">{{ current.id }}</el-descriptions-item>
        <el-descriptions-item label="企业">{{ current.enterpriseName }}</el-descriptions-item>
        <el-descriptions-item label="报告日期">{{ current.period }}</el-descriptions-item>
        <el-descriptions-item label="排放量">{{ current.amount }} tCO₂</el-descriptions-item>
        <el-descriptions-item label="审核人">{{ current.auditor || '-' }}</el-descriptions-item>
        <el-descriptions-item label="审核意见">{{ current.auditOpinion || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// 引入 Vue 组合式 API（ref 响应式基本类型、reactive 响应式对象、computed 计算属性）
import { ref, reactive, computed, onMounted } from 'vue'
// 引入 Element Plus 消息提示组件，用于审核操作反馈
import { ElMessage } from 'element-plus'
// 引入报告记录接口
import { getReportRecords, auditReport } from '@/api/report'
// 引入设备检测：手机端渲染卡片列表与可滚动弹窗
import { useDevice } from '@/composables/useDevice'

// 设备类型（手机端切换为卡片列表）
const { isMobile } = useDevice()

// 报告记录原始列表
const records = ref([])
// 列表加载态
const loading = ref(false)
// 提交审核按钮加载态
const submitting = ref(false)
// 当前激活的 Tab 名称：pending=待审核，all=全部报告
const activeTab = ref('pending')
// 待审核列表：过滤出 status=pending 的报告
const pendingList = computed(() => records.value.filter(r => r.status === 'pending'))
// 全部报告列表：包含所有状态的报告记录
const allList = computed(() => records.value)

// 弹窗显隐控制：审核弹窗、凭证弹窗、详情抽屉
const showAudit = ref(false)
const showEvidence = ref(false)
const showDetail = ref(false)
// 审核弹窗内部 Tab 切换：detail=报告详情，evidence=数据凭证，opinion=审核意见
const auditTab = ref('detail')
// 当前操作的报告行数据
const current = ref(null)
// 审核结论：pass=通过认证，reject=驳回
const auditResult = ref('pass')
// 审核意见文本
const opinion = ref('')
// 三项评分：数据真实性、核算合规性、因子合理性（默认 4 分）
const scores = reactive({ reality: 4, compliance: 4, factor: 4 })

// 拉取全部报告记录
async function load() {
  loading.value = true
  try {
    records.value = await getReportRecords({})
  } finally {
    loading.value = false
  }
}

// 打开审核弹窗：设置当前报告、重置 Tab 与表单状态
function openAudit(row) {
  current.value = row
  auditTab.value = 'detail'
  auditResult.value = 'pass'
  opinion.value = ''
  showAudit.value = true
}
// 打开凭证查看弹窗
function viewEvidence(row) { current.value = row; showEvidence.value = true }
// 打开详情抽屉（"全部报告"标签页使用）
function viewDetail(row) { current.value = row; showDetail.value = true }

// 提交审核：校验意见后调用后端审核接口，成功后重新拉取列表
async function submitAudit(action) {
  // 审核意见为空时提示并跳转到意见 Tab
  if (!opinion.value.trim()) {
    ElMessage.warning('请填写审核意见')
    auditTab.value = 'opinion'
    return
  }
  submitting.value = true
  try {
    await auditReport(current.value.id, {
      result: action === 'pass' ? 'certified' : 'rejected',
      opinion: opinion.value
    })
    ElMessage.success(action === 'pass' ? '审核通过，数据已上链存证' : '已驳回该报告')
    showAudit.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

// 状态文案映射：用于表格中状态标签的文字显示
function statusLabel(s) { return { certified: '已认证', pending: '待审核', rejected: '已驳回' }[s] }
// 状态标签类型映射：用于表格中状态标签的颜色（success/warning/danger）
function tagType(s) { return { certified: 'success', pending: 'warning', rejected: 'danger' }[s] }

// 初始化加载
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>

<style scoped>
/* 顶部角标外边距 */
.badge { margin-right: 10px; }
/* 凭证项容器：每项之间留出间距 */
.ev-item { margin-bottom: 16px; }
/* 凭证项标题样式 */
.ev-label { font-size: 13px; color: #475569; margin-bottom: 8px; font-weight: 500; }
/* 凭证文件列表：横向排列、自动换行、间距 8px */
.ev-files { display: flex; flex-wrap: wrap; gap: 8px; }
/* 单个凭证文件标签：圆角背景、图标与文字并排 */
.file-chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 6px 12px; background: #f1f5f9; border-radius: 6px;
  font-size: 13px; color: #475569;
}
/* 手机端弹窗内容区：限制最大高度并允许滚动，保证底部按钮始终可达 */
.dlg-scroll {
  max-height: 58vh;
  overflow-y: auto;
  padding-right: 4px;
}
</style>
