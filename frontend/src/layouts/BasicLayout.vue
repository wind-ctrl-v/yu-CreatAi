<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CAvatar from '../components/ui/CAvatar.vue'
import CButton from '../components/ui/CButton.vue'
import CDropdown from '../components/ui/CDropdown.vue'
import CDropdownItem from '../components/ui/CDropdownItem.vue'
import CIcon from '../components/ui/CIcon.vue'
import { confirm } from '../components/ui/confirm'
import { message } from '../components/ui/toast'
import { useLoginUserStore } from '../stores/loginUser'
import { PLAN_LABELS } from '../types'

const router = useRouter()
const route = useRoute()
const store = useLoginUserStore()

const loginUser = computed(() => store.loginUser)
const mobileOpen = ref(false)

const navItems = [
  { key: 'home', label: '首页', icon: 'home', path: '/' },
  { key: 'myApps', label: '我的作品', icon: 'grid', path: '/apps' },
  { key: 'explore', label: '精选应用', icon: 'compass', path: '/explore' },
]

const planLabel = computed(() => {
  const pt = loginUser.value?.planType || ''
  return pt ? PLAN_LABELS[pt] || '会员' : ''
})

const displayName = computed(
  () => loginUser.value?.userName || loginUser.value?.userAccount || '未命名用户',
)

function isActive(path: string) {
  if (path === '/') return route.path === '/'
  return route.path.startsWith(path)
}

function navTo(path: string) {
  mobileOpen.value = false
  router.push(path)
}

async function handleLogout() {
  const ok = await confirm({
    title: '退出登录',
    content: '退出后需要重新登录才能继续创作。',
    okText: '退出登录',
    danger: true,
    icon: 'log-out',
  })
  if (!ok) return
  await store.logout()
  message.success('已退出登录')
  router.push('/user/login')
}

watch(() => route.path, () => (mobileOpen.value = false))

onMounted(() => {
  if (!store.fetched) store.fetchLoginUser()
})
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="topbar__inner">
        <button class="brand" type="button" @click="navTo('/')">
          <span class="brand__mark">
            <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
              <path d="M8 1.5 14.5 14H1.5L8 1.5Z" fill="currentColor" />
            </svg>
          </span>
          <span class="brand__text">CreatAi</span>
        </button>

        <nav class="nav" aria-label="主导航">
          <button
            v-for="item in navItems"
            :key="item.key"
            class="nav__item"
            :class="{ 'is-active': isActive(item.path) }"
            type="button"
            @click="navTo(item.path)"
          >
            {{ item.label }}
          </button>
        </nav>

        <div class="topbar__right">
          <CButton
            v-if="loginUser"
            variant="ghost"
            size="sm"
            icon="plus"
            class="hide-sm"
            @click="navTo('/')"
          >
            新建应用
          </CButton>

          <CDropdown v-if="loginUser" align="right" width="216px">
            <template #trigger>
              <button class="user-chip" type="button" :aria-label="displayName">
                <CAvatar :src="loginUser.userAvatar" :name="displayName" :size="26" />
                <span class="user-chip__name">{{ displayName }}</span>
                <CIcon name="chevron-down" :size="13" class="user-chip__arrow" />
              </button>
            </template>

            <div class="menu-head">
              <p class="menu-head__name">{{ displayName }}</p>
              <p class="menu-head__meta">
                {{ planLabel || '未开通套餐' }}
              </p>
            </div>
            <CDropdownItem icon="user" @click="navTo('/me')">个人中心</CDropdownItem>
            <CDropdownItem icon="grid" @click="navTo('/apps')">我的作品</CDropdownItem>
            <CDropdownItem
              v-if="loginUser.userRole === 'admin'"
              icon="settings"
              @click="navTo('/admin')"
            >
              管理后台
            </CDropdownItem>
            <div class="menu-sep" />
            <CDropdownItem icon="log-out" danger @click="handleLogout">退出登录</CDropdownItem>
          </CDropdown>

          <template v-else>
            <CButton variant="text" size="sm" class="hide-sm" @click="navTo('/user/login')">
              登录
            </CButton>
            <CButton variant="primary" size="sm" @click="navTo('/user/register')">
              免费注册
            </CButton>
          </template>

          <button
            class="burger"
            type="button"
            :aria-expanded="mobileOpen"
            aria-label="切换菜单"
            @click="mobileOpen = !mobileOpen"
          >
            <CIcon :name="mobileOpen ? 'x' : 'menu'" :size="18" />
          </button>
        </div>
      </div>

      <Transition name="drawer">
        <div v-if="mobileOpen" class="mobile-nav">
          <button
            v-for="item in navItems"
            :key="item.key"
            class="mobile-nav__item"
            :class="{ 'is-active': isActive(item.path) }"
            type="button"
            @click="navTo(item.path)"
          >
            <CIcon :name="item.icon" :size="16" />
            {{ item.label }}
          </button>
          <template v-if="loginUser">
            <div class="menu-sep" />
            <button class="mobile-nav__item" type="button" @click="navTo('/me')">
              <CIcon name="user" :size="16" />个人中心
            </button>
            <button
              v-if="loginUser.userRole === 'admin'"
              class="mobile-nav__item"
              type="button"
              @click="navTo('/admin')"
            >
              <CIcon name="settings" :size="16" />管理后台
            </button>
          </template>
          <template v-else>
            <div class="menu-sep" />
            <button class="mobile-nav__item" type="button" @click="navTo('/user/login')">
              <CIcon name="log-in" :size="16" />登录
            </button>
          </template>
        </div>
      </Transition>
    </header>

    <main class="main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: var(--bg-page);
}

