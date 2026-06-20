<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  UserOutlined,
  AppstoreOutlined,
  MessageOutlined,
  DashboardOutlined,
  LeftOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
} from '@ant-design/icons-vue'
import { useLoginUserStore } from '../stores/loginUser'

const router = useRouter()
const route = useRoute()
const store = useLoginUserStore()

const loginUser = computed(() => store.loginUser)
const sidebarCollapsed = ref(false)

const navItems = [
  { key: 'dashboard', label: '控制台',   icon: 'dashboard', path: '/admin' },
  { key: 'users',     label: '用户管理', icon: 'user',      path: '/admin/users' },
  { key: 'apps',      label: '应用管理', icon: 'app',       path: '/admin/apps' },
  { key: 'chats',     label: '对话历史', icon: 'message',   path: '/admin/chats' },
]

function isActive(path: string) {
  if (path === '/admin') return route.path === '/admin'
  return route.path.startsWith(path)
}

function navTo(path: string) { router.push(path) }

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
  <div class="admin-layout">
    <!-- 侧边栏 -->
    <aside class="admin-sidebar" :class="{ collapsed: sidebarCollapsed }">
      <!-- Logo -->
      <div class="sidebar-logo" @click="router.push('/')">
        <div class="logo-mark">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
            <path d="M8 1L15 14H1L8 1Z" fill="currentColor"/>
          </svg>
        </div>
        <transition name="fade-label">
          <span v-if="!sidebarCollapsed" class="logo-text">管理后台</span>
        </transition>
      </div>

      <div class="sidebar-divider" />

      <!-- 返回前台 -->
      <div class="back-btn" @click="router.push('/')">
        <LeftOutlined class="back-icon" />
        <transition name="fade-label">
          <span v-if="!sidebarCollapsed" class="back-label">返回前台</span>
        </transition>
      </div>

      <div class="sidebar-divider" style="margin-top: 4px;" />

      <!-- 导航菜单 -->
      <nav class="sidebar-nav">
        <div
          v-for="item in navItems"
          :key="item.key"
          class="nav-item"
          :class="{ active: isActive(item.path) }"
          @click="navTo(item.path)"
        >
          <span class="nav-icon">
            <DashboardOutlined v-if="item.icon === 'dashboard'" />
            <UserOutlined      v-else-if="item.icon === 'user'" />
            <AppstoreOutlined  v-else-if="item.icon === 'app'" />
            <MessageOutlined   v-else-if="item.icon === 'message'" />
          </span>
          <transition name="fade-label">
            <span v-if="!sidebarCollapsed" class="nav-label">{{ item.label }}</span>
          </transition>
        </div>
      </nav>

      <!-- 底部 -->
      <div class="sidebar-footer">
        <template v-if="loginUser">
          <a-dropdown placement="topRight">
            <div class="user-row">
              <a-avatar :src="loginUser.userAvatar" :size="26" class="user-avatar">
                {{ (loginUser.userName || loginUser.userAccount || 'A').charAt(0) }}
              </a-avatar>
              <transition name="fade-label">
                <span v-if="!sidebarCollapsed" class="user-name">
                  {{ loginUser.userName || loginUser.userAccount }}
                </span>
              </transition>
            </div>
            <template #overlay>
              <a-menu>
                <a-menu-item key="logout" @click="handleLogout">退出登录</a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </template>

        <div class="collapse-btn" @click="sidebarCollapsed = !sidebarCollapsed">
          <MenuFoldOutlined   v-if="!sidebarCollapsed" />
          <MenuUnfoldOutlined v-else />
        </div>
      </div>
    </aside>

    <!-- 主内容区 -->
    <main class="admin-content" :style="{ marginLeft: sidebarCollapsed ? '56px' : '220px' }">
      <!-- 顶部栏 -->
      <header class="admin-header">
        <div class="header-breadcrumb">
          <span class="header-title">{{ navItems.find(n => isActive(n.path))?.label || '控制台' }}</span>
        </div>
        <div class="header-right">
          <a-tag color="red" style="border-radius: 4px; font-size: 12px;">Admin</a-tag>
          <span class="header-user">{{ loginUser?.userName || loginUser?.userAccount }}</span>
        </div>
      </header>

      <!-- 页面内容 -->
      <div class="admin-page">
        <router-view />
      </div>
    </main>
  </div>
