<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { adminListChatHistory } from '../../api/admin'
import CButton from '../../components/ui/CButton.vue'
import CDescriptions, { type DescItem } from '../../components/ui/CDescriptions.vue'
import CInput from '../../components/ui/CInput.vue'
import CModal from '../../components/ui/CModal.vue'
import CPagination from '../../components/ui/CPagination.vue'
import CSelect from '../../components/ui/CSelect.vue'
import CTable, { type TableColumn } from '../../components/ui/CTable.vue'
import CTag from '../../components/ui/CTag.vue'
import { message } from '../../components/ui/toast'
import type { ChatHistory, ChatHistoryQueryRequest } from '../../types'

/* ── 列表状态 ── */
const loading = ref(false)
const tableData = ref<ChatHistory[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(15)

const searchForm = reactive<ChatHistoryQueryRequest>({
  appId: '',
  userId: '',
  messageType: '',
})

/* ── 详情弹窗 ── */
const previewOpen = ref(false)
const previewRecord = ref<ChatHistory | null>(null)

const typeOptions = [
  { value: '', label: '全部类型' },
  { value: 'user', label: '用户消息' },
  { value: 'ai', label: 'AI 回复' },
]

const columns: TableColumn[] = [
  { key: 'messageType', title: '类型', width: '110px' },
  { key: 'message', title: '消息内容', minWidth: '320px' },
  { key: 'appId', title: '应用 ID', width: '150px', mono: true, ellipsis: true },
  { key: 'userId', title: '用户 ID', width: '150px', mono: true, ellipsis: true },
  { key: 'createTime', title: '时间', width: '170px' },
  { key: 'action', title: '操作', width: '90px', align: 'right' },
]

const previewItems = computed<DescItem[]>(() => {
  const r = previewRecord.value
  if (!r) return []
  return [
    { key: 'id', label: '消息 ID', value: r.id, mono: true },
    { key: 'type', label: '类型' },
    { key: 'appId', label: '应用 ID', value: r.appId, mono: true },
    { key: 'userId', label: '用户 ID', value: r.userId, mono: true },
    { key: 'time', label: '时间', value: r.createTime, span: 2 },
  ]
})

async function loadData() {
  loading.value = true
  try {
    const params: ChatHistoryQueryRequest = {
      pageNum: current.value,
      pageSize: pageSize.value,
    }
    if (searchForm.appId) params.appId = searchForm.appId
    if (searchForm.userId) params.userId = searchForm.userId
    if (searchForm.messageType) params.messageType = searchForm.messageType

    const res = await adminListChatHistory(params)
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

function goPage(page: number) {
  current.value = page
  loadData()
}

function resetSearch() {
  searchForm.appId = ''
  searchForm.userId = ''
  searchForm.messageType = undefined
  handleSearch()
}

function openPreview(row: ChatHistory) {
  previewRecord.value = row
  previewOpen.value = true
}

function typeLabel(type: string) {
  return type === 'ai' ? 'AI 回复' : '用户消息'
}

async function copyMessage(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制到剪贴板')
  } catch {
    message.error('复制失败，请手动选中复制')
  }
}

onMounted(loadData)
</script>

<template>
  <div class="admin-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">Conversations</p>
        <h1 class="page-title">对话历史</h1>
        <p class="page-subtitle">共 {{ total.toLocaleString() }} 条消息记录。</p>
      </div>
    </header>

    <div class="filters">
      <CInput
        v-model="searchForm.appId"
        size="sm"
        icon="grid"
        placeholder="按应用 ID 过滤"
        clearable
        @enter="handleSearch"
      />
      <CInput
        v-model="searchForm.userId"
        size="sm"
        icon="user"
        placeholder="按用户 ID 过滤"
        clearable
        @enter="handleSearch"
      />
      <CSelect
        v-model="searchForm.messageType"
        size="sm"
        :options="typeOptions"
        placeholder="消息类型"
        width="140px"
        @change="handleSearch"
      />
      <CButton variant="primary" size="sm" icon="search" @click="handleSearch">搜索</CButton>
      <CButton variant="ghost" size="sm" icon="refresh" @click="resetSearch">重置</CButton>
    </div>

    <CTable
      :columns="columns"
      :data="tableData"
      :loading="loading"
      row-key="id"
      min-width="1080px"
      compact
      empty-text="没有符合条件的记录"
    >
      <template #cell:messageType="{ record }">
        <CTag
          :tone="record.messageType === 'ai' ? 'violet' : 'info'"
          :icon="record.messageType === 'ai' ? 'bot' : 'user'"
        >
          {{ record.messageType === 'ai' ? 'AI' : '用户' }}
        </CTag>
      </template>

      <template #cell:message="{ record }">
        <p class="cell-msg clamp-2">{{ record.message }}</p>
      </template>

      <template #cell:createTime="{ record }">
        <span class="text-muted">{{ record.createTime || '—' }}</span>
      </template>

      <template #cell:action="{ record }">
        <CButton variant="ghost" size="xs" icon="eye" @click="openPreview(record)">查看</CButton>
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
      v-model:open="previewOpen"
      title="消息详情"
      icon="message-square"
      width="640px"
      hide-footer
    >
      <template v-if="previewRecord">
        <CDescriptions :items="previewItems" :columns="2">
          <template #type>
            <CTag
              :tone="previewRecord.messageType === 'ai' ? 'violet' : 'info'"
              :icon="previewRecord.messageType === 'ai' ? 'bot' : 'user'"
            >
              {{ typeLabel(previewRecord.messageType) }}
            </CTag>
          </template>
        </CDescriptions>

        <div class="content">
          <div class="content__head">
            <span class="content__label">消息内容</span>
            <CButton
              variant="ghost"
              size="xs"
              icon="copy"
              @click="copyMessage(previewRecord.message)"
            >
              复制
            </CButton>
          </div>
          <div class="content__body">
            <pre>{{ previewRecord.message }}</pre>
          </div>
        </div>
      </template>
    </CModal>
  </div>
</template>

<style scoped>
.admin-page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

.filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 12px 14px;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-xs);
}

.filters :deep(.c-input) { width: 190px; }

.cell-msg {
  font-size: var(--fs-xs);
  line-height: 1.55;
  color: var(--text-secondary);
  max-width: 420px;
}

.content { margin-top: var(--sp-5); }

.content__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.content__label {
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--text-secondary);
}

.content__body {
  padding: 14px 15px;
  max-height: 380px;
  overflow: auto;
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-md);
  background: var(--n-50);
}

.content__body pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-primary);
}

@media (max-width: 620px) {
  .filters :deep(.c-input) { width: 100%; }
}
</style>
