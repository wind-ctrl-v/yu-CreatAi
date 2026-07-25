import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { LoginUserVO } from '../types'
import { getLoginUser, logout as logoutApi } from '../api/user'

export const useLoginUserStore = defineStore('loginUser', () => {
  const loginUser = ref<LoginUserVO | null>(null)
  // 是否已尝试拉取过登录态，避免守卫重复请求
  const fetched = ref(false)

  function setLoginUser(user: LoginUserVO | null) {
    loginUser.value = user
    // 标记已获取登录态，避免后续守卫/布局重复请求
    fetched.value = true
  }

  /** 拉取当前登录用户（失败返回 null，不抛出） */
  async function fetchLoginUser(): Promise<LoginUserVO | null> {
    const res = await getLoginUser().catch(() => null)
    fetched.value = true
    loginUser.value = res?.code === 0 ? res.data : null
    return loginUser.value
  }

  /** 退出登录 */
  async function logout() {
    await logoutApi().catch((err) => console.error('退出失败：', err))
    loginUser.value = null
  }

  return { loginUser, fetched, setLoginUser, fetchLoginUser, logout }
})
