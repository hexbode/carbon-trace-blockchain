<!--
  EnterpriseEmissionView.vue —— 企业碳排放信息页（公共查询端，无需登录）
  用途：社会公众按企业名称搜索纳入监管的企业，查看其累计排放、认证记录与待审核情况，
  并可打开抽屉查看企业详情及认证记录明细。
  路由：/public/enterprise（PublicLayout 子路由）。
-->
<template>
  <div class="public-page">
    <!-- 页面标题区 -->
    <div class="hero">
      <h1>企业碳排放信息</h1>
      <p>公开查询纳入监管的企业碳排放数据与认证情况</p>
    </div>

    <!-- 搜索区：按企业名称关键字搜索 -->
    <div class="search-box panel">
      <el-input v-model="keyword" size="large" placeholder="输入企业名称搜索" clearable>
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
    </div>

    <!-- 企业卡片列表：展示企业基本信息和排放/认证统计 -->
    <div class="enterprise-list" v-loading="loading">
      <div
        class="ent-card panel"
        v-for="ent in filtered"
        :key="ent.id"
        @click="selectEnterprise(ent)"
      >
        <div class="ent-header">
          <div class="ent-icon"><el-icon :size="24"><OfficeBuilding /></el-icon></div>
          <div class="ent-info">
            <h3>{{ ent.name }}</h3>
            <p>{{ ent.industry }} · {{ ent.scope }}</p>
          </div>
          <el-tag type="success" size="small">已认证 {{ ent.certCount }} 条</el-tag>
        </div>
        <div class="ent-stats">
          <div class="stat">
            <div class="val">{{ ent.total }}<span>tCO₂</span></div>
            <div class="lbl">累计排放</div>
          </div>
          <div class="stat">
            <div class="val">{{ ent.certCount }}<span>条</span></div>
            <div class="lbl">认证记录</div>
          </div>
          <div class="stat">
            <div class="val">{{ ent.pending }}<span>条</span></div>
            <div class="lbl">待审核</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 搜索无结果时的空状态 -->
    <el-empty v-if="!filtered.length" description="未找到匹配的企业" />

    <!-- 企业碳排放详情抽屉 -->
    <el-drawer v-model="showDetail" title="企业碳排放详情" size="600px">
      <!-- 详情内容：手机端限制高度并允许滚动，保证企业信息与认证记录可完整查看 -->
      <div v-if="detail" :style="isMobile ? { maxHeight: '76vh', overflowY: 'auto', paddingRight: '4px' } : null">
        <div class="detail-header">
          <h2>{{ detail.name }}</h2>
          <p>{{ detail.industry }} · {{ detail.scope }}</p>
        </div>
        <!-- 企业基本信息：手机端单列展示，避免字段被挤压 -->
        <el-descriptions :column="isMobile ? 1 : 2" border>
          <el-descriptions-item label="统一社会信用代码">{{ detail.creditCode }}</el-descriptions-item>
          <el-descriptions-item label="联系人">{{ detail.contact }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">{{ detail.phone }}</el-descriptions-item>
          <el-descriptions-item label="注册时间">{{ detail.registeredAt }}</el-descriptions-item>
          <el-descriptions-item label="注册地址" :span="2">{{ detail.address }}</el-descriptions-item>
        </el-descriptions>

        <!-- 排放统计：累计排放 / 已认证 / 待审核 -->
        <h3 class="section-title">排放统计</h3>
        <el-row :gutter="16">
          <el-col :span="8"><div class="mini-stat"><div class="v">{{ detail.total }}</div><div class="l">累计排放 (tCO₂)</div></div></el-col>
          <el-col :span="8"><div class="mini-stat green"><div class="v">{{ detail.certCount }}</div><div class="l">已认证 (条)</div></div></el-col>
          <el-col :span="8"><div class="mini-stat orange"><div class="v">{{ detail.pending }}</div><div class="l">待审核 (条)</div></div></el-col>
        </el-row>

        <!-- 认证记录明细：手机端用卡片列表，桌面端用表格 -->
        <h3 class="section-title">认证记录</h3>
        <div v-if="isMobile" class="m-list">
          <el-empty v-if="!detail.certList.length" description="暂无认证记录" :image-size="60" />
          <div v-for="row in detail.certList" :key="row.id" class="m-card">
            <div class="m-card-head">
              <span class="m-card-title">{{ row.id }}</span>
              <el-tag :type="row.status === 'revoked' ? 'danger' : 'success'" size="small">
                {{ row.status === 'revoked' ? '已撤销' : '有效' }}
              </el-tag>
            </div>
            <div class="m-row"><span class="m-key">报告日期</span><span class="m-val">{{ row.period }}</span></div>
            <div class="m-row"><span class="m-key">排放量</span><span class="m-val text-primary" style="font-weight: 600">{{ row.amount }} tCO₂</span></div>
          </div>
        </div>
        <!-- 桌面端：表格展示认证记录，排放量右对齐便于横向比较 -->
        <el-table v-else :data="detail.certList" size="small">
          <el-table-column prop="id" label="认证编号" min-width="120" />
          <el-table-column prop="period" label="报告日期" min-width="100" />
          <el-table-column label="排放量" min-width="110" align="right">
            <template #default="{ row }">{{ row.amount }} tCO₂</template>
          </el-table-column>
          <el-table-column label="状态" min-width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.status === 'revoked' ? 'danger' : 'success'" size="small">
                {{ row.status === 'revoked' ? '已撤销' : '有效' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
// ===== 依赖引入 =====
import { ref, computed, onMounted } from 'vue'
// 公共企业 / 排放概览接口；认证明细接口（登录态下可用）
import { getPublicEnterprises, getPublicEnterpriseEmissions } from '@/api/publicApi'
import { getCertifications } from '@/api/certification'
// 引入设备检测：手机端认证记录改用卡片列表
import { useDevice } from '@/composables/useDevice'

// 设备类型（手机端切换列表与详情展示方式）
const { isMobile } = useDevice()

// ===== 状态 =====
const keyword = ref('')       // 搜索关键字
const showDetail = ref(false) // 详情抽屉显示状态
const detail = ref(null)      // 当前查看的企业详情
const enterprises = ref([])   // 企业基础信息
const emissions = ref([])     // 企业排放概览
const loading = ref(false)    // 加载态

// 企业列表基础统计：合并企业基础信息与排放概览
const list = computed(() =>
  enterprises.value.map(e => {
    const em = emissions.value.find(x => x.enterpriseId === e.id) || {}
    return {
      id: e.id,
      name: e.name,
      industry: e.industry,
      scope: e.scope,
      // 累计排放取整展示，避免小数位让卡片数字过长
      total: Math.round(Number(em.totalEmission || 0)),
      certCount: em.certCount || 0,
      // 待审核数量由公共接口的 pendingCount 提供
      pending: em.pendingCount || 0
    }
  })
)

// 按关键字过滤企业列表
const filtered = computed(() =>
  list.value.filter(e => !keyword.value || e.name.includes(keyword.value))
)

// 选中企业：加载该企业认证记录明细（公共端无该接口，登录态下查询）
async function selectEnterprise(ent) {
  const entBase = enterprises.value.find(e => e.id === ent.id) || {}
  let certList = []
  // 认证明细接口需要登录态：公共端未登录时跳过请求，直接展示基础信息
  if (localStorage.getItem('carbon_token')) {
    try {
      certList = await getCertifications({ enterpriseId: ent.id })
    } catch (e) {
      certList = []
    }
  }
  detail.value = { ...ent, ...entBase, certList }
  showDetail.value = true
}

// 初始化加载企业列表与排放概览
onMounted(async () => {
  loading.value = true
  try {
    // 企业基础信息与排放概览互不依赖，并行请求减少等待
    const [ents, ems] = await Promise.all([getPublicEnterprises(), getPublicEnterpriseEmissions()])
    enterprises.value = ents || []
    emissions.value = ems || []
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
/* 页面容器：限制最大宽度并居中 */
.public-page { max-width: 1000px; margin: 0 auto; }
/* 标题区 */
.hero { text-align: center; margin-bottom: 28px; }
.hero h1 { font-size: 28px; color: #0f172a; margin: 0 0 8px; }
.hero p { color: #64748b; margin: 0; }
/* 搜索区 */
.search-box { padding: 20px; margin-bottom: 20px; }
/* 企业卡片网格布局 */
.enterprise-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px; }
/* 单张企业卡片：可点击进入详情，悬停轻微上浮 */
.ent-card { padding: 20px; cursor: pointer; transition: all .2s; }
.ent-card:hover { transform: translateY(-3px); box-shadow: 0 8px 20px rgba(0,0,0,.08); }
/* 卡片头部：企业图标 + 名称/行业 + 认证数量标签 */
.ent-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.ent-icon { width: 44px; height: 44px; border-radius: 10px; background: #ecfdf5; color: #10b981; display: flex; align-items: center; justify-content: center; }
/* 企业文案占满剩余宽度，过长的名称在内部截断 */
.ent-info { flex: 1; min-width: 0; }
.ent-info h3 { margin: 0; font-size: 16px; color: #0f172a; }
.ent-info p { margin: 2px 0 0; font-size: 12px; color: #94a3b8; }
/* 卡片内统计区 */
.ent-stats { display: flex; justify-content: space-around; padding-top: 16px; border-top: 1px dashed #e2e8f0; }
.stat { text-align: center; }
.stat .val { font-size: 20px; font-weight: 700; color: #0f172a; }
.stat .val span { font-size: 11px; color: #94a3b8; font-weight: 400; margin-left: 2px; }
.stat .lbl { font-size: 11px; color: #94a3b8; margin-top: 2px; }

/* 详情抽屉样式 */
.detail-header h2 { margin: 0 0 4px; }
.detail-header p { margin: 0; color: #64748b; font-size: 13px; }
.section-title { font-size: 15px; font-weight: 600; margin: 20px 0 12px; color: #0f172a; }
.mini-stat { text-align: center; padding: 16px; background: #f8fafc; border-radius: 10px; }
.mini-stat .v { font-size: 24px; font-weight: 700; color: #0f172a; }
.mini-stat.green .v { color: #10b981; }
.mini-stat.orange .v { color: #f59e0b; }
.mini-stat .l { font-size: 12px; color: #94a3b8; margin-top: 4px; }

/* 移动端断点 ≤767px：卡片改为单列，长文本允许换行 */
@media (max-width: 767px) {
  /* 标题与留白收紧，避免超大标题在窄屏溢出 */
  .hero { margin-bottom: 18px; }
  .hero h1 { font-size: 22px; }
  .hero p { font-size: 13px; line-height: 1.6; }
  .search-box { padding: 14px; }
  /* 企业卡片：手机端单列，避免 320px 最小列宽撑出横向滚动 */
  .enterprise-list { grid-template-columns: 1fr; }
  .ent-card { padding: 16px; }
  .ent-header { flex-wrap: wrap; }
  /* 详情标题长文本换行 */
  .detail-header h2 { font-size: 18px; word-break: break-all; }
  .detail-header p { word-break: break-all; }
}
</style>
