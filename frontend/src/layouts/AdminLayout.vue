<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CAvatar from '../components/ui/CAvatar.vue'
import CDropdown from '../components/ui/CDropdown.vue'
import CDropdownItem from '../components/ui/CDropdownItem.vue'
import CIcon from '../components/ui/CIcon.vue'
import { confirm } from '../components/ui/confirm'
import { message } from '../components/ui/toast'
import { useLoginUserStore } from '../stores/loginUser'

const router = useRouter()
const route = useRoute()
const store = useLoginUserStore()

const loginUser = computed(() => store.loginUser)
const collapsed = ref(false)

const navItems = [
  { key: 'dashboard', label: '控制台', icon: 'dashboard', path: '/admin' },
  { key: 'users', label: '用户管理', icon: 'users', path: '/admin/users' },
  { key: 'apps', label: '应用管理', icon: 'grid', path: '/admin/apps' },
  { key: 'chats', label: '对话历史', icon: 'message-square', path: '/admin/chats' },
  { key: 'fingerprints', label: '指纹审核', icon: 'fingerprint', path: '/admin/fingerprints' },
]

const displayName = computed(
  () => loginUser.value?.userName || loginUser.value?.userAccount || '管理员',
)

const currentLabel = computed(
  () => navItems.find((i) => isActive(i.path))?.label ?? '管理后台',
)

function isActive(path: string) {
  if (path === '/admin') return route.path === '/admin'
  return route.path.startsWith(path)
}

function navTo(path: string) {
  router.push(path)
}

async function handleLogout() {
  const ok = await confirm({
    title: '退出登录',
    content: '退出后需要重新登录才能访问管理后台。',
    okText: '退出登录',
    danger: true,
    icon: 'log-out',
  })
  if (!ok) return
  await store.logout()
  message.success('已退出登录')
  router.push('/user/login')
}
</script>

<template>
  <div class="admin">
    <aside class="side" :class="{ 'is-collapsed': collapsed }">
      <button class="side__brand" type="button" @click="navTo('/')">
        <span class="side__mark">
          <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
            <path d="M8 1.5 14.5 14H1.5L8 1.5Z" fill="currentColor" />
          </svg>
        </span>
        <span v-if="!collapsed" class="side__brand-text">
          <span class="side__brand-name">CreatAi</span>
          <span class="side__brand-sub">管理后台</span>
        </span>
      </button>

      <button class="side__back" type="button" @click="navTo('/')">
        <CIcon name="arrow-left" :size="15" />
        <span v-if="!collapsed">返回前台</span>
      </button>

      <nav class="side__nav" aria-label="后台导航">
        <p v-if="!collapsed" class="side__group">管理</p>
        <button
          v-for="item in navItems"
          :key="item.key"
          class="side__item"
          :class="{ 'is-active': isActive(item.path) }"
          type="button"
          :title="collapsed ? item.label : undefined"
          @click="navTo(item.path)"
        >
          <CIcon :name="item.icon" :size="16" />
          <span v-if="!collapsed">{{ item.label }}</span>
        </button>
      </nav>

      <div class="side__foot">
        <CDropdown v-if="loginUser" align="left" placement="top" width="200px">
          <template #trigger>
            <button class="side__user" type="button">
              <CAvatar :src="loginUser.userAvatar" :name="displayName" :size="26" />
              <span v-if="!collapsed" class="side__user-name">{{ displayName }}</span>
              <CIcon v-if="!collapsed" name="chevron-up" :size="13" class="side__user-arrow" />
            </button>
          </template>
          <CDropdownItem icon="user" @click="navTo('/me')">个人中心</CDropdownItem>
          <CDropdownItem icon="log-out" danger @click="handleLogout">退出登录</CDropdownItem>
        </CDropdown>

        <button
          class="side__collapse"
          type="button"
          :aria-label="collapsed ? '展开侧栏' : '折叠侧栏'"
          @click="collapsed = !collapsed"
        >
          <CIcon :name="collapsed ? 'panel-open' : 'panel-close'" :size="16" />
        </button>
      </div>
    </aside>

    <div class="body">
      <header class="crumb">
        <CIcon name="settings" :size="14" class="crumb__icon" />
        <span class="crumb__root">管理后台</span>
        <CIcon name="chevron-right" :size="13" class="crumb__sep" />
        <span class="crumb__current">{{ currentLabel }}</span>
      </header>
      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.admin {
  display: flex;
  min-height: 100vh;
  background: var(--bg-page);
}

