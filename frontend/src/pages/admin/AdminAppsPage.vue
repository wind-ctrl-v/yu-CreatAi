<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { adminDeleteApp, adminListAppByPage, adminUpdateApp } from '../../api/admin'
import CButton from '../../components/ui/CButton.vue'
import CField from '../../components/ui/CField.vue'
import CIcon from '../../components/ui/CIcon.vue'
import CInput from '../../components/ui/CInput.vue'
import CModal from '../../components/ui/CModal.vue'
import CPagination from '../../components/ui/CPagination.vue'
import CSelect from '../../components/ui/CSelect.vue'
import CTable, { type TableColumn } from '../../components/ui/CTable.vue'
import CTag from '../../components/ui/CTag.vue'
import { confirm } from '../../components/ui/confirm'
import { message } from '../../components/ui/toast'
import type { AdminAppUpdateRequest, AppQueryRequest, AppVO } from '../../types'

/* ── 列表状态 ── */
const loading = ref(false)
const tableData = ref<AppVO[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(10)

const searchForm = reactive<AppQueryRequest>({
  appName: '',
  codeGenType: '',
  userId: '',
})

/* ── 编辑弹窗 ── */
const editOpen = ref(false)
const editLoading = ref(false)
const editForm = reactive<AdminAppUpdateRequest & { appName: string }>({
  id: '',
  appName: '',
  priority: 0,
})

const typeOptions = [
  { value: '', label: '全部类型' },
  { value: 'html', label: 'HTML' },
  { value: 'multi_file', label: '多文件' },
  { value: 'vue_project', label: 'Vue 项目' },
]

const TYPE_LABELS: Record<string, string> = {
  html: 'HTML',
  multi_file: '多文件',
  vue_project: 'Vue 项目',
}

const columns: TableColumn[] = [
  { key: 'appName', title: '应用', minWidth: '220px' },
  { key: 'codeGenType', title: '代码类型', width: '120px' },
  { key: 'user', title: '创建者', width: '150px', ellipsis: true },
  { key: 'priority', title: '优先级', width: '100px' },
  { key: 'deploy', title: '部署状态', width: '120px' },
  { key: 'createTime', title: '创建时间', width: '170px' },
  { key: 'action', title: '操作', width: '150px', align: 'right' },
]

async function loadData() {
  loading.value = true
  try {
    const res = await adminListAppByPage({
      ...searchForm,
      codeGenType: searchForm.codeGenType || undefined,
      userId: searchForm.userId || undefined,
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

function goPage(page: number) {
  current.value = page
  loadData()
}

function resetSearch() {
  searchForm.appName = ''
  searchForm.codeGenType = undefined
  searchForm.userId = ''
  handleSearch()
}

function openEditDialog(row: AppVO) {
  editForm.id = row.id
  editForm.appName = row.appName
  editForm.priority = row.priority ?? 0
  editOpen.value = true
}

async function handleSave() {
  if (!editForm.appName.trim()) {
    message.warning('应用名称不能为空')
    return
  }
  editLoading.value = true
  try {
    const res = await adminUpdateApp({
      id: editForm.id,
      appName: editForm.appName,
      priority: Number(editForm.priority) || 0,
    })
    if (res?.code === 0) {
      message.success('更新成功')
      editOpen.value = false
      loadData()
    }
  } finally {
    editLoading.value = false
  }
}

async function handleDelete(row: AppVO) {
  const ok = await confirm({
    title: `删除应用「${row.appName}」`,
    content: '删除后不可恢复，相关代码与对话记录都将失效。',
    okText: '确认删除',
    danger: true,
    icon: 'trash',
  })
  if (!ok) return
  const res = await adminDeleteApp({ id: row.id }).catch(() => null)
  if (res?.code === 0) {
    message.success('删除成功')
    loadData()
  }
}

onMounted(loadData)
</script>

<template>
  <div class="admin-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">Apps</p>
        <h1 class="page-title">应用管理</h1>
        <p class="page-subtitle">共 {{ total }} 个应用，优先级设为 99 即成为精选。</p>
      </div>
    </header>

    <div class="filters">
      <CInput
        v-model="searchForm.appName"
        size="sm"
        icon="search"
        placeholder="搜索应用名称"
        clearable
        @enter="handleSearch"
      />
      <CSelect
        v-model="searchForm.codeGenType"
        size="sm"
        :options="typeOptions"
        placeholder="代码类型"
        width="140px"
        @change="handleSearch"
      />
      <CInput
        v-model="searchForm.userId"
        size="sm"
        icon="user"
        placeholder="创建者 ID"
        clearable
        @enter="handleSearch"
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
      empty-text="没有符合条件的应用"
    >
      <template #cell:appName="{ record }">
        <div class="cell-app">
          <span class="cell-app__thumb">
            <img v-if="record.cover" :src="record.cover" alt="" loading="lazy" />
            <CIcon v-else name="grid" :size="14" />
          </span>
          <div class="cell-app__body">
            <span class="cell-app__name">{{ record.appName || '未命名应用' }}</span>
            <span class="cell-app__id mono">#{{ record.id }}</span>
          </div>
        </div>
      </template>

      <template #cell:codeGenType="{ record }">
        <CTag tone="neutral">{{ TYPE_LABELS[record.codeGenType] || record.codeGenType }}</CTag>
      </template>

      <template #cell:user="{ record }">
        <span class="text-secondary">
          {{ record.user?.userName || record.user?.userAccount || record.userId }}
        </span>
      </template>

      <template #cell:priority="{ record }">
        <CTag v-if="record.priority === 99" tone="spark" icon="sparkles">精选</CTag>
        <span v-else class="text-muted">{{ record.priority ?? 0 }}</span>
      </template>

      <template #cell:deploy="{ record }">
        <CTag :tone="record.deployKey ? 'success' : 'neutral'" dot>
          {{ record.deployKey ? '已部署' : '未部署' }}
        </CTag>
      </template>

      <template #cell:createTime="{ record }">
        <span class="text-muted">{{ record.createTime || '—' }}</span>
      </template>

      <template #cell:action="{ record }">
        <div class="cell-acts">
          <CButton variant="ghost" size="xs" icon="pencil" @click="openEditDialog(record)">
            编辑
          </CButton>
          <CButton variant="ghost" size="xs" icon="trash" danger @click="handleDelete(record)">
            删除
          </CButton>
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

    <CModal
      v-model:open="editOpen"
      title="编辑应用"
      icon="pencil"
      ok-text="保存"
      :ok-loading="editLoading"
      width="460px"
      @ok="handleSave"
    >
      <div class="form">
        <CField label="应用名称" required input-id="a-name">
          <CInput id="a-name" v-model="editForm.appName" placeholder="请输入应用名称" />
        </CField>
        <CField label="优先级" hint="设置为 99 可标记为精选应用" input-id="a-priority">
          <CInput id="a-priority" v-model="editForm.priority" type="number" :min="0" />
        </CField>
      </div>
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

.filters :deep(.c-input) { width: 180px; }

.cell-app {
  display: flex;
  align-items: center;
  gap: 10px;
}

.cell-app__thumb {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: var(--r-xs);
  background: var(--n-100);
  border: 1px solid var(--border-subtle);
  color: var(--text-faint);
  overflow: hidden;
}

.cell-app__thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cell-app__body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.cell-app__name {
  font-weight: 500;
  color: var(--text-strong);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-app__id {
  font-size: var(--fs-2xs);
  color: var(--text-faint);
}

.cell-acts {
  display: flex;
  justify-content: flex-end;
  gap: 3px;
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  padding-bottom: 4px;
}

@media (max-width: 620px) {
  .filters :deep(.c-input) { width: 100%; }
}
</style>
