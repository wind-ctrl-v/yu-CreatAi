<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { deleteApp, listMyAppByPage, updateApp } from '../api/app'
import AppCard from '../components/AppCard.vue'
import CButton from '../components/ui/CButton.vue'
import CEmpty from '../components/ui/CEmpty.vue'
import CField from '../components/ui/CField.vue'
import CInput from '../components/ui/CInput.vue'
import CModal from '../components/ui/CModal.vue'
import CPagination from '../components/ui/CPagination.vue'
import CRadioGroup from '../components/ui/CRadioGroup.vue'
import CSelect from '../components/ui/CSelect.vue'
import CSpin from '../components/ui/CSpin.vue'
import { message } from '../components/ui/toast'
import { useAppPage } from '../composables/useAppPage'
import type { AppVO, DeleteReason } from '../types'

const router = useRouter()

const searchName = ref('')
const filterType = ref<string>('')

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
  { value: '', label: '全部类型' },
  { value: 'vue_project', label: 'Vue 项目' },
  { value: 'html', label: 'HTML 页面' },
  { value: 'multi_file', label: '多文件' },
]

const reasonOptions = [
  { value: 'dislike', label: '不喜欢这个结果', desc: 'AI 会减少同类风格的输出' },
  { value: 'correction', label: '方向不对，重新做' },
  { value: 'test', label: '只是测试' },
  { value: 'unknown', label: '其他原因' },
]

/* ── 重命名 ── */
const renameOpen = ref(false)
const renameTarget = ref<AppVO | null>(null)
const renameValue = ref('')
const renaming = ref(false)

function handleRename(app: AppVO) {
  renameTarget.value = app
  renameValue.value = app.appName ?? ''
  renameOpen.value = true
}

async function confirmRename() {
  const app = renameTarget.value
  const name = renameValue.value.trim()
  if (!app) return
  if (!name) {
    message.warning('名称不能为空')
    return
  }
  if (name === app.appName) {
    renameOpen.value = false
    return
  }
  renaming.value = true
  const res = await updateApp({ id: String(app.id), appName: name }).catch(() => null)
  renaming.value = false
  if (res?.code === 0) {
    message.success('已重命名')
    renameOpen.value = false
    loadApps()
  }
}

/* ── 删除 ── */
const deleteOpen = ref(false)
const deleteTarget = ref<AppVO | null>(null)
const deleteReason = ref<DeleteReason>('unknown')
const deleting = ref(false)

function handleDelete(app: AppVO) {
  deleteTarget.value = app
  deleteReason.value = 'unknown'
  deleteOpen.value = true
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  const res = await deleteApp(String(deleteTarget.value.id), deleteReason.value).catch(() => null)
  deleting.value = false
  if (res?.code === 0) {
    message.success('已删除')
    deleteOpen.value = false
    loadApps()
  }
}

/* ── 列表 ── */
function queryParams() {
  return {
    appName: searchName.value || undefined,
    codeGenType: filterType.value || undefined,
  }
}

function loadApps() {
  return load(queryParams())
}

function openApp(app: AppVO) {
  router.push({ name: 'appChat', params: { id: String(app.id) } })
}

function shareApp(app: AppVO) {
  router.push({ name: 'appShare', params: { id: String(app.id) } })
}

onMounted(loadApps)
</script>

<template>
  <div class="page container">
    <header class="page-head">
      <div>
        <p class="eyebrow">Workspace</p>
        <h1 class="page-title">我的作品</h1>
        <p class="page-subtitle">这里是你创建的全部应用，可继续对话迭代或对外展示。</p>
      </div>
      <CButton variant="primary" icon="plus" @click="router.push('/')">新建应用</CButton>
    </header>

    <div class="toolbar">
      <CInput
        v-model="searchName"
        type="search"
        placeholder="搜索应用名称…"
        clearable
        class="toolbar__search"
        @search="resetAndLoad(queryParams())"
      />
      <CSelect
        v-model="filterType"
        :options="typeOptions"
        width="150px"
        @change="resetAndLoad(queryParams())"
      />
      <span class="toolbar__total">共 {{ total }} 个</span>
    </div>

    <CSpin :spinning="loading">
      <div v-if="apps.length" class="grid">
        <AppCard v-for="app in apps" :key="app.id" :app="app" @open="openApp">
          <template #actions>
            <CButton variant="ghost" size="xs" icon="pencil" @click="handleRename(app)">
              重命名
            </CButton>
            <CButton variant="ghost" size="xs" icon="share" @click="shareApp(app)">展示</CButton>
            <CButton variant="ghost" size="xs" icon="trash" danger @click="handleDelete(app)">
              删除
            </CButton>
          </template>
        </AppCard>
      </div>
      <CEmpty
        v-else-if="!loading"
        icon="sparkles"
        description="还没有作品"
        hint="回到首页描述你的想法，几秒钟就能生成第一个应用。"
      >
        <CButton variant="primary" size="sm" @click="router.push('/')">开始创作</CButton>
      </CEmpty>
    </CSpin>

    <div v-if="total > pageSize" class="pager">
      <CPagination
        :current="pageNum"
        :total="total"
        :page-size="pageSize"
        @change="(p) => changePage(p, queryParams())"
      />
    </div>

    <!-- 重命名 -->
    <CModal
      v-model:open="renameOpen"
      title="重命名应用"
      description="换一个更容易辨认的名字。"
      icon="pencil"
      ok-text="保存"
      :ok-loading="renaming"
      width="440px"
      @ok="confirmRename"
    >
      <CField label="应用名称" input-id="rename-input">
        <CInput
          id="rename-input"
          v-model="renameValue"
          placeholder="请输入应用名称"
          :maxlength="60"
          @enter="confirmRename"
        />
      </CField>
    </CModal>

    <!-- 删除（带原因反馈） -->
    <CModal
      v-model:open="deleteOpen"
      title="删除应用"
      :description="`确定要删除「${deleteTarget?.appName ?? ''}」吗？此操作不可撤销。`"
      icon="trash"
      danger
      ok-text="确认删除"
      :ok-loading="deleting"
      width="480px"
      @ok="confirmDelete"
    >
      <div class="reason">
        <p class="reason__label">删除原因（帮助 AI 更好地理解你的偏好）</p>
        <CRadioGroup v-model="deleteReason" :options="reasonOptions" card />
      </div>
    </CModal>
  </div>
</template>

<style scoped>
.page {
  flex: 1;
  padding-top: var(--sp-10);
  padding-bottom: var(--sp-16);
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: var(--sp-6);
}

.toolbar__search {
  flex: 1;
  max-width: 380px;
}

.toolbar__total {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-4);
}

.pager { margin-top: var(--sp-8); }

.reason {
  padding-bottom: 4px;
}

.reason__label {
  margin-bottom: 10px;
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

@media (max-width: 980px) {
  .grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 600px) {
  .grid { grid-template-columns: 1fr; }
  .toolbar { flex-wrap: wrap; }
  .toolbar__search { max-width: none; flex-basis: 100%; }
  .toolbar__total { display: none; }
}
</style>
