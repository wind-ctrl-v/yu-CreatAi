<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  HomeOutlined,
  AppstoreOutlined,
  CompassOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  SettingOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import { useLoginUserStore } from '../stores/loginUser'
import { PLAN_LABELS } from '../types'

const router = useRouter()
const route = useRoute()
const store = useLoginUserStore()

const loginUser = computed(() => store.loginUser)
const sidebarCollapsed = ref(false)

const planTag = computed(() => {
  const pt = loginUser.value?.planType || ''
  return { label: PLAN_LABELS[pt] || '会员', color: planColor(pt) }
})
function planColor(pt: string): string {
  const colors: Record<string, string> = {
    FREE: '#8c8c8c',
    PRO: '#1677ff',
    PREMIUM: '#722ed1',
    ENTERPRISE: '#f5a623',
    ADMIN: '#f5222d',
  }
  return colors[pt] || '#8c8c8c'
}

onMounted(() => {
  if (!store.fetched) store.fetchLoginUser()
})

function goHome() { router.push('/') }

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

const navItems = [
  { key: 'home',    label: '首页',     icon: 'home',     path: '/' },
  { key: 'myApps',  label: '我的作品',  icon: 'appstore', path: '/apps' },
  { key: 'explore', label: '精选应用',  icon: 'compass',  path: '/explore' },
]

function isActive(path: string) {
  if (path === '/') return route.path === '/'
  return route.path.startsWith(path)
}
function navTo(path: string) { router.push(path) }
</script>

<template>
  <div class="layout">
    <!-- 侧边栏 -->
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed }">
      <!-- Logo -->
      <div class="sidebar-logo" @click="goHome">
        <div class="logo-mark">
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
            <path d="M8 1L15 14H1L8 1Z" fill="currentColor"/>
          </svg>
        </div>
        <transition name="fade-label">
          <span v-if="!sidebarCollapsed" class="logo-text">CreatAi</span>
        </transition>
      </div>

      <!-- 分隔线 -->
      <div class="sidebar-divider" />

      <!-- 导航 -->
      <nav class="sidebar-nav">
        <div
          v-for="item in navItems"
          :key="item.key"
          class="nav-item"
          :class="{ active: isActive(item.path) }"
          @click="navTo(item.path)"
        >
          <span class="nav-icon">
            <HomeOutlined     v-if="item.icon === 'home'" />
            <AppstoreOutlined v-else-if="item.icon === 'appstore'" />
            <CompassOutlined  v-else-if="item.icon === 'compass'" />
          </span>
          <transition name="fade-label">
            <span v-if="!sidebarCollapsed" class="nav-label">{{ item.label }}</span>
          </transition>
        </div>
      </nav>

      <!-- 底部区域 -->
      <div class="sidebar-footer">
        <!-- 用户信息 -->
        <template v-if="loginUser">
          <a-dropdown placement="topRight">
            <div class="user-row">
              <a-avatar :src="loginUser.userAvatar" :size="26" class="user-avatar">
                {{ (loginUser.userName || loginUser.userAccount || 'U').charAt(0) }}
              </a-avatar>
              <transition name="fade-label">
                <span v-if="!sidebarCollapsed" class="user-name">
                  {{ loginUser.userName || loginUser.userAccount }}
                </span>
              </transition>
              <transition name="fade-label">
                <span
                  v-if="!sidebarCollapsed && loginUser.planType"
                  class="plan-badge"
                  :style="{ color: planTag.color, borderColor: planTag.color }"
                >{{ planTag.label }}</span>
              </transition>
            </div>
            <template #overlay>
              <a-menu>
                <a-menu-item key="profile" @click="router.push('/me')">
                  <UserOutlined style="margin-right: 6px;" />个人中心
                </a-menu-item>
                <a-menu-item v-if="loginUser.userRole === 'admin'" key="admin" @click="router.push('/admin')">
                  <SettingOutlined style="margin-right: 6px;" />管理后台
                </a-menu-item>
                <a-menu-item key="logout" @click="handleLogout">退出登录</a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </template>
        <template v-else>
          <div class="login-btn-wrap">
            <a-button size="small" block @click="router.push('/user/login')" class="login-btn">
              <span v-if="!sidebarCollapsed">登录</span>
              <span v-else>→</span>
            </a-button>
          </div>
        </template>

        <!-- 折叠按钮 -->
        <div class="collapse-btn" @click="sidebarCollapsed = !sidebarCollapsed" :title="sidebarCollapsed ? '展开' : '折叠'">
          <MenuFoldOutlined   v-if="!sidebarCollapsed" />
          <MenuUnfoldOutlined v-else />
        </div>
      </div>
    </aside>

    <!-- 主内容区 -->
    <main class="content" :style="{ marginLeft: sidebarCollapsed ? '56px' : '200px' }">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100%;
  background: var(--bg-page);
}

/* ======= 侧边栏 ======= */
.sidebar {
  position: fixed;
  top: 0; left: 0; bottom: 0;
  width: 200px;
  background: var(--bg-sidebar);
  box-shadow: var(--shadow-border);
  display: flex;
  flex-direction: column;
  z-index: 200;
  transition: width 0.22s var(--smooth);
  overflow: hidden;
}
.sidebar.collapsed { width: 56px; }

/* Logo */
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
  background: var(--geist-black);
  color: var(--geist-white);
  transition: background var(--duration-fast) ease;
}

.logo-text {
  font-size: 15px;
  font-weight: 600;
  color: var(--geist-black);
  letter-spacing: var(--tracking-label);
  white-space: nowrap;
}

.sidebar-divider {
  height: 1px;
  background: var(--gray-100);
  margin: 0 14px;
  flex-shrink: 0;
}

/* 导航 */
.sidebar-nav {
  flex: 1;
  padding: 8px 8px;
  overflow: hidden;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 8px;
  border-radius: var(--r-card);
  cursor: pointer;
  color: var(--gray-600);
  font-size: 13px;
  font-weight: 500;
  letter-spacing: var(--tracking-label);
  transition: background var(--duration-fast) ease, color var(--duration-fast) ease;
  white-space: nowrap;
  overflow: hidden;
  margin-bottom: 1px;
}
.nav-item:hover {
  background: var(--gray-50);
  color: var(--geist-black);
}
.nav-item.active {
  background: var(--gray-50);
  color: var(--geist-black);
  font-weight: 600;
  box-shadow: var(--shadow-border);
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
  letter-spacing: var(--tracking-label);
}

.plan-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: var(--r-subtle);
  border: 1px solid;
  white-space: nowrap;
  line-height: 1.5;
  opacity: 0.85;
  flex-shrink: 0;
}

.login-btn-wrap { padding: 0 2px; }
.login-btn {
  border-radius: var(--r-base) !important;
  font-size: 13px !important;
  font-weight: 500 !important;
  box-shadow: var(--shadow-ring) !important;
  border: none !important;
  color: var(--geist-black) !important;
  background: var(--geist-white) !important;
}
.login-btn:hover {
  background: var(--gray-50) !important;
  box-shadow: var(--shadow-border) !important;
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
.content {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  transition: margin-left 0.22s var(--smooth);
  overflow: hidden;
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
