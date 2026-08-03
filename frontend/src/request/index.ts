import axios from 'axios'
import type { AxiosInstance, AxiosResponse } from 'axios'
import { message } from '../components/ui/toast'

/** 后端基址（开发期经 Vite 代理到 8123） */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

const request: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  withCredentials: true, // 关键：携带 Session Cookie
  timeout: 60000,
})

// 未登录错误码
const NOT_LOGIN_CODE = 40100

// 响应拦截：统一解包 BaseResponse
request.interceptors.response.use(
  (response: AxiosResponse) => {
    const res = response.data
    // 文件下载等非标准响应直接返回
    if (res instanceof Blob) {
      return response
    }
    // 标准 BaseResponse
    if (res && typeof res.code === 'number') {
      if (res.code === 0) {
        return res
      }
      // 未登录：清理登录态并跳转登录页
      if (res.code === NOT_LOGIN_CODE) {
        const redirect = window.location.pathname + window.location.search
        if (!window.location.pathname.startsWith('/user/login')) {
          window.location.href = `/user/login?redirect=${encodeURIComponent(redirect)}`
        }
        return Promise.reject(new Error(res.message || '未登录'))
      }
      message.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => {
    console.error('请求异常：', error)
    message.error(error?.message || '网络异常，请稍后重试')
    return Promise.reject(error)
  },
)

export default request
