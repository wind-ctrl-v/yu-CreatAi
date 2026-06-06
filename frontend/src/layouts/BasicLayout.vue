<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { useLoginUserStore } from '../stores/loginUser'

const router = useRouter()
const store = useLoginUserStore()

const loginUser = computed(() => store.loginUser)

onMounted(() => {
  if (!store.fetched) {
    store.fetchLoginUser()
  }
})

function goHome() {
  router.push('/')
}

function handleLogout() {
  Modal.confirm({
    title: '确认退出登录？',
    okText: '退出',
    cancelText: '取消',
    onOk: async () => {
      await store.logout()
      message.success('已退出登录')
      router.push('/user/login')
    },
  })
}
</script>

<template>
  <div class="layout">
    <header class="header">
      <div class="logo" @click="goHome">
        <span class="logo-mark">YU</span>
        <span class="logo-text">Code Father</span>
      </div>

      <div class="header-right">
        <template v-if="loginUser">
          <a-dropdown placement="bottomRight">
            <div class="user-chip">
              <a-avatar :src="loginUser.userAvatar" size="small">
                {{ (loginUser.userName || loginUser.userAccount || 'U').charAt(0) }}
              </a-avatar>
              <span class="user-name">{{ loginUser.userName || loginUser.userAccount }}</span>
            </div>
            <template #overlay>
              <a-menu>
                <a-menu-item key="logout" @click="handleLogout">退出登录</a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </template>
        <template v-else>
          <a-button type="primary" @click="router.push('/user/login')">登录</a-button>
        </template>
      </div>
    </header>

    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.header {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 60px;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 28px;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(12px);
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  user-select: none;
}

.logo-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: linear-gradient(135deg, #1677ff, #4096ff);
  color: #fff;
  font-weight: 700;
  font-size: 14px;
  letter-spacing: 0.5px;
}

.logo-text {
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary);
}

.header-right {
  display: flex;
  align-items: center;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px;
  border-radius: 20px;
  cursor: pointer;
  transition: background 0.2s;
}
.user-chip:hover {
  background: rgba(22, 119, 255, 0.08);
}

.user-name {
  font-size: 14px;
  color: var(--text-secondary);
}

.content {
  flex: 1;
  padding-top: 60px;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
</style>
