import request from '../request'
import type {
  BaseResponse,
  Page,
  UserVO,
  UserQueryRequest,
  UserAddRequest,
  UserUpdateRequest,
  DeleteRequest,
  AppVO,
  AppQueryRequest,
  AdminAppUpdateRequest,
  ChatHistory,
  ChatHistoryQueryRequest,
} from '../types'

// ==================== 用户管理 ====================

/** 管理员新增用户 */
export function adminAddUser(params: UserAddRequest) {
  return request.post<any, BaseResponse<string>>('/user/add', params)
}

/** 管理员删除用户 */
export function adminDeleteUser(params: DeleteRequest) {
  return request.post<any, BaseResponse<boolean>>('/user/delete', params)
}

/** 管理员更新用户 */
export function adminUpdateUser(params: UserUpdateRequest) {
  return request.post<any, BaseResponse<boolean>>('/user/update', params)
}

/** 管理员分页查询用户列表 */
export function adminListUserByPage(params: UserQueryRequest) {
  return request.post<any, BaseResponse<Page<UserVO>>>('/user/list/page/vo', params)
}

// ==================== 应用管理 ====================

/** 管理员分页查询所有应用 */
export function adminListAppByPage(params: AppQueryRequest) {
  return request.post<any, BaseResponse<Page<AppVO>>>('/app/admin/list/page/vo', params)
}

/** 管理员删除任意应用 */
export function adminDeleteApp(params: DeleteRequest) {
  return request.post<any, BaseResponse<boolean>>('/app/admin/delete', params)
}

/** 管理员更新任意应用 */
export function adminUpdateApp(params: AdminAppUpdateRequest) {
  return request.post<any, BaseResponse<boolean>>('/app/admin/update', params)
}

/** 管理员获取应用详情 */
export function adminGetAppById(id: string) {
  return request.get<any, BaseResponse<AppVO>>('/app/admin/get/vo', { params: { id } })
}

// ==================== 对话历史管理 ====================

/** 管理员分页查询所有对话历史 */
export function adminListChatHistory(params: ChatHistoryQueryRequest) {
  return request.post<any, BaseResponse<Page<ChatHistory>>>('/chatHistory/admin/list/page/vo', params)
}
