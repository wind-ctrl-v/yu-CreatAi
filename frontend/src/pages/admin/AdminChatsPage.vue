<script setup lang="ts">
import { ref, reactive, onMounted, h } from 'vue'
import { SearchOutlined, UserOutlined, RobotOutlined, CopyOutlined } from '@ant-design/icons-vue'
import { adminListChatHistory } from '../../api/admin'
import { message } from 'ant-design-vue'
import type { ChatHistory, ChatHistoryQueryRequest } from '../../types'

// ======== 列表状态 ========
const loading = ref(false)
const tableData = ref<ChatHistory[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(15)

// ======== 搜索条件 ========
const searchForm = reactive<ChatHistoryQueryRequest>({
  appId: '',
  userId: '',
  messageType: undefined,
})

// ======== 预览对话框 ========
const previewVisible = ref(false)
const previewRecord = ref<ChatHistory | null>(null)

// ======== 表格列定义 ========
const columns = [
  { title: 'ID', dataIndex: 'id', width: 80, ellipsis: true },
  { title: '消息类型', dataIndex: 'messageType', width: 100 },
  { title: '消息内容', dataIndex: 'message', ellipsis: true },
  { title: '应用 ID', dataIndex: 'appId', width: 120, ellipsis: true },
  { title: '用户 ID', dataIndex: 'userId', width: 120, ellipsis: true },
  { title: '时间', dataIndex: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 80, fixed: 'right' },
]

// ======== 数据加载 ========
async function loadData() {
  loading.value = true
  try {
    const params: ChatHistoryQueryRequest = {
      pageNum: current.value,
      pageSize: pageSize.value,
    }
    if (searchForm.appId)       params.appId       = searchForm.appId
    if (searchForm.userId)      params.userId      = searchForm.userId
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

// ======== 预览 ========
function openPreview(row: ChatHistory) {
  previewRecord.value = row
  previewVisible.value = true
}

function msgTypeLabel(type: string) {
  return type === 'ai' ? 'AI' : '用户'
}
function msgTypeColor(type: string) {
  return type === 'ai' ? 'purple' : 'blue'
}

async function copyMessage(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制到剪贴板')
  } catch {
    message.error('复制失败，请手动选中复制')
  }
}
</script>

<template>
  <div class="chat-manage">
    <!-- 搜索栏 -->
    <div class="toolbar">
      <div class="search-group">
        <a-input
          v-model:value="searchForm.appId"
          placeholder="按应用 ID 过滤"
          allow-clear
          style="width: 180px;"
          @press-enter="handleSearch"
        />
        <a-input
          v-model:value="searchForm.userId"
          placeholder="按用户 ID 过滤"
          allow-clear
          style="width: 180px;"
          @press-enter="handleSearch"
        />
        <a-select
          v-model:value="searchForm.messageType"
          placeholder="消息类型"
          allow-clear
          style="width: 120px;"
        >
          <a-select-option value="user">用户消息</a-select-option>
          <a-select-option value="ai">AI 回复</a-select-option>
        </a-select>
        <a-button type="primary" :icon="h(SearchOutlined)" @click="handleSearch">搜索</a-button>
      </div>
      <div class="total-badge">
        共 <strong>{{ total.toLocaleString() }}</strong> 条记录
      </div>
    </div>

    <!-- 表格 -->
    <div class="table-wrap">
      <a-table
        :columns="columns"
        :data-source="tableData"
        :loading="loading"
        row-key="id"
        :scroll="{ x: 900 }"
        :pagination="{
          current,
          pageSize,
          total,
          showSizeChanger: true,
          pageSizeOptions: ['10', '15', '30', '50'],
          showTotal: (t: number) => `共 ${t} 条`,
        }"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'messageType'">
            <a-tag :color="msgTypeColor(record.messageType)" style="border-radius:4px;">
              <template #icon>
                <UserOutlined  v-if="record.messageType === 'user'" />
                <RobotOutlined v-else />
              </template>
              {{ msgTypeLabel(record.messageType) }}
            </a-tag>
          </template>

          <template v-else-if="column.dataIndex === 'message'">
            <span class="message-preview">{{ record.message }}</span>
          </template>

          <template v-else-if="column.key === 'action'">
            <a-button type="link" size="small" @click="openPreview(record)">查看</a-button>
          </template>
        </template>
      </a-table>
    </div>

    <!-- 消息预览对话框 -->
    <a-modal
      v-model:open="previewVisible"
      title="消息详情"
      :footer="null"
      width="620px"
    >
      <template v-if="previewRecord">
        <div class="preview-meta">
          <a-descriptions :column="2" size="small" bordered>
            <a-descriptions-item label="消息 ID">{{ previewRecord.id }}</a-descriptions-item>
            <a-descriptions-item label="类型">
              <a-tag :color="msgTypeColor(previewRecord.messageType)">
                {{ msgTypeLabel(previewRecord.messageType) }}
              </a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="应用 ID">{{ previewRecord.appId }}</a-descriptions-item>
            <a-descriptions-item label="用户 ID">{{ previewRecord.userId }}</a-descriptions-item>
            <a-descriptions-item label="时间" :span="2">{{ previewRecord.createTime }}</a-descriptions-item>
          </a-descriptions>
        </div>
        <div class="preview-content">
          <div class="preview-label">
            消息内容
            <a-button
              type="link"
              size="small"
              :icon="h(CopyOutlined)"
              @click="copyMessage(previewRecord.message)"
            >
              复制
            </a-button>
          </div>
          <div class="preview-body" :class="{ 'is-ai': previewRecord.messageType === 'ai' }">
            <pre>{{ previewRecord.message }}</pre>
          </div>
        </div>
      </template>
    </a-modal>
  </div>
</template>

<style scoped>
.chat-manage { display: flex; flex-direction: column; gap: 16px; }

.toolbar {
  background: var(--geist-white);
  border-radius: var(--r-card);
  box-shadow: var(--shadow-border);
  padding: 16px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.search-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.total-badge {
  font-size: 13px;
  color: var(--gray-500);
}

.table-wrap {
  background: var(--geist-white);
  border-radius: var(--r-card);
  box-shadow: var(--shadow-border);
  overflow: hidden;
}

.message-preview {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 13px;
  color: var(--gray-700);
  line-height: 1.5;
}

/* 预览弹窗 */
.preview-meta { margin-bottom: 16px; }

.preview-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-600);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.preview-body {
  background: var(--gray-50);
  border-radius: var(--r-base);
  padding: 14px 16px;
  max-height: 400px;
  overflow: auto;
}
.preview-body.is-ai { background: #f5f3ff; }

.preview-body pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  color: var(--geist-black);
}
</style>
