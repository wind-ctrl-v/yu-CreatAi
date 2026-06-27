import request from '../request'
import type { BaseResponse, LoginUserVO, PlanCapabilities } from '../types'

/** 用户注册 */
export function register(params: {
  userAccount: string
  userPassword: string
  checkPassword: string
}) {
  return request.post<any, BaseResponse<string>>('/user/register', params)
}

/** 用户登录 */
export function login(params: { userAccount: string; userPassword: string }) {
  return request.post<any, BaseResponse<LoginUserVO>>('/user/login', params)
}

/** 获取当前登录用户 */
export function getLoginUser() {
  return request.get<any, BaseResponse<LoginUserVO>>('/user/get/login')
}

/** 退出登录 */
export function logout() {
  return request.post<any, BaseResponse<boolean>>('/user/logout')
}

/** 获取当前套餐能力（planType、maxMode、availableModes） */
export function getPlanCapabilities() {
  return request.get<any, BaseResponse<PlanCapabilities>>('/user/plan')
}