</template>

<style scoped>
.admin-layout {
  display: flex;
  height: 100%;
  background: #f5f5f5;
}

/* ======= 侧边栏 ======= */
.admin-sidebar {
  position: fixed;
  top: 0; left: 0; bottom: 0;
  width: 220px;
  background: var(--geist-white);
  box-shadow: var(--shadow-border);
  display: flex;
  flex-direction: column;
  z-index: 200;
  transition: width 0.22s var(--smooth);
  overflow: hidden;
}
.admin-sidebar.collapsed { width: 56px; }

.sidebar-logo {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 18px 14px 16px;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
  overflow: hidden;
  flex-shrink: 0;
}
.sidebar-logo:hover .logo-mark { background: var(--gray-900); }

.logo-mark {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--r-base);
  background: #e53e3e;
  color: var(--geist-white);
  transition: background var(--duration-fast) ease;
}

.logo-text {
  font-size: 15px;
  font-weight: 600;
  color: #e53e3e;
  letter-spacing: var(--tracking-label);
  white-space: nowrap;
}

.sidebar-divider {
  height: 1px;
  background: var(--gray-100);
  margin: 0 14px;
  flex-shrink: 0;
}

/* 返回按钮 */
.back-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 22px;
  cursor: pointer;
  color: var(--gray-500);
  font-size: 12px;
  transition: color var(--duration-fast) ease;
  white-space: nowrap;
  overflow: hidden;
  flex-shrink: 0;
}
.back-btn:hover { color: var(--geist-black); }
.back-icon { font-size: 11px; flex-shrink: 0; }
.back-label { white-space: nowrap; }

/* 导航 */
.sidebar-nav {
  flex: 1;
  padding: 8px;
  overflow: hidden;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 8px;
  border-radius: var(--r-card);
  cursor: pointer;
  color: var(--gray-600);
  font-size: 13px;
  font-weight: 500;
  letter-spacing: var(--tracking-label);
  transition: background var(--duration-fast) ease, color var(--duration-fast) ease;
  white-space: nowrap;
  overflow: hidden;
  margin-bottom: 2px;
}
.nav-item:hover {
  background: var(--gray-50);
  color: var(--geist-black);
}
.nav-item.active {
  background: #fff5f5;
  color: #e53e3e;
  font-weight: 600;
  box-shadow: 0 0 0 1px rgba(229, 62, 62, 0.15);
}

.nav-icon {
  flex-shrink: 0;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  color: inherit;
}
.nav-label { white-space: nowrap; }

/* 底部 */
.sidebar-footer {
  padding: 10px 8px 14px;
  border-top: 1px solid var(--gray-100);
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0;
}

.user-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 6px;
  border-radius: var(--r-card);
  cursor: pointer;
  transition: background var(--duration-fast) ease;
  overflow: hidden;
  white-space: nowrap;
}
.user-row:hover { background: var(--gray-50); }
.user-avatar { flex-shrink: 0; }

.user-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--geist-black);
  overflow: hidden;
  text-overflow: ellipsis;
}

.collapse-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 30px;
  border-radius: var(--r-card);
  cursor: pointer;
  color: var(--gray-400);
  font-size: 13px;
  transition: background var(--duration-fast) ease, color var(--duration-fast) ease;
}
.collapse-btn:hover {
  background: var(--gray-50);
  color: var(--geist-black);
}

/* ======= 主内容区 ======= */
.admin-content {
  flex: 1;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  transition: margin-left 0.22s var(--smooth);
}

.admin-header {
  height: 56px;
  background: var(--geist-white);
  box-shadow: 0 0 0 1px var(--gray-100);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  position: sticky;
  top: 0;
  z-index: 100;
  flex-shrink: 0;
}

.header-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--geist-black);
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-user {
  font-size: 13px;
  color: var(--gray-600);
  font-weight: 500;
}

.admin-page {
  flex: 1;
  padding: 24px;
  overflow: auto;
}

/* label 动画 */
.fade-label-enter-active, .fade-label-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}
.fade-label-enter-from, .fade-label-leave-to {
  opacity: 0;
  transform: translateX(-4px);
}
</style>
