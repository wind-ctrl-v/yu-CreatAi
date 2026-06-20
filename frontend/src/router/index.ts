import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useLoginUserStore } from '../stores/loginUser'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: () => import('../layouts/BasicLayout.vue'),
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('../pages/HomePage.vue'),
        meta: { requiresAuth: true },
      },
      {
        path: 'app/chat/:id',
        name: 'appChat',
        component: () => import('../pages/AppChatPage.vue'),
        meta: { requiresAuth: true },
      },
    ],
  },
  {
    path: '/admin',
    component: () => import('../layouts/AdminLayout.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
    children: [
      {
        path: '',
        name: 'adminDashboard',
        component: () => import('../pages/admin/AdminDashboard.vue'),
      },
      {
        path: 'users',
        name: 'adminUsers',
        component: () => import('../pages/admin/AdminUsersPage.vue'),
      },
      {
        path: 'apps',
        name: 'adminApps',
        component: () => import('../pages/admin/AdminAppsPage.vue'),
      },
      {
        path: 'chats',
        name: 'adminChats',
        component: () => import('../pages/admin/AdminChatsPage.vue'),
      },
    ],
  },
  {
    path: '/user/login',
    name: 'login',
    component: () => import('../pages/user/LoginPage.vue'),
  },
  {
    path: '/user/register',
    name: 'register',
    component: () => import('../pages/user/RegisterPage.vue'),
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 全局前置守卫：登录拦截 + 管理员权限拦截
router.beforeEach(async (to) => {
  if (!to.meta.requiresAuth) {
    return true
  }
  const store = useLoginUserStore()
  let user = store.loginUser
  // 登录态缺失时才请求后端校验，避免重复请求
  if (!user && !store.fetched) {
    user = await store.fetchLoginUser()
  }
  if (!user) {
    return {
      path: '/user/login',
      query: { redirect: to.fullPath },
    }
  }
  // 管理员路由额外检查角色
  if (to.meta.requiresAdmin && user.userRole !== 'admin') {
    return { path: '/' }
  }
  return true
})

export default router
