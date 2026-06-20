<script setup lang="ts">
import { ref, reactive, onMounted, h } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  SearchOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  InboxOutlined,
  EyeOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons-vue'
import {
  adminListFingerprintByPage,
  adminGetFingerprint,
  adminApproveFingerprint,
  adminRejectFingerprint,
  adminArchiveFingerprint,
} from '../../api/admin'
import type { DesignFingerprint, FingerprintQueryRequest } from '../../types'

// ======== 列表状态 ========
const loading = ref(false)
const tableData = ref<DesignFingerprint[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(15)

// ======== 搜索条件 ========
const searchForm = reactive<FingerprintQueryRequest>({
  styleProfile: undefined,
  status: 'CANDIDATE',  // 默认显示待审核
  sourceType: undefined,
  tone: '',
})

// ======== 详情/操作弹窗 ========
const detailVisible = ref(false)
const detailRecord = ref<DesignFingerprint | null>(null)
const detailLoading = ref(false)

const remarkVisible = ref(false)
const remarkText = ref('')
const remarkAction = ref<'reject' | 'archive'>('reject')
const remarkTargetId = ref<string>('')
const remarkLoading = ref(false)

// ======== 状态选项 ========
const statusOptions = [
  { label: '全部', value: undefined },
  { label: '待审核', value: 'CANDIDATE' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已拒绝', value: 'REJECTED' },
  { label: '已归档', value: 'ARCHIVED' },
]

const sourceTypeOptions = [
  { label: '全部来源', value: undefined },
  { label: '精选页面提炼', value: 'EXTRACTED_FINGERPRINT' },
  { label: '自产反哺', value: 'SELF_FED_FINGERPRINT' },
]

const styleProfileOptions = [
  { label: '全部风格', value: undefined },
  { label: 'startup', value: 'startup' },
  { label: 'enterprise', value: 'enterprise' },
  { label: 'default', value: 'default' },
]

// ======== 表格列定义 ========
const columns = [
  { title: 'ID', dataIndex: 'id', width: 80, ellipsis: true },
  { title: '风格画像', dataIndex: 'styleProfile', width: 110 },
  { title: '来源类型', key: 'sourceType', width: 130 },
  { title: '置信度', dataIndex: 'confidence', width: 90 },
  { title: '调性', dataIndex: 'tone', width: 150, ellipsis: true },
  { title: '状态', dataIndex: 'status', width: 100 },
  { title: '审核备注', dataIndex: 'reviewRemark', ellipsis: true },
  { title: '入库时间', dataIndex: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 200, fixed: 'right' },
]

// ======== 数据加载 ========
async function loadData() {
  loading.value = true
  try {
    const res = await adminListFingerprintByPage({
      ...searchForm,
      pageNum: current.value,
      pageSize: pageSize.value,
    })
    if (res?.code === 0) {
      tableData.value = res.data.records
      total.value = res.data.totalRow
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadData)

function handleSearch() {
  current.value = 1
  loadData()
}

function handleTableChange(pagination: any) {
  current.value = pagination.current
  pageSize.value = pagination.pageSize
  loadData()
}

// ======== 查看详情 ========
async function openDetail(row: DesignFingerprint) {
  detailVisible.value = true
  detailLoading.value = true
  try {
    const res = await adminGetFingerprint(row.id)
    if (res?.code === 0) {
      detailRecord.value = res.data
    }
  } finally {
    detailLoading.value = false
  }
}

// ======== 审核通过 ========
function handleApprove(row: DesignFingerprint) {
  Modal.confirm({
    title: '确认审核通过？',
    icon: h(ExclamationCircleOutlined, { style: { color: '#52c41a' } }),
    content: `指纹 ID: ${row.id}，置信度: ${((row.confidence ?? 0) * 100).toFixed(0)}%。通过后将参与运行时召回。`,
    okText: '通过',
    cancelText: '取消',
    onOk: async () => {
      const res = await adminApproveFingerprint({ id: row.id })
      if (res?.code === 0) {
        message.success('审核通过，指纹已加入 APPROVED 池')
        loadData()
      }
    },
  })
}

// ======== 拒绝 ========
function openRejectDialog(row: DesignFingerprint) {
  remarkAction.value = 'reject'
  remarkTargetId.value = row.id
  remarkText.value = ''
  remarkVisible.value = true
}

// ======== 归档 ========
function openArchiveDialog(row: DesignFingerprint) {
  remarkAction.value = 'archive'
  remarkTargetId.value = row.id
  remarkText.value = ''
  remarkVisible.value = true
}

// ======== 执行拒绝/归档 ========
async function handleRemarkConfirm() {
  if (!remarkTargetId.value) return
  remarkLoading.value = true
  try {
    const params = { id: remarkTargetId.value, remark: remarkText.value }
    let res
    if (remarkAction.value === 'reject') {
      res = await adminRejectFingerprint(params)
    } else {
      res = await adminArchiveFingerprint(params)
    }
    if (res?.code === 0) {
      message.success(remarkAction.value === 'reject' ? '已拒绝' : '已归档')
      remarkVisible.value = false
      loadData()
    }
  } finally {
    remarkLoading.value = false
  }
}

// ======== 样式辅助 ========
function statusColor(status: string) {
  const map: Record<string, string> = {
    CANDIDATE: 'orange',
    APPROVED: 'green',
    REJECTED: 'red',
    ARCHIVED: 'default',
  }
  return map[status] ?? 'default'
}

function statusLabel(status: string) {
  const map: Record<string, string> = {
    CANDIDATE: '待审核',
    APPROVED: '已通过',
    REJECTED: '已拒绝',
    ARCHIVED: '已归档',
  }
  return map[status] ?? status
}

function sourceLabel(type: string) {
  return type === 'SELF_FED_FINGERPRINT' ? '自产反哺' : '精选提炼'
}

function confidenceColor(c?: number) {
  if (c == null) return 'var(--gray-400)'
  if (c >= 0.8) return '#52c41a'
  if (c >= 0.7) return '#faad14'
  return '#ff4d4f'
}

// 格式化 fingerprintJson 为可读形式
function formatJson(json?: string) {
  if (!json) return ''
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}
</script>

<template>
  <div class="fingerprint-manage">
    <!-- 搜索栏 -->
    <div class="toolbar">
      <div class="search-group">
        <!-- 状态快速切换 tabs -->
        <div class="status-tabs">
          <span
            v-for="opt in statusOptions"
            :key="String(opt.value)"
            class="status-tab"
            :class="{ active: searchForm.status === opt.value }"
            @click="searchForm.status = opt.value; handleSearch()"
          >
            {{ opt.label }}
          </span>
        </div>
      </div>
      <div class="filter-group">
        <a-select
          v-model:value="searchForm.styleProfile"
          :options="styleProfileOptions"
          placeholder="风格筛选"
          allow-clear
          style="width: 130px;"
        />
        <a-select
          v-model:value="searchForm.sourceType"
          :options="sourceTypeOptions"
          placeholder="来源筛选"
          allow-clear
          style="width: 140px;"
        />
        <a-input
          v-model:value="searchForm.tone"
          placeholder="调性关键词"
          allow-clear
          style="width: 140px;"
          @press-enter="handleSearch"
        />
        <a-button type="primary" :icon="h(SearchOutlined)" @click="handleSearch">搜索</a-button>
      </div>
    </div>

    <!-- 统计提示 -->
    <div class="stat-bar">
      <span class="stat-item">
        共 <strong>{{ total }}</strong> 条指纹
        <template v-if="searchForm.status === 'CANDIDATE'">
          — 以下为待审核列表，置信度 ≥ 0.7 方可通过
        </template>
      </span>
    </div>

    <!-- 表格 -->
    <div class="table-wrap">
      <a-table
        :columns="columns"
        :data-source="tableData"
        :loading="loading"
        row-key="id"
        :scroll="{ x: 1100 }"
        :pagination="{
          current,
          pageSize,
          total,
          showSizeChanger: true,
          pageSizeOptions: ['10', '15', '30'],
          showTotal: (t: number) => `共 ${t} 条`,
        }"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <!-- 来源类型 -->
          <template v-if="column.key === 'sourceType'">
            <a-tag
              :color="record.sourceType === 'SELF_FED_FINGERPRINT' ? 'purple' : 'cyan'"
              style="border-radius:4px;"
            >
              {{ sourceLabel(record.sourceType) }}
            </a-tag>
          </template>

          <!-- 置信度 -->
          <template v-else-if="column.dataIndex === 'confidence'">
            <span :style="{ color: confidenceColor(record.confidence), fontWeight: 600 }">
              {{ record.confidence != null ? (record.confidence * 100).toFixed(0) + '%' : '-' }}
            </span>
          </template>

          <!-- 状态 -->
          <template v-else-if="column.dataIndex === 'status'">
            <a-tag :color="statusColor(record.status)" style="border-radius:4px;">
              {{ statusLabel(record.status) }}
            </a-tag>
          </template>

          <!-- 操作 -->
          <template v-else-if="column.key === 'action'">
            <a-space size="small">
              <a-button type="text" size="small" :icon="h(EyeOutlined)" @click="openDetail(record)">
                详情
              </a-button>
              <template v-if="record.status === 'CANDIDATE'">
                <a-button
                  type="text" size="small"
                  :icon="h(CheckCircleOutlined)"
                  style="color: #52c41a;"
                  @click="handleApprove(record)"
                >
                  通过
                </a-button>
                <a-button
                  type="text" size="small" danger
                  :icon="h(CloseCircleOutlined)"
                  @click="openRejectDialog(record)"
                >
                  拒绝
                </a-button>
              </template>
              <template v-else-if="record.status === 'APPROVED'">
                <a-button
                  type="text" size="small"
                  :icon="h(InboxOutlined)"
                  style="color: var(--gray-500);"
                  @click="openArchiveDialog(record)"
                >
                  归档
                </a-button>
              </template>
            </a-space>
          </template>
        </template>
      </a-table>
    </div>

    <!-- 详情弹窗 -->
    <a-modal
      v-model:open="detailVisible"
      title="指纹详情"
      :footer="null"
      width="720px"
      :body-style="{ maxHeight: '70vh', overflowY: 'auto' }"
    >
      <a-spin :spinning="detailLoading">
        <template v-if="detailRecord">
          <a-descriptions :column="2" size="small" bordered style="margin-bottom: 16px;">
            <a-descriptions-item label="ID" :span="2">{{ detailRecord.id }}</a-descriptions-item>
            <a-descriptions-item label="风格画像">{{ detailRecord.styleProfile }}</a-descriptions-item>
            <a-descriptions-item label="schema 版本">{{ detailRecord.schemaVersion }}</a-descriptions-item>
            <a-descriptions-item label="来源类型">{{ sourceLabel(detailRecord.sourceType) }}</a-descriptions-item>
            <a-descriptions-item label="来源标识">{{ detailRecord.sourceId || '-' }}</a-descriptions-item>
            <a-descriptions-item label="治理状态">
              <a-tag :color="statusColor(detailRecord.status)">{{ statusLabel(detailRecord.status) }}</a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="置信度">
              <span :style="{ color: confidenceColor(detailRecord.confidence), fontWeight: 600 }">
                {{ detailRecord.confidence != null ? (detailRecord.confidence * 100).toFixed(1) + '%' : '-' }}
              </span>
            </a-descriptions-item>
            <a-descriptions-item label="优先级">{{ detailRecord.priority ?? '-' }}</a-descriptions-item>
            <a-descriptions-item label="调性关键词">{{ detailRecord.tone || '-' }}</a-descriptions-item>
            <a-descriptions-item label="审核备注" :span="2">{{ detailRecord.reviewRemark || '无' }}</a-descriptions-item>
            <a-descriptions-item label="入库时间">{{ detailRecord.createTime }}</a-descriptions-item>
            <a-descriptions-item label="更新时间">{{ detailRecord.updateTime }}</a-descriptions-item>
          </a-descriptions>

          <div class="json-section">
            <div class="json-label">完整六层指纹 JSON</div>
            <pre class="json-body">{{ formatJson(detailRecord.fingerprintJson) }}</pre>
          </div>

          <!-- 详情内的快捷操作 -->
          <div v-if="detailRecord.status === 'CANDIDATE'" class="detail-actions">
            <a-button
              type="primary"
              :icon="h(CheckCircleOutlined)"
              @click="detailVisible = false; handleApprove(detailRecord!)"
            >
              审核通过
            </a-button>
            <a-button
              danger
              :icon="h(CloseCircleOutlined)"
              @click="detailVisible = false; openRejectDialog(detailRecord!)"
            >
              拒绝
            </a-button>
          </div>
          <div v-else-if="detailRecord.status === 'APPROVED'" class="detail-actions">
            <a-button
              :icon="h(InboxOutlined)"
              @click="detailVisible = false; openArchiveDialog(detailRecord!)"
            >
              归档
            </a-button>
          </div>
        </template>
      </a-spin>
    </a-modal>

    <!-- 拒绝/归档备注弹窗 -->
    <a-modal
      v-model:open="remarkVisible"
      :title="remarkAction === 'reject' ? '拒绝指纹' : '归档指纹'"
      :confirm-loading="remarkLoading"
      :ok-button-props="{ danger: remarkAction === 'reject' }"
      :ok-text="remarkAction === 'reject' ? '确认拒绝' : '确认归档'"
      cancel-text="取消"
      @ok="handleRemarkConfirm"
    >
      <div style="margin-bottom: 8px; color: var(--gray-600); font-size: 13px;">
        {{ remarkAction === 'reject'
          ? '拒绝后该指纹不参与召回，相关向量将被失效。'
          : '归档后该指纹不参与召回，相关向量将被失效。' }}
      </div>
      <a-textarea
        v-model:value="remarkText"
        placeholder="请填写备注原因（可选）"
        :rows="3"
        :maxlength="200"
        show-count
      />
    </a-modal>
  </div>
</template>

<style scoped>
.fingerprint-manage {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 工具栏 */
.toolbar {
  background: var(--geist-white);
  border-radius: var(--r-card);
  box-shadow: var(--shadow-border);
  padding: 14px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

/* 状态 Tabs */
.status-tabs {
  display: flex;
  gap: 4px;
}

.status-tab {
  padding: 5px 12px;
  border-radius: var(--r-pill);
  font-size: 13px;
  font-weight: 500;
  color: var(--gray-500);
  cursor: pointer;
  transition: background var(--duration-fast) ease, color var(--duration-fast) ease;
  user-select: none;
  border: 1px solid transparent;
}
.status-tab:hover {
  background: var(--gray-50);
  color: var(--geist-black);
}
.status-tab.active {
  background: var(--geist-black);
  color: var(--geist-white);
}

.filter-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

/* 统计条 */
.stat-bar {
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: var(--r-base);
  padding: 8px 16px;
  font-size: 13px;
  color: #7c5d00;
}
.stat-item strong { color: var(--geist-black); }

/* 表格 */
.table-wrap {
  background: var(--geist-white);
  border-radius: var(--r-card);
  box-shadow: var(--shadow-border);
  overflow: hidden;
}

/* 详情 JSON */
.json-section {
  margin-top: 4px;
}

.json-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-600);
  margin-bottom: 8px;
}

.json-body {
  background: #1e1e1e;
  color: #d4d4d4;
  border-radius: var(--r-base);
  padding: 14px 16px;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 320px;
  overflow: auto;
  margin: 0;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

/* 详情内操作按钮 */
.detail-actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
  justify-content: flex-end;
}
</style>
