<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  adminApproveFingerprint,
  adminArchiveFingerprint,
  adminGetFingerprint,
  adminListFingerprintByPage,
  adminRejectFingerprint,
} from '../../api/admin'
import CButton from '../../components/ui/CButton.vue'
import CDescriptions, { type DescItem } from '../../components/ui/CDescriptions.vue'
import CIcon from '../../components/ui/CIcon.vue'
import CInput from '../../components/ui/CInput.vue'
import CModal from '../../components/ui/CModal.vue'
import CPagination from '../../components/ui/CPagination.vue'
import CSelect from '../../components/ui/CSelect.vue'
import CSpin from '../../components/ui/CSpin.vue'
import CTable, { type TableColumn } from '../../components/ui/CTable.vue'
import CTag from '../../components/ui/CTag.vue'
import CTextarea from '../../components/ui/CTextarea.vue'
import { confirm } from '../../components/ui/confirm'
import { message } from '../../components/ui/toast'
import type { DesignFingerprint, FingerprintQueryRequest } from '../../types'

/* ── 列表状态 ── */
const loading = ref(false)
const tableData = ref<DesignFingerprint[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(15)

const searchForm = reactive<FingerprintQueryRequest>({
  styleProfile: '',
  status: 'CANDIDATE',
  sourceType: '',
  tone: '',
})

/* ── 详情 / 备注弹窗 ── */
const detailOpen = ref(false)
const detailRecord = ref<DesignFingerprint | null>(null)
const detailLoading = ref(false)

const remarkOpen = ref(false)
const remarkText = ref('')
const remarkAction = ref<'reject' | 'archive'>('reject')
const remarkTargetId = ref('')
const remarkLoading = ref(false)

/* ── 选项 ── */
const statusTabs = [
  { label: '全部', value: '' },
  { label: '待审核', value: 'CANDIDATE' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已拒绝', value: 'REJECTED' },
  { label: '已归档', value: 'ARCHIVED' },
]

const sourceTypeOptions = [
  { label: '全部来源', value: '' },
  { label: '精选页面提炼', value: 'EXTRACTED_FINGERPRINT' },
  { label: '自产反哺', value: 'SELF_FED_FINGERPRINT' },
]

const styleProfileOptions = [
  { label: '全部风格', value: '' },
  { label: 'startup', value: 'startup' },
  { label: 'enterprise', value: 'enterprise' },
  { label: 'default', value: 'default' },
]

const columns: TableColumn[] = [
  { key: 'styleProfile', title: '风格画像', width: '120px' },
  { key: 'sourceType', title: '来源类型', width: '140px' },
  { key: 'confidence', title: '置信度', width: '110px' },
  { key: 'tone', title: '调性', width: '170px', ellipsis: true },
  { key: 'status', title: '状态', width: '110px' },
  { key: 'reviewRemark', title: '审核备注', minWidth: '160px', ellipsis: true },
  { key: 'createTime', title: '入库时间', width: '170px' },
  { key: 'action', title: '操作', width: '132px', align: 'right' },
]

const STATUS_LABELS: Record<string, string> = {
  CANDIDATE: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
  ARCHIVED: '已归档',
}

const STATUS_TONES: Record<string, 'warning' | 'success' | 'danger' | 'neutral'> = {
  CANDIDATE: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  ARCHIVED: 'neutral',
}

function statusLabel(status: string) {
  return STATUS_LABELS[status] ?? status
}

function sourceLabel(type: string) {
  return type === 'SELF_FED_FINGERPRINT' ? '自产反哺' : '精选提炼'
}

function confidenceTone(c?: number) {
  if (c == null) return 'muted'
  if (c >= 0.8) return 'good'
  if (c >= 0.7) return 'mid'
  return 'low'
}

function confidenceText(c?: number, digits = 0) {
  return c != null ? `${(c * 100).toFixed(digits)}%` : '—'
}

function formatJson(json?: string) {
  if (!json) return ''
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

const detailItems = computed<DescItem[]>(() => {
  const r = detailRecord.value
  if (!r) return []
  return [
    { key: 'id', label: 'ID', value: r.id, span: 2, mono: true },
    { key: 'styleProfile', label: '风格画像', value: r.styleProfile },
    { key: 'schemaVersion', label: 'schema 版本', value: r.schemaVersion },
    { key: 'sourceType', label: '来源类型', value: sourceLabel(r.sourceType) },
    { key: 'sourceId', label: '来源标识', value: r.sourceId, mono: true },
    { key: 'status', label: '治理状态' },
    { key: 'confidence', label: '置信度' },
    { key: 'priority', label: '优先级', value: r.priority },
    { key: 'tone', label: '调性关键词', value: r.tone },
    { key: 'reviewRemark', label: '审核备注', value: r.reviewRemark || '无', span: 2 },
    { key: 'createTime', label: '入库时间', value: r.createTime },
    { key: 'updateTime', label: '更新时间', value: r.updateTime },
  ]
})

/* ── 数据 ── */
async function loadData() {
  loading.value = true
  try {
    const res = await adminListFingerprintByPage({
      styleProfile: searchForm.styleProfile || undefined,
      status: searchForm.status || undefined,
      sourceType: searchForm.sourceType || undefined,
      tone: searchForm.tone || undefined,
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

function handleSearch() {
  current.value = 1
  loadData()
}

function switchStatus(value: string) {
  searchForm.status = value
  handleSearch()
}

async function openDetail(row: DesignFingerprint) {
  detailOpen.value = true
  detailLoading.value = true
  detailRecord.value = null
  try {
    const res = await adminGetFingerprint(row.id)
    if (res?.code === 0) detailRecord.value = res.data
  } finally {
    detailLoading.value = false
  }
}

async function handleApprove(row: DesignFingerprint) {
  const ok = await confirm({
    title: '审核通过这条指纹？',
    content: [
      `指纹 ID：${row.id}`,
      `置信度：${confidenceText(row.confidence)}`,
      '通过后该指纹将参与运行时召回。',
    ],
    okText: '审核通过',
    icon: 'check-circle',
  })
  if (!ok) return
  const res = await adminApproveFingerprint({ id: row.id }).catch(() => null)
  if (res?.code === 0) {
    message.success('审核通过，指纹已加入 APPROVED 池')
    loadData()
  }
}

function openRemarkDialog(row: DesignFingerprint, action: 'reject' | 'archive') {
  remarkAction.value = action
  remarkTargetId.value = row.id
  remarkText.value = ''
  remarkOpen.value = true
}

/** 详情弹窗内的快捷操作 */
function approveFromDetail() {
  const row = detailRecord.value
  detailOpen.value = false
  if (row) handleApprove(row)
}

function remarkFromDetail(action: 'reject' | 'archive') {
  const row = detailRecord.value
  detailOpen.value = false
  if (row) openRemarkDialog(row, action)
}

function goPage(page: number) {
  current.value = page
  loadData()
}

async function handleRemarkConfirm() {
  if (!remarkTargetId.value) return
  remarkLoading.value = true
  try {
    const params = { id: remarkTargetId.value, remark: remarkText.value }
    const res =
      remarkAction.value === 'reject'
        ? await adminRejectFingerprint(params)
        : await adminArchiveFingerprint(params)
    if (res?.code === 0) {
      message.success(remarkAction.value === 'reject' ? '已拒绝' : '已归档')
      remarkOpen.value = false
      loadData()
    }
  } finally {
    remarkLoading.value = false
  }
}

onMounted(loadData)
</script>

<template>
  <div class="admin-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">Fingerprints</p>
        <h1 class="page-title">指纹审核</h1>
        <p class="page-subtitle">
          共 {{ total }} 条指纹<template v-if="searchForm.status === 'CANDIDATE'">，置信度 ≥ 70% 方可通过</template>。
        </p>
      </div>
    </header>

    <!-- 状态切换 -->
    <div class="tabs">
      <button
        v-for="tab in statusTabs"
        :key="tab.value"
        class="tab"
        :class="{ 'is-active': searchForm.status === tab.value }"
        type="button"
        @click="switchStatus(tab.value)"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- 筛选 -->
    <div class="filters">
      <CSelect
        v-model="searchForm.styleProfile"
        size="sm"
        :options="styleProfileOptions"
        width="150px"
        @change="handleSearch"
      />
      <CSelect
        v-model="searchForm.sourceType"
        size="sm"
        :options="sourceTypeOptions"
        width="170px"
        @change="handleSearch"
      />
      <CInput
        v-model="searchForm.tone"
        size="sm"
        icon="search"
        placeholder="调性关键词"
        clearable
        @enter="handleSearch"
      />
      <CButton variant="primary" size="sm" icon="search" @click="handleSearch">搜索</CButton>
    </div>

    <CTable
      :columns="columns"
      :data="tableData"
      :loading="loading"
      row-key="id"
      min-width="1120px"
      compact
      empty-text="没有符合条件的指纹"
    >
      <template #cell:styleProfile="{ record }">
        <span class="mono">{{ record.styleProfile }}</span>
      </template>

      <template #cell:sourceType="{ record }">
        <CTag :tone="record.sourceType === 'SELF_FED_FINGERPRINT' ? 'violet' : 'info'">
          {{ sourceLabel(record.sourceType) }}
        </CTag>
      </template>

      <template #cell:confidence="{ record }">
        <span class="conf" :class="`is-${confidenceTone(record.confidence)}`">
          {{ confidenceText(record.confidence) }}
        </span>
      </template>

      <template #cell:status="{ record }">
        <CTag :tone="STATUS_TONES[record.status] || 'neutral'" dot>
          {{ statusLabel(record.status) }}
        </CTag>
      </template>

      <template #cell:reviewRemark="{ record }">
        <span class="text-muted">{{ record.reviewRemark || '—' }}</span>
      </template>

      <template #cell:createTime="{ record }">
        <span class="text-muted">{{ record.createTime || '—' }}</span>
      </template>

      <template #cell:action="{ record }">
        <div class="cell-acts">
          <CButton
            variant="ghost"
            size="xs"
            icon="eye"
            title="查看详情"
            aria-label="查看详情"
            @click="openDetail(record)"
          />
          <template v-if="record.status === 'CANDIDATE'">
            <CButton
              variant="ghost"
              size="xs"
              icon="check"
              title="审核通过"
              aria-label="审核通过"
              @click="handleApprove(record)"
            />
            <CButton
              variant="ghost"
              size="xs"
              icon="x"
              danger
              title="拒绝"
              aria-label="拒绝"
              @click="openRemarkDialog(record, 'reject')"
            />
          </template>
          <CButton
            v-else-if="record.status === 'APPROVED'"
            variant="ghost"
            size="xs"
            icon="inbox"
            title="归档"
            aria-label="归档"
            @click="openRemarkDialog(record, 'archive')"
          />
        </div>
      </template>
    </CTable>

    <CPagination
      v-if="total > 0"
      :current="current"
      :page-size="pageSize"
      :total="total"
      @change="goPage"
    />

    <!-- 详情 -->
    <CModal
      v-model:open="detailOpen"
      title="指纹详情"
      icon="fingerprint"
      width="740px"
      hide-footer
    >
      <CSpin :spinning="detailLoading">
        <template v-if="detailRecord">
          <CDescriptions :items="detailItems" :columns="2">
            <template #status>
              <CTag :tone="STATUS_TONES[detailRecord.status] || 'neutral'" dot>
                {{ statusLabel(detailRecord.status) }}
              </CTag>
            </template>
            <template #confidence>
              <span class="conf" :class="`is-${confidenceTone(detailRecord.confidence)}`">
                {{ confidenceText(detailRecord.confidence, 1) }}
              </span>
            </template>
          </CDescriptions>

          <div class="json">
            <div class="json__head">
              <CIcon name="code" :size="14" />
              <span>完整六层指纹 JSON</span>
            </div>
            <pre class="json__body">{{ formatJson(detailRecord.fingerprintJson) }}</pre>
          </div>

          <div v-if="detailRecord.status === 'CANDIDATE'" class="detail-acts">
            <CButton variant="danger" icon="x" @click="remarkFromDetail('reject')">拒绝</CButton>
            <CButton variant="primary" icon="check" @click="approveFromDetail">审核通过</CButton>
          </div>
          <div v-else-if="detailRecord.status === 'APPROVED'" class="detail-acts">
            <CButton variant="secondary" icon="inbox" @click="remarkFromDetail('archive')">
              归档
            </CButton>
          </div>
        </template>
      </CSpin>
    </CModal>

    <!-- 拒绝 / 归档 -->
    <CModal
      v-model:open="remarkOpen"
      :title="remarkAction === 'reject' ? '拒绝指纹' : '归档指纹'"
      :description="
        remarkAction === 'reject'
          ? '拒绝后该指纹不参与召回，相关向量将被失效。'
          : '归档后该指纹不参与召回，相关向量将被失效。'
      "
      :icon="remarkAction === 'reject' ? 'x-circle' : 'inbox'"
      :danger="remarkAction === 'reject'"
      :ok-text="remarkAction === 'reject' ? '确认拒绝' : '确认归档'"
      :ok-loading="remarkLoading"
      width="480px"
      @ok="handleRemarkConfirm"
    >
      <CTextarea
        v-model="remarkText"
        :rows="3"
        :maxlength="200"
        placeholder="请填写备注原因（可选）"
      />
    </CModal>
  </div>
</template>

<style scoped>
.admin-page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

/* ── 状态切换 ── */
.tabs {
  display: flex;
  gap: 3px;
  padding: 4px;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-pill);
  box-shadow: var(--shadow-xs);
  width: fit-content;
  max-width: 100%;
  overflow-x: auto;
}

.tab {
  height: 28px;
  padding: 0 14px;
  border: none;
  border-radius: var(--r-pill);
  background: transparent;
  font-size: var(--fs-xs);
  font-weight: 500;
  color: var(--text-muted);
  white-space: nowrap;
  cursor: pointer;
  transition: all var(--t-fast) var(--ease-out);
}

.tab:hover:not(.is-active) {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.tab.is-active {
  background: var(--accent);
  color: var(--accent-fg);
}

/* ── 筛选 ── */
.filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.filters :deep(.c-input) { width: 170px; }

/* ── 置信度 ── */
.conf {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.conf.is-good { color: var(--success); }
.conf.is-mid { color: var(--warning); }
.conf.is-low { color: var(--danger); }
.conf.is-muted { color: var(--text-faint); }

.cell-acts {
  display: flex;
  justify-content: flex-end;
  gap: 2px;
}

/* ── JSON ── */
.json { margin-top: var(--sp-5); }

.json__head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--text-secondary);
}

.json__body {
  margin: 0;
  padding: 14px 16px;
  max-height: 340px;
  overflow: auto;
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-md);
  background: var(--n-50);
  font-family: var(--font-mono);
  font-size: var(--fs-2xs);
  line-height: 1.75;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

.detail-acts {
  display: flex;
  justify-content: flex-end;
  gap: 9px;
  margin-top: var(--sp-5);
}

@media (max-width: 620px) {
  .filters :deep(.c-input) { width: 100%; }
}
</style>
