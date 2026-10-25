<!--
  CertificationApply.vue - 认证申请页（企业端）
  用途：对已提交的碳排放报告发起区块链认证申请，审核通过后生成不可篡改的认证凭证；含申请流程说明与申请弹窗
  路由：/enterprise/apply（name: CertificationApply，仅 enterprise 角色可访问）
  依赖：报告记录接口（列表）与申请接口；申请后状态由 pending 起重新进入审核，通过后生成认证证书
-->
<template>
  <div class="page-container">
    <!-- 页面标题区 -->
    <div class="page-header">
      <h2>认证申请</h2>
      <p>对已提交的碳排放报告发起区块链认证申请，审核通过后将生成不可篡改的认证凭证</p>
    </div>

    <!-- 可申请认证报告列表：展示未认证报告并提供申请入口
         包含审核中（pending）与已驳回（rejected）两种状态；已驳回报告可在此再次申请，
         后端会把状态改回 pending 并清空原审核信息 -->
    <div class="panel mb-16">
      <div class="panel-header"><span class="title">可申请认证的报告</span></div>
      <div class="panel-body" style="padding: 0">
        <!-- 可申请报告：手机端用卡片列表，桌面端用表格 -->
        <div v-if="isMobile" v-loading="loading" class="m-list" style="padding: 12px">
          <el-empty v-if="!applicable.length" description="暂无可申请认证的报告，请先在「碳排放数据填报」中提交数据" :image-size="70" />
          <div v-for="row in applicable" :key="row.id" class="m-card">
            <div class="m-card-head">
              <span class="m-card-title">{{ row.id }}</span>
            </div>
            <div class="m-row"><span class="m-key">报告日期</span><span class="m-val">{{ row.period }}</span></div>
            <div class="m-row"><span class="m-key">排放源</span><span class="m-val">{{ row.sourceName }}</span></div>
            <div class="m-row"><span class="m-key">排放量</span><span class="m-val text-primary" style="font-weight:600">{{ row.amount }} tCO₂</span></div>
            <div class="m-row"><span class="m-key">电子防伪码</span><span class="m-val hash-text">{{ row.fingerprint }}</span></div>
            <div class="m-row"><span class="m-key">提交时间</span><span class="m-val">{{ row.submittedAt }}</span></div>
            <div class="m-card-actions">
              <el-button type="primary" size="small" @click="apply(row)" :disabled="row.applied">
                {{ row.applied ? '已申请' : '申请认证' }}
              </el-button>
            </div>
          </div>
        </div>

        <el-table v-else :data="applicable" stripe v-loading="loading">
          <el-table-column prop="id" label="报告编号" min-width="100" align="center" />
          <el-table-column prop="period" label="报告日期" min-width="90" align="center" />
          <el-table-column prop="sourceName" label="排放源" min-width="90"  />
          <el-table-column label="排放量" min-width="80" >
            <template #default="{ row }"><span class="text-primary" style="font-weight:600">{{ row.amount }}</span> tCO₂</template>
          </el-table-column>
          <el-table-column label="电子防伪码" min-width="100" align="center">
            <template #default="{ row }"><span class="hash-text">{{ row.fingerprint }}</span></template>
          </el-table-column>
          <el-table-column prop="submittedAt" label="提交时间" min-width="150" align="center" />
          <el-table-column label="操作" min-width="140" fixed="right" align="center">
            <template #default="{ row }">
              <el-button type="primary" size="small" @click="apply(row)" :disabled="row.applied">
                {{ row.applied ? '已申请' : '申请认证' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!isMobile && !applicable.length" description="暂无可申请认证的报告，请先在「碳排放数据填报」中提交数据" />
      </div>
    </div>

    <!-- 认证申请流程说明：四步流程（提交申请→机构审核→上链存证→生成证书）
         该流程为静态示意，前两步固定高亮，不反映具体某条报告的真实进度 -->
    <div class="panel">
      <div class="panel-header"><span class="title">认证申请流程</span></div>
      <div class="panel-body">
        <div class="flow-steps">
          <div class="flow-step" v-for="(s, i) in steps" :key="i" :class="{ active: i <= 1 }">
            <div class="step-num">{{ i + 1 }}</div>
            <div class="step-text">
              <div class="st-title">{{ s.title }}</div>
              <div class="st-desc">{{ s.desc }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 提交认证申请对话框：展示报告信息并填写备注后确认申请
         备注仅用于界面说明，当前申请接口只按报告编号发起，不接收备注内容 -->
    <el-dialog v-model="showApply" title="提交认证申请" width="520px">
      <div v-if="current">
        <el-alert type="info" :closable="false" style="margin-bottom: 16px">
          申请提交后，审核机构将对该报告进行数据核验，通过后电子防伪码将上链存证并生成认证证书。
        </el-alert>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="报告编号">{{ current.id }}</el-descriptions-item>
          <el-descriptions-item label="报告日期">{{ current.period }}</el-descriptions-item>
          <el-descriptions-item label="排放量">{{ current.amount }} tCO₂</el-descriptions-item>
          <el-descriptions-item label="电子防伪码"><span class="hash-text">{{ current.fingerprint }}</span></el-descriptions-item>
        </el-descriptions>
        <el-form label-width="100px" style="margin-top: 16px">
          <el-form-item label="备注说明">
            <el-input v-model="remark" type="textarea" :rows="2" placeholder="可补充说明核算方法、数据来源等（选填）" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="showApply = false">取消</el-button>
        <el-button type="primary" @click="confirmApply" :loading="submitting">确认申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 引入页面数据自动刷新组合式函数（定时用接口重新拉取，不重新挂载组件）
import { useAutoRefresh } from '@/composables/useAutoRefresh'
// 引入 Vue 响应式 API：ref 基本值，computed 计算属性
import { ref, reactive, computed, onMounted } from 'vue'
// 引入 Element Plus 消息提示组件
import { ElMessage } from 'element-plus'
// 引入用户 store，获取当前登录企业
import { useUserStore } from '@/store'
// 引入报告记录接口
import { getReportRecords, applyReport } from '@/api/report'
// 引入设备检测：手机端渲染卡片列表，桌面端渲染表格
import { useDevice } from '@/composables/useDevice'

// 用户 store 实例
const userStore = useUserStore()
// 报告记录原始列表
const records = ref([])
// 已发起申请的报告编号（用于按钮置灰，纯 UI 状态，不落库；
// 刷新后按钮是否置灰不再由该集合决定，而以接口返回的报告状态为准）
const appliedIds = reactive(new Set())
// 列表加载态
const loading = ref(false)
// 设备类型（手机端切换为卡片列表）
const { isMobile } = useDevice()
// 可申请认证的报告：当前企业且状态不是已认证（certified）的记录，
// 即审核中（pending）与已驳回（rejected）均可发起或重新发起申请
const applicable = computed(() => records.value
  .filter(r => r.status !== 'certified')
  .map(r => ({ ...r, applied: appliedIds.has(r.id) }))
)

// 认证申请流程步骤说明数据
const steps = [
  { title: '提交申请', desc: '企业选择报告发起认证申请' },
  { title: '机构审核', desc: '审核机构核验数据真实性与因子合规性' },
  { title: '上链存证', desc: '审核通过后电子防伪码写入区块链' },
  { title: '生成证书', desc: '生成可验证的碳排放认证证书' }
]

// 申请弹窗显隐控制、当前选中报告、备注内容
const showApply = ref(false)
const current = ref(null)
const remark = ref('')
// 申请提交按钮加载态
const submitting = ref(false)

// 拉取当前企业报告记录
async function load() {
  loading.value = true
  try {
    records.value = await getReportRecords({ enterpriseId: userStore.user?.enterpriseId })
  } finally {
    loading.value = false
  }
}

// 打开申请弹窗：记录当前报告行并清空备注
function apply(row) {
  current.value = row
  remark.value = ''
  showApply.value = true
}

// 确认申请：调用申请接口重新发起认证（后端将报告状态改回 pending 并清空审核信息），
// 成功后本地记入 appliedIds 置灰按钮、关闭弹窗并刷新列表；
// 已驳回报告的重试与首次申请走同一接口，不区分状态
async function confirmApply() {
  if (!current.value) return
  submitting.value = true
  try {
    await applyReport(current.value.id)
    appliedIds.add(current.value.id)
    ElMessage.success('认证申请已提交，等待审核机构审核')
    showApply.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

// 初始化加载列表
onMounted(load)
// 定时自动刷新：通过接口重新拉取数据，不重置页面状态
useAutoRefresh(load)
</script>

<style scoped>
/* ===== 桌面端（>767px）基础样式；≤767px 时报告列表改为卡片（isMobile），
   流程步骤仍沿用本段横向布局，页面留白等通用适配由 mobile.css 处理 ===== */
/* 流程步骤容器：横向均分布局 */
.flow-steps {
  display: flex;
  justify-content: space-between;
}
/* 单个流程步骤项 */
.flow-step {
  flex: 1;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px;
  border-radius: 10px;
  background: #f8fafc;
  margin: 0 6px;
  transition: all .2s;
}
/* 激活态步骤项背景色 */
.flow-step.active {
  background: #ecfdf5;
}
/* 步骤序号圆形标识 */
.step-num {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #e2e8f0;
  color: #64748b;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  flex-shrink: 0;
}
/* 激活态步骤序号配色 */
.flow-step.active .step-num {
  background: #10b981;
  color: #fff;
}
/* 步骤标题与描述文字样式 */
.st-title { font-size: 14px; font-weight: 600; color: #0f172a; margin-bottom: 4px; }
.st-desc { font-size: 12px; color: #64748b; line-height: 1.5; }
</style>
