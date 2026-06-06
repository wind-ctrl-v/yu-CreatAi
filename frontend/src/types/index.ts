/** 后端统一响应体 */
export interface BaseResponse<T> {
  code: number
  data: T
  message: string
}

/** MyBatis-Flex 分页结构 */
export interface Page<T> {
  records: T[]
  pageNumber: number
  pageSize: number
  totalRow: number
  totalPage: number
}

/** 分页查询基础请求 */
export interface PageRequest {
  pageNum?: number
  pageSize?: number
  sortField?: string
  sortOrder?: string
}

/** 当前登录用户信息 */
export interface LoginUserVO {
  id: number
  userAccount: string
  userName?: string
  userAvatar?: string
  userProfile?: string
  userRole: string
  createTime?: string
  updateTime?: string
}

/** 应用创建者信息 */
export interface UserVO {
  id: number
  userAccount: string
  userName?: string
  userAvatar?: string
  userProfile?: string
  userRole: string
  createTime?: string
}

/** 代码生成类型 */
export type CodeGenType = 'html' | 'multi_file' | 'vue_project'

/** 应用视图对象 */
export interface AppVO {
  id: number
  appName: string
  cover?: string
  initPrompt: string
  codeGenType: CodeGenType | string
  deployKey?: string
  deployedTime?: string
  priority?: number
  userId: number
  createTime?: string
  updateTime?: string
  user?: UserVO
}

/** 对话历史记录 */
export interface ChatHistory {
  id: number
  message: string
  messageType: 'user' | 'ai'
  appId: number
  userId: number
  createTime?: string
  updateTime?: string
}

/** 应用查询请求 */
export interface AppQueryRequest extends PageRequest {
  id?: number
  appName?: string
  codeGenType?: string
  userId?: number
}
