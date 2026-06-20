<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  UserOutlined,
  AppstoreOutlined,
  MessageOutlined,
  RiseOutlined,
} from '@ant-design/icons-vue'
import { adminListUserByPage, adminListAppByPage, adminListChatHistory } from '../../api/admin'

const router = useRouter()

const stats = ref({
  totalUsers: 0,
  totalApps: 0,
  totalChats: 0,
})
const loading = ref(true)

onMounted(async () => {
  try {
    const [usersRes, appsRes, chatsRes] = await Promise.all([
      adminListUserByPage({ pageNum: 1, pageSize: 1 }),
      adminListAppByPage({ pageNum: 1, pageSize: 1 }),
      adminListChatHistory({ pageNum: 1, pageSize: 1 }),
    ])
    if (usersRes?.code === 0) stats.value.totalUsers = usersRes.data?.totalRow ?? 0
    if (appsRes?.code === 0)  stats.value.totalApps  = appsRes.data?.totalRow ?? 0
    if (chatsRes?.code === 0) stats.value.totalChats = chatsRes.data?.totalRow ?? 0
  } finally {
    loading.value = false
  }
})

const cards = [
  {
    key: 'users',
    label: '注册用户',
    icon: 'user',
    color: '#5b6cff',
    bg: '#f0f1ff',
    path: '/admin/users',
    getValue: () => stats.value.totalUsers,
  },
  {
    key: 'apps',
    label: '创建应用',
    icon: 'app',
    color: '#0a72ef',
    bg: '#f0f6ff',
    path: '/admin/apps',
    getValue: () => stats.value.totalApps,
  },
  {
    key: 'chats',
    label: '对话次数',
    icon: 'message',
    color: '#38a169',
    bg: '#f0fff4',
    path: '/admin/chats',
    getValue: () => stats.value.totalChats,
  },
]
</script>

<template>
  <div class="dashboard">
    <div class="dashboard-welcome">
      <h1 class="welcome-title">欢迎回来，管理员</h1>
      <p class="welcome-sub">以下是平台整体数据概览</p>
    </div>

    <!-- 统计卡片 -->
    <div class="stat-grid">
      <div
        v-for="card in cards"
        :key="card.key"
        class="stat-card"
        @click="router.push(card.path)"
      >
        <div class="stat-icon-wrap" :style="{ background: card.bg }">
          <UserOutlined     v-if="card.icon === 'user'"    :style="{ color: card.color, fontSize: '20px' }" />
          <AppstoreOutlined v-else-if="card.icon === 'app'" :style="{ color: card.color, fontSize: '20px' }" />
          <MessageOutlined  v-else-if="card.icon === 'message'" :style="{ color: card.color, fontSize: '20px' }" />
        </div>
        <div class="stat-content">
          <div class="stat-label">{{ card.label }}</div>
          <div class="stat-value">
            <a-spin v-if="loading" size="small" />
            <span v-else>{{ card.getValue().toLocaleString() }}</span>
          </div>
        </div>
        <RiseOutlined class="stat-arrow" />
      </div>
    </div>

    <!-- 快捷操作 -->
    <div class="quick-section">
      <h2 class="section-title">快捷管理</h2>
      <div class="quick-grid">
        <div class="quick-card" @click="router.push('/admin/users')">
          <UserOutlined class="quick-icon" />
          <span class="quick-label">用户管理</span>
          <span class="quick-desc">查看、编辑用户信息及权限</span>
        </div>
        <div class="quick-card" @click="router.push('/admin/apps')">
          <AppstoreOutlined class="quick-icon" />
          <span class="quick-label">应用管理</span>
          <span class="quick-desc">管理所有应用，支持删除、更新</span>
        </div>
        <div class="quick-card" @click="router.push('/admin/chats')">
          <MessageOutlined class="quick-icon" />
          <span class="quick-label">对话历史</span>
          <span class="quick-desc">查看全平台用户的对话记录</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dashboard {
  max-width: 960px;
}

.dashboard-welcome {
  margin-bottom: 28px;
}

.welcome-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--geist-black);
  margin: 0 0 6px;
}

.welcome-sub {
  font-size: 14px;
  color: var(--gray-500);
  margin: 0;
}

/* 统计卡片 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 32px;
}

@media (max-width: 700px) {
  .stat-grid { grid-template-columns: 1fr; }
}

.stat-card {
  background: var(--geist-white);
  border-radius: var(--r-card);
  box-shadow: var(--shadow-border);
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  cursor: pointer;
  transition: box-shadow var(--duration-fast) ease, transform var(--duration-fast) ease;
}
.stat-card:hover {
  box-shadow: var(--shadow-card);
  transform: translateY(-2px);
}

.stat-icon-wrap {
  width: 48px;
  height: 48px;
  border-radius: var(--r-base);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.stat-content { flex: 1; min-width: 0; }

.stat-label {
  font-size: 13px;
  color: var(--gray-500);
  margin-bottom: 4px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--geist-black);
  line-height: 1.2;
}

.stat-arrow {
  font-size: 16px;
  color: var(--gray-300);
}

/* 快捷操作 */
.section-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--geist-black);
  margin: 0 0 14px;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}

@media (max-width: 700px) {
  .quick-grid { grid-template-columns: 1fr; }
}

.quick-card {
  background: var(--geist-white);
  border-radius: var(--r-card);
  box-shadow: var(--shadow-border);
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  cursor: pointer;
  transition: box-shadow var(--duration-fast) ease, transform var(--duration-fast) ease;
}
.quick-card:hover {
  box-shadow: var(--shadow-card);
  transform: translateY(-2px);
}

.quick-icon {
  font-size: 20px;
  color: #5b6cff;
  margin-bottom: 4px;
}

.quick-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--geist-black);
}

.quick-desc {
  font-size: 12px;
  color: var(--gray-500);
  line-height: 1.5;
}
</style>
