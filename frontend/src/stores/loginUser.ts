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

  // TODO: 有后端后删掉这个 mock，恢复真实 fetchLoginUser
  const DEV_MOCK = import.meta.env.DEV

  /** 拉取当前登录用户（失败返回 null，不抛出） */
  async function fetchLoginUser(): Promise<LoginUserVO | null> {
    const res = await getLoginUser().catch(() => null)
    fetched.value = true
    if (res && res.code === 0) {
      loginUser.value = res.data
    } else if (DEV_MOCK && !res) {
      // 开发阶段后端不可达时，注入 mock 管理员以便预览所有页面
      loginUser.value = {
        id: '1',
        userAccount: 'admin',
        userName: '管理员（Mock）',
        userRole: 'admin',
        planType: 'ADMIN',
      }
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
