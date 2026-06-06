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

// 全局前置守卫：未登录拦截
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
  return true
})

export default router
