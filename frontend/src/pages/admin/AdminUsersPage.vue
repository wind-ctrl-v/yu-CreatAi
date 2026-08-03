<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  adminAddUser,
  adminDeleteUser,
  adminListUserByPage,
  adminUpdateUser,
} from '../../api/admin'
import CAvatar from '../../components/ui/CAvatar.vue'
import CButton from '../../components/ui/CButton.vue'
import CField from '../../components/ui/CField.vue'
import CInput from '../../components/ui/CInput.vue'
import CModal from '../../components/ui/CModal.vue'
import CPagination from '../../components/ui/CPagination.vue'
import CSelect from '../../components/ui/CSelect.vue'
import CTable, { type TableColumn } from '../../components/ui/CTable.vue'
import CTag from '../../components/ui/CTag.vue'
import CTextarea from '../../components/ui/CTextarea.vue'
import { confirm } from '../../components/ui/confirm'
import { message } from '../../components/ui/toast'
import type {
  UserAddRequest,
  UserQueryRequest,
  UserUpdateRequest,
  UserVO,
} from '../../types'

/* ── 列表状态 ── */
const loading = ref(false)
const tableData = ref<UserVO[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(10)

const searchForm = reactive<UserQueryRequest>({
  userAccount: '',
  userName: '',
  userRole: '',
})

/* ── 弹窗状态 ── */
const dialogOpen = ref(false)
const dialogMode = ref<'add' | 'edit'>('add')
const formLoading = ref(false)
const formData = reactive<UserAddRequest & { id?: string }>({
  userAccount: '',
  userName: '',
  userAvatar: '',
  userProfile: '',
  userRole: 'user',
  planType: 'FREE',
})

const roleOptions = [
  { value: '', label: '全部角色' },
  { value: 'user', label: '普通用户' },
  { value: 'admin', label: '管理员' },
]

const roleFormOptions = [
  { value: 'user', label: '普通用户' },
  { value: 'admin', label: '管理员' },
]

const planOptions = [
  { value: 'FREE', label: '免费用户' },
  { value: 'PRO', label: '普通会员' },
  { value: 'PREMIUM', label: '高级会员' },
  { value: 'ENTERPRISE', label: '企业版' },
  { value: 'ADMIN', label: '管理员' },
]

const columns: TableColumn[] = [
  { key: 'userAccount', title: '账号', minWidth: '180px' },
  { key: 'userName', title: '昵称', width: '130px', ellipsis: true },
  { key: 'userRole', title: '角色', width: '110px' },
  { key: 'planType', title: '套餐', width: '110px' },
  { key: 'userProfile', title: '简介', minWidth: '180px', ellipsis: true },
  { key: 'createTime', title: '注册时间', width: '170px' },
  { key: 'action', title: '操作', width: '150px', align: 'right' },
]

const PLAN_LABELS: Record<string, string> = {
  FREE: '免费',
  PRO: '会员',
  PREMIUM: '高级',
  ENTERPRISE: '企业',
  ADMIN: '管理员',
}

const PLAN_TONES: Record<string, 'neutral' | 'info' | 'violet' | 'warning' | 'spark'> = {
  FREE: 'neutral',
  PRO: 'info',
  PREMIUM: 'violet',
  ENTERPRISE: 'warning',
  ADMIN: 'spark',
}

async function loadData() {
  loading.value = true
  try {
    const res = await adminListUserByPage({
      ...searchForm,
      userRole: searchForm.userRole || undefined,
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
  searchForm.userAccount = ''
  searchForm.userName = ''
  searchForm.userRole = undefined
  handleSearch()
}

function openAddDialog() {
  dialogMode.value = 'add'
  Object.assign(formData, {
    id: undefined,
    userAccount: '',
    userName: '',
    userAvatar: '',
    userProfile: '',
    userRole: 'user',
    planType: 'FREE',
  })
  dialogOpen.value = true
}

function openEditDialog(row: UserVO) {
  dialogMode.value = 'edit'
  Object.assign(formData, {
    id: row.id,
    userAccount: row.userAccount,
    userName: row.userName || '',
    userAvatar: row.userAvatar || '',
    userProfile: row.userProfile || '',
    userRole: row.userRole,
    planType: row.planType || 'FREE',
  })
  dialogOpen.value = true
}

async function handleSave() {
  if (!formData.userAccount && dialogMode.value === 'add') {
    message.warning('账号不能为空')
    return
  }
  formLoading.value = true
  try {
    const res =
      dialogMode.value === 'add'
        ? await adminAddUser({
            userAccount: formData.userAccount,
            userName: formData.userName,
            userAvatar: formData.userAvatar,
            userProfile: formData.userProfile,
            userRole: formData.userRole,
            planType: formData.planType,
          } as UserAddRequest)
        : await adminUpdateUser({
            id: formData.id!,
            userName: formData.userName,
            userAvatar: formData.userAvatar,
            userProfile: formData.userProfile,
            userRole: formData.userRole,
            planType: formData.planType,
          } as UserUpdateRequest)
    if (res?.code === 0) {
      message.success(dialogMode.value === 'add' ? '新增成功' : '更新成功')
      dialogOpen.value = false
      loadData()
    }
  } finally {
    formLoading.value = false
  }
}

async function handleDelete(row: UserVO) {
  const ok = await confirm({
    title: `删除用户「${row.userAccount}」`,
    content: '删除后不可恢复，该用户的数据将无法访问。',
    okText: '确认删除',
    danger: true,
    icon: 'trash',
  })
  if (!ok) return
  const res = await adminDeleteUser({ id: row.id }).catch(() => null)
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
        <p class="eyebrow">Users</p>
        <h1 class="page-title">用户管理</h1>
        <p class="page-subtitle">共 {{ total }} 位注册用户。</p>
      </div>
      <CButton variant="primary" icon="plus" @click="openAddDialog">新增用户</CButton>
    </header>

    <!-- 筛选 -->
    <div class="filters">
      <CInput
        v-model="searchForm.userAccount"
        size="sm"
        icon="search"
        placeholder="搜索账号"
        clearable
        @enter="handleSearch"
      />
      <CInput
        v-model="searchForm.userName"
        size="sm"
        icon="user"
        placeholder="搜索昵称"
        clearable
        @enter="handleSearch"
      />
      <CSelect
        v-model="searchForm.userRole"
        size="sm"
        :options="roleOptions"
        placeholder="角色筛选"
        width="130px"
        @change="handleSearch"
      />
      <CButton variant="primary" size="sm" icon="search" @click="handleSearch">搜索</CButton>
      <CButton variant="ghost" size="sm" icon="refresh" @click="resetSearch">重置</CButton>
    </div>

    <!-- 表格 -->
    <CTable
      :columns="columns"
      :data="tableData"
      :loading="loading"
      row-key="id"
      min-width="1020px"
      empty-text="没有符合条件的用户"
    >
      <template #cell:userAccount="{ record }">
        <div class="cell-user">
          <CAvatar :src="record.userAvatar" :name="record.userName || record.userAccount" :size="26" />
          <div class="cell-user__body">
            <span class="cell-user__account">{{ record.userAccount }}</span>
            <span class="cell-user__id mono">#{{ record.id }}</span>
          </div>
        </div>
      </template>

      <template #cell:userRole="{ record }">
        <CTag :tone="record.userRole === 'admin' ? 'spark' : 'neutral'" dot>
          {{ record.userRole === 'admin' ? '管理员' : '普通用户' }}
        </CTag>
      </template>

      <template #cell:planType="{ record }">
        <CTag v-if="record.planType" :tone="PLAN_TONES[record.planType] || 'neutral'">
          {{ PLAN_LABELS[record.planType] || record.planType }}
        </CTag>
        <span v-else class="text-muted">—</span>
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

    <!-- 新增 / 编辑 -->
    <CModal
      v-model:open="dialogOpen"
      :title="dialogMode === 'add' ? '新增用户' : '编辑用户'"
      :icon="dialogMode === 'add' ? 'plus' : 'pencil'"
      ok-text="保存"
      :ok-loading="formLoading"
      width="520px"
      @ok="handleSave"
    >
      <div class="form">
        <CField label="账号" required input-id="u-account">
          <CInput
            id="u-account"
            v-model="formData.userAccount"
            :disabled="dialogMode === 'edit'"
            placeholder="请输入账号"
          />
        </CField>

        <div class="form__row">
          <CField label="昵称" input-id="u-name">
            <CInput id="u-name" v-model="formData.userName" placeholder="请输入昵称" />
          </CField>
          <CField label="角色" input-id="u-role">
            <CSelect id="u-role" v-model="formData.userRole" :options="roleFormOptions" />
          </CField>
        </div>

        <div class="form__row">
          <CField label="套餐" input-id="u-plan">
            <CSelect id="u-plan" v-model="formData.planType" :options="planOptions" />
          </CField>
          <CField label="头像 URL" input-id="u-avatar">
            <CInput id="u-avatar" v-model="formData.userAvatar" placeholder="https://" />
          </CField>
        </div>

        <CField label="简介" input-id="u-profile">
          <CTextarea
            id="u-profile"
            v-model="formData.userProfile"
            :rows="3"
            :maxlength="200"
            placeholder="请输入简介"
          />
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

.filters :deep(.c-input) { width: 170px; }

.cell-user {
  display: flex;
  align-items: center;
  gap: 9px;
}

.cell-user__body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.cell-user__account {
  font-weight: 500;
  color: var(--text-strong);
}

.cell-user__id {
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

.form__row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-4);
}

@media (max-width: 620px) {
  .form__row { grid-template-columns: 1fr; }
  .filters :deep(.c-input) { width: 100%; }
}
</style>