/* ── 侧边栏 ── */
.side {
  position: fixed;
  top: 0;
  bottom: 0;
  left: 0;
  width: 216px;
  display: flex;
  flex-direction: column;
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border-subtle);
  z-index: var(--z-sticky);
  transition: width var(--t-base) var(--ease-out);
}

.side.is-collapsed { width: 62px; }

.side__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 14px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  overflow: hidden;
  white-space: nowrap;
}

.side__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: none;
  border-radius: var(--r-sm);
  background: var(--accent);
  color: var(--accent-fg);
}

.side__brand-text { display: flex; flex-direction: column; min-width: 0; }

.side__brand-name {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-strong);
  letter-spacing: var(--tracking-heading);
  line-height: 1.25;
}

.side__brand-sub {
  font-size: var(--fs-2xs);
  color: var(--text-muted);
  line-height: 1.3;
}

.side__back {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0 10px 8px;
  padding: 7px 9px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-md);
  background: var(--bg-surface);
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  transition: all var(--t-fast) var(--ease-out);
}

.side__back:hover {
  border-color: var(--border);
  color: var(--text-primary);
  box-shadow: var(--shadow-xs);
}

.side__nav {
  flex: 1;
  padding: 6px 10px;
  overflow-y: auto;
  overflow-x: hidden;
}

.side__group {
  padding: 8px 9px 6px;
  font-size: var(--fs-2xs);
  font-weight: 600;
  letter-spacing: var(--tracking-wide);
  text-transform: uppercase;
  color: var(--text-faint);
}

.side__item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 9px;
  margin-bottom: 2px;
  border: none;
  border-radius: var(--r-md);
  background: transparent;
  font-size: var(--fs-sm);
  font-weight: 450;
  color: var(--text-secondary);
  letter-spacing: var(--tracking-tight);
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  cursor: pointer;
  transition: all var(--t-fast) var(--ease-out);
}

.side__item:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.side__item.is-active {
  background: var(--accent);
  color: var(--accent-fg);
  font-weight: 500;
}

.is-collapsed .side__item,
.is-collapsed .side__back,
.is-collapsed .side__user { justify-content: center; }

.side__foot {
  padding: 10px;
  border-top: 1px solid var(--border-subtle);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.side__user {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 6px 8px;
  border: none;
  border-radius: var(--r-md);
  background: transparent;
  cursor: pointer;
  overflow: hidden;
  transition: background-color var(--t-fast) var(--ease-out);
}

.side__user:hover { background: var(--bg-hover); }

.side__user-name {
  flex: 1;
  min-width: 0;
  font-size: var(--fs-xs);
  font-weight: 500;
  color: var(--text-primary);
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.side__user-arrow { color: var(--text-faint); }

.side__collapse {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 30px;
  border: none;
  border-radius: var(--r-md);
  background: transparent;
  color: var(--text-faint);
  cursor: pointer;
  transition: all var(--t-fast) var(--ease-out);
}

.side__collapse:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

/* ── 主体 ── */
.body {
  flex: 1;
  min-width: 0;
  margin-left: 216px;
  display: flex;
  flex-direction: column;
  transition: margin-left var(--t-base) var(--ease-out);
}

.side.is-collapsed + .body { margin-left: 62px; }

.crumb {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 46px;
  padding: 0 var(--sp-8);
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--border-subtle);
  position: sticky;
  top: 0;
  z-index: 10;
  font-size: var(--fs-xs);
}

.crumb__icon,
.crumb__sep { color: var(--text-faint); }
.crumb__root { color: var(--text-muted); }
.crumb__current { color: var(--text-primary); font-weight: 500; }

.content {
  flex: 1;
  padding: var(--sp-8);
  max-width: var(--container-wide);
  width: 100%;
}

@media (max-width: 900px) {
  .side { width: 62px; }
  .side .side__brand-text,
  .side .side__brand-sub { display: none; }
  .body { margin-left: 62px; }
  .content { padding: var(--sp-5) var(--sp-4); }
  .crumb { padding: 0 var(--sp-4); }
}
</style>
