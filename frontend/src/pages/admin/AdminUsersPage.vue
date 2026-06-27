<script setup lang="ts">
import { ref, reactive, onMounted, h } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined, SearchOutlined, EditOutlined, DeleteOutlined, UserOutlined } from '@ant-design/icons-vue'
import {
  adminListUserByPage,
  adminAddUser,
  adminUpdateUser,
  adminDeleteUser,
} from '../../api/admin'
import type { UserVO, UserQueryRequest, UserAddRequest, UserUpdateRequest } from '../../types'

// ======== 列表状态 ========
const loading = ref(false)
const tableData = ref<UserVO[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(10)

// ======== 搜索条件 ========
const searchForm = reactive<UserQueryRequest>({
  userAccount: '',
  userName: '',
  userRole: undefined,
})

// ======== 对话框状态 ========
const dialogVisible = ref(false)
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

// ======== 表格列定义 ========
const columns = [
  { title: 'ID', dataIndex: 'id', width: 80, ellipsis: true },
  { title: '账号', dataIndex: 'userAccount', width: 120 },
  { title: '昵称', dataIndex: 'userName', width: 120 },
  { title: '角色', dataIndex: 'userRole', width: 90 },
  { title: '套餐', dataIndex: 'planType', width: 100 },
  { title: '简介', dataIndex: 'userProfile', ellipsis: true },
  { title: '注册时间', dataIndex: 'createTime', width: 170 },
  { title: '操作', key: 'action', width: 120, fixed: 'right' },
]

// ======== 数据加载 ========
async function loadData() {
  loading.value = true
  try {
    const res = await adminListUserByPage({
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

// ======== 新增 ========
function openAddDialog() {
  dialogMode.value = 'add'
  formData.id = undefined
  formData.userAccount = ''
  formData.userName = ''
  formData.userAvatar = ''
  formData.userProfile = ''
  formData.userRole = 'user'
  formData.planType = 'FREE'
  dialogVisible.value = true
}

// ======== 编辑 ========
function openEditDialog(row: UserVO) {
  dialogMode.value = 'edit'
  formData.id = row.id
  formData.userAccount = row.userAccount
  formData.userName = row.userName || ''
  formData.userAvatar = row.userAvatar || ''
  formData.userProfile = row.userProfile || ''
  formData.userRole = row.userRole
  formData.planType = row.planType || 'FREE'
  dialogVisible.value = true
}

// ======== 保存 ========
async function handleSave() {
  if (!formData.userAccount && dialogMode.value === 'add') {
    message.warning('账号不能为空')
    return
  }
  formLoading.value = true
  try {
    let res
    if (dialogMode.value === 'add') {
      res = await adminAddUser({
        userAccount: formData.userAccount,
        userName:    formData.userName,
        userAvatar:  formData.userAvatar,
        userProfile: formData.userProfile,
        userRole:    formData.userRole,
        planType:    formData.planType,
      } as UserAddRequest)
    } else {
      res = await adminUpdateUser({
        id:          formData.id!,
        userName:    formData.userName,
        userAvatar:  formData.userAvatar,
        userProfile: formData.userProfile,
        userRole:    formData.userRole,
        planType:    formData.planType,
      } as UserUpdateRequest)
    }
    if (res?.code === 0) {
      message.success(dialogMode.value === 'add' ? '新增成功' : '更新成功')
      dialogVisible.value = false
      loadData()
    }
  } finally {
    formLoading.value = false
  }
}

// ======== 删除 ========
function handleDelete(row: UserVO) {
  Modal.confirm({
    title: `确认删除用户「${row.userAccount}」？`,
    content: '删除后不可恢复，请谨慎操作。',
    okType: 'danger',
    okText: '删除',
    cancelText: '取消',
    onOk: async () => {
      const res = await adminDeleteUser({ id: row.id })
      if (res?.code === 0) {
        message.success('删除成功')
        loadData()
      }
    },
  })
}

function roleColor(role: string) {
  return role === 'admin' ? 'red' : 'blue'
}
function roleLabel(role: string) {
  return role === 'admin' ? '管理员' : '普通用户'
}
const planLabels: Record<string, string> = {
  FREE: '免费', PRO: '会员', PREMIUM: '高级', ENTERPRISE: '企业', ADMIN: '管理员',
}
const planColors: Record<string, string> = {
  FREE: 'default', PRO: 'blue', PREMIUM: 'purple', ENTERPRISE: 'orange', ADMIN: 'red',
}
</script>

<template>
  <div class="user-manage">
    <!-- 搜索栏 -->
    <div class="toolbar">
      <div class="search-group">
        <a-input
          v-model:value="searchForm.userAccount"
          placeholder="搜索账号"
          allow-clear
          style="width: 160px;"
          @press-enter="handleSearch"
        />
        <a-input
          v-model:value="searchForm.userName"
          placeholder="搜索昵称"
          allow-clear
          style="width: 160px;"
          @press-enter="handleSearch"
        />
        <a-select
          v-model:value="searchForm.userRole"
          placeholder="角色筛选"
          allow-clear
          style="width: 120px;"
        >
          <a-select-option value="user">普通用户</a-select-option>
          <a-select-option value="admin">管理员</a-select-option>
        </a-select>
        <a-button type="primary" :icon="h(SearchOutlined)" @click="handleSearch">搜索</a-button>
      </div>
      <a-button type="primary" danger :icon="h(PlusOutlined)" @click="openAddDialog">新增用户</a-button>
    </div>

    <!-- 表格 -->
    <div class="table-wrap">
      <a-table
        :columns="columns"
        :data-source="tableData"
        :loading="loading"
        row-key="id"
        :scroll="{ x: 800 }"
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
          <template v-if="column.dataIndex === 'userAccount'">
            <div class="cell-user">
              <a-avatar :src="record.userAvatar" :size="24">
                <template #icon><UserOutlined /></template>
              </a-avatar>
              <span>{{ record.userAccount }}</span>
            </div>
          </template>

          <template v-else-if="column.dataIndex === 'userRole'">
            <a-tag :color="roleColor(record.userRole)" style="border-radius:4px;">
              {{ roleLabel(record.userRole) }}
            </a-tag>
          </template>

          <template v-else-if="column.dataIndex === 'planType'">
            <a-tag v-if="record.planType" :color="planColors[record.planType] || 'default'" style="border-radius:4px;">
              {{ planLabels[record.planType] || record.planType }}
            </a-tag>
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

    <!-- 新增/编辑对话框 -->
    <a-modal
      v-model:open="dialogVisible"
      :title="dialogMode === 'add' ? '新增用户' : '编辑用户'"
      :confirm-loading="formLoading"
      ok-text="保存"
      cancel-text="取消"
      @ok="handleSave"
    >
      <a-form layout="vertical" style="margin-top: 8px;">
        <a-form-item label="账号" required>
          <a-input
            v-model:value="formData.userAccount"
            :disabled="dialogMode === 'edit'"
            placeholder="请输入账号"
          />
        </a-form-item>
        <a-form-item label="昵称">
          <a-input v-model:value="formData.userName" placeholder="请输入昵称" />
        </a-form-item>
        <a-form-item label="头像 URL">
          <a-input v-model:value="formData.userAvatar" placeholder="请输入头像 URL" />
        </a-form-item>
        <a-form-item label="简介">
          <a-textarea v-model:value="formData.userProfile" placeholder="请输入简介" :rows="3" />
        </a-form-item>
        <a-form-item label="角色">
          <a-select v-model:value="formData.userRole">
            <a-select-option value="user">普通用户</a-select-option>
            <a-select-option value="admin">管理员</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="套餐">
          <a-select v-model:value="formData.planType">
            <a-select-option value="FREE">免费用户</a-select-option>
            <a-select-option value="PRO">普通会员</a-select-option>
            <a-select-option value="PREMIUM">高级会员</a-select-option>
            <a-select-option value="ENTERPRISE">企业版</a-select-option>
            <a-select-option value="ADMIN">管理员</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.user-manage { display: flex; flex-direction: column; gap: 16px; }

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

.cell-user {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
