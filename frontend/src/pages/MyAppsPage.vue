<script setup lang="ts">
import { onMounted, ref, h } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { listMyAppByPage, updateApp, deleteApp } from '../api/app'
import type { AppVO, DeleteReason } from '../types'
import AppCard from '../components/AppCard.vue'
import { useAppPage } from '../composables/useAppPage'

const router = useRouter()

const searchName = ref('')
const filterType = ref<string | undefined>(undefined)
const {
  records: apps,
  loading,
  total,
  pageNum,
  pageSize,
  load,
  changePage,
  resetAndLoad,
} = useAppPage(listMyAppByPage, 12)

const typeOptions = [
  { value: undefined, label: '全部类型' },
  { value: 'vue_project', label: 'Vue 项目' },
  { value: 'html', label: 'HTML 页面' },
  { value: 'multi_file', label: '多文件' },
]

function openApp(app: AppVO) { router.push({ name: 'appChat', params: { id: String(app.id) } }) }
function shareApp(app: AppVO) { router.push({ name: 'appShare', params: { id: String(app.id) } }) }

function queryParams() {
  return { appName: searchName.value || undefined, codeGenType: filterType.value }
}

function loadApps() {
  return load(queryParams())
}

async function handleRename(app: AppVO) {
  const newName = ref(app.appName)
  Modal.confirm({
    title: '重命名应用',
    content: h('div', { style: 'padding-top:4px' }, [
      h('input', {
        value: newName.value,
        onInput: (e: Event) => { newName.value = (e.target as HTMLInputElement).value },
        style: 'width:100%;padding:8px 10px;border:1px solid var(--gray-200);border-radius:8px;font-size:14px;outline:none;box-sizing:border-box',
      }),
    ]),
    okText: '确定',
    cancelText: '取消',
    onOk: async (close) => {
      const name = newName.value.trim()
      if (!name || name === app.appName) return close()
      const res = await updateApp({ id: String(app.id), appName: name })
      if (res?.code === 0) {
        message.success('已重命名')
        loadApps()
      }
      close()
    },
  })
}

const deleteModalOpen = ref(false)
const deletingApp = ref<AppVO | null>(null)
const deleteReason = ref<DeleteReason>('unknown')

async function handleDelete(app: AppVO) {
  deletingApp.value = app
  deleteReason.value = 'unknown'
  deleteModalOpen.value = true
}

async function confirmDelete() {
  if (!deletingApp.value) return
  const res = await deleteApp(String(deletingApp.value.id), deleteReason.value).catch(() => null)
  if (res?.code === 0) {
    message.success('已删除')
    deleteModalOpen.value = false
    loadApps()
  }
}

function onPageChange(page: number) {
  changePage(page, queryParams())
}

function onSearch() {
  resetAndLoad(queryParams())
}

function onFilterChange() {
  resetAndLoad(queryParams())
}

onMounted(() => { loadApps() })
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">我的作品</h2>
      <span class="page-count">{{ total }} 个</span>
    </div>

    <div class="toolbar">
      <a-input-search
        v-model:value="searchName"
        placeholder="搜索应用名称…"
        :bordered="false"
        class="search-input"
        @search="onSearch"
        @press-enter="onSearch"
      />
      <a-select
        v-model:value="filterType"
        :options="typeOptions"
        :bordered="false"
        class="type-select"
        @change="onFilterChange"
      />
    </div>

    <a-spin :spinning="loading">
      <div v-if="apps.length" class="grid">
        <AppCard
          v-for="app in apps"
          :key="app.id"
          :app="app"
          @open="openApp"
        >
          <template #actions>
            <a-button size="small" type="text" @click.stop="handleRename(app)">重命名</a-button>
            <a-button size="small" type="text" @click.stop="shareApp(app)">展示</a-button>
            <a-button size="small" type="text" danger @click.stop="handleDelete(app)">删除</a-button>
          </template>
        </AppCard>
      </div>
      <div v-else-if="!loading" class="empty-state">还没有作品，去首页开始创作吧</div>
    </a-spin>

    <div v-if="total > pageSize" class="pagination">
      <a-pagination
        :current="pageNum"
        :total="total"
        :page-size="pageSize"
        :show-size-changer="false"
        @change="onPageChange"
      />
    </div>

    <!-- 删除应用弹窗（带原因选择，用于 N-3 负反馈边界控制） -->
    <a-modal
      v-model:open="deleteModalOpen"
      title="删除应用"
      ok-text="删除"
      cancel-text="取消"
      :ok-button-props="{ danger: true }"
      @ok="confirmDelete"
    >
      <p class="delete-confirm-text">
        确定要删除「{{ deletingApp?.appName }}」吗？此操作不可撤销。
      </p>
      <div class="delete-reason">
        <p class="delete-reason-label">删除原因（帮助 AI 更好地理解你的喜好）</p>
        <a-radio-group v-model:value="deleteReason" class="reason-group">
          <a-radio value="dislike">不喜欢这个结果</a-radio>
          <a-radio value="correction">方向不对，重新做</a-radio>
          <a-radio value="test">只是测试</a-radio>
          <a-radio value="unknown">其他原因</a-radio>
        </a-radio-group>
      </div>
    </a-modal>
  </div>
</template>

<style scoped>
.page {
  flex: 1;
  overflow-y: auto;
  padding: 32px 48px 64px;
  background: var(--bg-page);
}
.page-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 20px;
}
.page-title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
}
.page-count {
  font-size: 12px;
  color: var(--gray-400);
  background: var(--gray-50);
  padding: 2px 8px;
  border-radius: var(--r-pill);
}
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  max-width: 560px;
}
.search-input {
  flex: 1;
  background: var(--geist-white);
  border-radius: var(--r-base);
  box-shadow: var(--shadow-ring);
}
.type-select {
  width: 140px;
  flex-shrink: 0;
  background: var(--geist-white);
  border-radius: var(--r-base);
  box-shadow: var(--shadow-ring);
}
.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}
.empty-state {
  color: var(--gray-400);
  text-align: center;
  padding: 60px 0;
}
.pagination {
  display: flex;
  justify-content: center;
  margin-top: 32px;
}
@media (max-width: 960px) { .grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 580px) { .grid { grid-template-columns: 1fr; } }

/* 删除弹窗 */
.delete-confirm-text {
  margin: 0 0 16px;
  font-size: 14px;
  color: var(--gray-700);
}
.delete-reason {
  padding: 12px 0 4px;
  border-top: 1px solid var(--gray-100);
}
.delete-reason-label {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--gray-500);
}
.reason-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