/* ── 顶栏 ── */
.topbar {
  position: sticky;
  top: 0;
  z-index: var(--z-sticky);
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: saturate(180%) blur(14px);
  border-bottom: 1px solid var(--border-subtle);
}

.topbar__inner {
  display: flex;
  align-items: center;
  gap: 20px;
  height: var(--header-h);
  max-width: var(--container-wide);
  margin: 0 auto;
  padding: 0 var(--sp-6);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 4px 6px 4px 4px;
  margin-left: -4px;
  border: none;
  background: transparent;
  border-radius: var(--r-md);
  cursor: pointer;
  transition: background-color var(--t-fast) var(--ease-out);
}

.brand:hover { background: var(--bg-hover); }

.brand__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--r-sm);
  background: var(--accent);
  color: var(--accent-fg);
}

.brand__text {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: var(--tracking-heading);
  color: var(--text-strong);
}

.nav {
  display: flex;
  align-items: center;
  gap: 2px;
}

.nav__item {
  height: 32px;
  padding: 0 12px;
  border: none;
  border-radius: var(--r-sm);
  background: transparent;
  font-size: var(--fs-sm);
  font-weight: 450;
  color: var(--text-secondary);
  letter-spacing: var(--tracking-tight);
  cursor: pointer;
  transition: all var(--t-fast) var(--ease-out);
}

.nav__item:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.nav__item.is-active {
  background: var(--bg-hover);
  color: var(--text-strong);
  font-weight: 550;
}

.topbar__right {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}

.user-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 9px 0 5px;
  margin-left: 10px;
  border: 1px solid transparent;
  border-radius: var(--r-pill);
  background: transparent;
  cursor: pointer;
  transition: all var(--t-fast) var(--ease-out);
}

.user-chip::before {
  content: '';
  position: absolute;
  width: 1px;
  height: 18px;
  margin-left: -16px;
  background: var(--border);
}

.user-chip:hover {
  background: var(--bg-surface);
  border-color: var(--border);
  box-shadow: var(--shadow-xs);
}

.user-chip__name {
  font-size: var(--fs-sm);
  font-weight: 450;
  color: var(--text-primary);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-chip__arrow { color: var(--text-faint); }

.menu-head {
  padding: 9px 11px 10px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--border-subtle);
}

.menu-head__name {
  font-size: var(--fs-sm);
  font-weight: 550;
  color: var(--text-strong);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-head__meta {
  margin-top: 2px;
  font-size: var(--fs-2xs);
  color: var(--text-muted);
}

.menu-sep {
  height: 1px;
  margin: 4px -1px;
  background: var(--border-subtle);
}

.burger {
  display: none;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--border);
  border-radius: var(--r-sm);
  background: var(--bg-surface);
  color: var(--text-primary);
  cursor: pointer;
}

.mobile-nav {
  display: none;
  flex-direction: column;
  gap: 2px;
  padding: 8px var(--sp-4) 14px;
  border-top: 1px solid var(--border-subtle);
  background: var(--bg-surface);
}

.mobile-nav__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px;
  border: none;
  border-radius: var(--r-md);
  background: transparent;
  font-size: var(--fs-sm);
  color: var(--text-primary);
  text-align: left;
  cursor: pointer;
}

.mobile-nav__item.is-active,
.mobile-nav__item:hover { background: var(--bg-hover); }

.main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.drawer-enter-active,
.drawer-leave-active {
  transition: opacity var(--t-fast) var(--ease-out), transform var(--t-fast) var(--ease-out);
}

.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

@media (max-width: 860px) {
  .topbar__inner { padding: 0 var(--sp-4); gap: 12px; }
  .nav { display: none; }
  .burger { display: inline-flex; }
  .mobile-nav { display: flex; }
  .hide-sm { display: none !important; }
  .user-chip__name { display: none; }
}
</style>
