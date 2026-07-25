<script setup lang="ts">
import { ref, reactive, onMounted, h } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { SearchOutlined, EditOutlined, DeleteOutlined, AppstoreOutlined } from '@ant-design/icons-vue'
import {
  adminListAppByPage,
  adminDeleteApp,
  adminUpdateApp,
} from '../../api/admin'
import type { AppVO, AppQueryRequest, AdminAppUpdateRequest } from '../../types'

// ======== 列表状态 ========
const loading = ref(false)
const tableData = ref<AppVO[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(10)

// ======== 搜索条件 ========
const searchForm = reactive<AppQueryRequest>({
  appName: '',
  codeGenType: undefined,
  userId: '',
})

// ======== 编辑对话框 ========
const editVisible = ref(false)
const editLoading = ref(false)
const editForm = reactive<AdminAppUpdateRequest & { appName: string }>({
  id: '',
  appName: '',
  priority: 0,
})

// ======== 表格列定义 ========
const columns = [
  { title: 'ID', dataIndex: 'id', width: 80, ellipsis: true },
  { title: '应用名称', dataIndex: 'appName', width: 180 },
  { title: '代码类型', dataIndex: 'codeGenType', width: 120 },
  { title: '创建者', key: 'user', width: 130 },
  { title: '优先级', dataIndex: 'priority', width: 90 },
  { title: '是否部署', key: 'deploy', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 130, fixed: 'right' },
]

const codeGenTypeOptions = [
  { label: 'HTML', value: 'html' },
  { label: '多文件', value: 'multi_file' },
  { label: 'Vue 项目', value: 'vue_project' },
]

function codeTypeLabel(type: string) {
  return codeGenTypeOptions.find(o => o.value === type)?.label || type
}

// ======== 数据加载 ========
async function loadData() {
  loading.value = true
  try {
    const res = await adminListAppByPage({
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

// ======== 编辑 ========
function openEditDialog(row: AppVO) {
  editForm.id        = row.id
  editForm.appName   = row.appName
  editForm.priority  = row.priority ?? 0
  editVisible.value  = true
}

async function handleSave() {
  if (!editForm.appName.trim()) {
    message.warning('应用名称不能为空')
    return
  }
  editLoading.value = true
  try {
    const res = await adminUpdateApp({
      id:       editForm.id,
      appName:  editForm.appName,
      priority: editForm.priority,
    })
    if (res?.code === 0) {
      message.success('更新成功')
      editVisible.value = false
      loadData()
    }
  } finally {
    editLoading.value = false
  }
}

// ======== 删除 ========
function handleDelete(row: AppVO) {
  Modal.confirm({
    title: `确认删除应用「${row.appName}」？`,
    content: '删除后不可恢复，请谨慎操作。',
    okType: 'danger',
    okText: '删除',
    cancelText: '取消',
    onOk: async () => {
      const res = await adminDeleteApp({ id: row.id })
      if (res?.code === 0) {
        message.success('删除成功')
        loadData()
      }
    },
  })
}
</script>

<template>
  <div class="app-manage">
    <!-- 搜索栏 -->
    <div class="toolbar">
      <div class="search-group">
        <a-input
          v-model:value="searchForm.appName"
          placeholder="搜索应用名称"
          allow-clear
          style="width: 180px;"
          @press-enter="handleSearch"
        />
        <a-select
          v-model:value="searchForm.codeGenType"
          placeholder="代码类型"
          allow-clear
          style="width: 130px;"
        >
          <a-select-option v-for="o in codeGenTypeOptions" :key="o.value" :value="o.value">
            {{ o.label }}
          </a-select-option>
        </a-select>
        <a-input
          v-model:value="searchForm.userId"
          placeholder="创建者 ID"
          allow-clear
          style="width: 160px;"
          @press-enter="handleSearch"
        />
        <a-button type="primary" :icon="h(SearchOutlined)" @click="handleSearch">搜索</a-button>
      </div>
    </div>

    <!-- 表格 -->
    <div class="table-wrap">
      <a-table
        :columns="columns"
        :data-source="tableData"
        :loading="loading"
        row-key="id"
        :scroll="{ x: 950 }"
        :pagination="{
          current,
          pageSize,
          total,
          showSizeChanger: true,
          showTotal: (t: number) => `共 ${t} 条`,
        }"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'appName'">
            <div class="cell-app">
              <div class="app-cover" v-if="record.cover">
                <img :src="record.cover" alt="" />
              </div>
              <div class="app-cover-placeholder" v-else>
                <AppstoreOutlined />
              </div>
              <span class="app-name">{{ record.appName }}</span>
            </div>
          </template>

          <template v-else-if="column.dataIndex === 'codeGenType'">
            <a-tag style="border-radius:4px;">{{ codeTypeLabel(record.codeGenType) }}</a-tag>
          </template>

          <template v-else-if="column.key === 'user'">
            <span class="cell-user">
              {{ record.user?.userName || record.user?.userAccount || record.userId }}
            </span>
          </template>

          <template v-else-if="column.key === 'deploy'">
            <a-badge
              :status="record.deployKey ? 'success' : 'default'"
              :text="record.deployKey ? '已部署' : '未部署'"
            />
          </template>

          <template v-else-if="column.dataIndex === 'priority'">
            <a-tag v-if="record.priority === 99" color="gold" style="border-radius:4px;">精选</a-tag>
            <span v-else style="color: var(--gray-400);">{{ record.priority ?? 0 }}</span>
          </template>

          <template v-else-if="column.key === 'action'">
            <a-space>
              <a-button type="text" size="small" :icon="h(EditOutlined)" @click="openEditDialog(record)">
                编辑
              </a-button>
              <a-button type="text" size="small" danger :icon="h(DeleteOutlined)" @click="handleDelete(record)">
                删除
              </a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </div>

    <!-- 编辑对话框 -->
    <a-modal
      v-model:open="editVisible"
      title="编辑应用"
      :confirm-loading="editLoading"
      ok-text="保存"
      cancel-text="取消"
      @ok="handleSave"
    >
      <a-form layout="vertical" style="margin-top: 8px;">
        <a-form-item label="应用名称" required>
          <a-input v-model:value="editForm.appName" placeholder="请输入应用名称" />
        </a-form-item>
        <a-form-item label="优先级">
          <a-input-number v-model:value="editForm.priority" :min="0" style="width: 100%;" />
          <div style="font-size:12px; color: var(--gray-400); margin-top:4px;">
            设置为 99 可标记为精选应用
          </div>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.app-manage { display: flex; flex-direction: column; gap: 16px; }

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

.table-wrap {
  background: var(--geist-white);
  border-radius: var(--r-card);
  box-shadow: var(--shadow-border);
  overflow: hidden;
}

.cell-app {
  display: flex;
  align-items: center;
  gap: 10px;
}

.app-cover {
  width: 32px;
  height: 32px;
  border-radius: 4px;
  overflow: hidden;
  flex-shrink: 0;
}
.app-cover img { width: 100%; height: 100%; object-fit: cover; }

.app-cover-placeholder {
  width: 32px;
  height: 32px;
  border-radius: 4px;
  background: var(--gray-50);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--gray-300);
  flex-shrink: 0;
}

.app-name {
  font-weight: 500;
  color: var(--geist-black);
}

.cell-user {
  font-size: 13px;
  color: var(--gray-600);
}
</style>
