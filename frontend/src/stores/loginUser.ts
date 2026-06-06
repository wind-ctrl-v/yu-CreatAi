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
  }

  /** 拉取当前登录用户（失败返回 null，不抛出） */
  async function fetchLoginUser(): Promise<LoginUserVO | null> {
    const res = await getLoginUser().catch(() => null)
    fetched.value = true
    if (res && res.code === 0) {
      loginUser.value = res.data
    } else {
      loginUser.value = null
    }
    return loginUser.value
  }

  /** 退出登录 */
  async function logout() {
    await logoutApi().catch((err) => console.error('退出失败：', err))
    loginUser.value = null
  }

  return { loginUser, fetched, setLoginUser, fetchLoginUser, logout }
})
