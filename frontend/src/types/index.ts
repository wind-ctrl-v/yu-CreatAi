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
  id: string
  userAccount: string
  userName?: string
  userAvatar?: string
  userProfile?: string
  userRole: string
  /** 套餐类型：FREE/PRO/PREMIUM/ENTERPRISE/ADMIN */
  planType?: string
  createTime?: string
  updateTime?: string
}

/** 应用创建者信息 */
export interface UserVO {
  id: string
  userAccount: string
  userName?: string
  userAvatar?: string
  userProfile?: string
  userRole: string
  planType?: string
  createTime?: string
}

/** 代码生成类型 */
export type CodeGenType = 'html' | 'multi_file' | 'vue_project'

/** 应用视图对象 */
export interface AppVO {
  id: string
  appName: string
  cover?: string
  initPrompt: string
  codeGenType: CodeGenType | string
  deployKey?: string
  deployedTime?: string
  priority?: number
  userId: string
  createTime?: string
  updateTime?: string
  user?: UserVO
}

/** 对话历史记录 */
export interface ChatHistory {
  id: string
  message: string
  messageType: 'user' | 'ai'
  appId: string
  userId: string
  createTime?: string
  updateTime?: string
}

/** 应用查询请求 */
export interface AppQueryRequest extends PageRequest {
  id?: string
  appName?: string
  codeGenType?: string
  userId?: string
}

/** 用户查询请求（管理员） */
export interface UserQueryRequest extends PageRequest {
  id?: string
  userName?: string
  userAccount?: string
  userProfile?: string
  userRole?: string
}

/** 用户新增请求（管理员） */
export interface UserAddRequest {
  userName?: string
  userAccount: string
  userAvatar?: string
  userProfile?: string
  userRole?: string
  planType?: string
}

/** 用户更新请求（管理员） */
export interface UserUpdateRequest {
  id: string
  userName?: string
  userAvatar?: string
  userProfile?: string
  userRole?: string
  planType?: string
}

/** 删除请求 */
export interface DeleteRequest {
  id: string
}

/** 对话历史查询请求（管理员） */
export interface ChatHistoryQueryRequest extends PageRequest {
  appId?: string
  userId?: string
  messageType?: string
}

/** 管理员应用更新请求 */
export interface AdminAppUpdateRequest {
  id: string
  appName?: string
  priority?: number
  codeGenType?: string
}

/** 设计指纹实体 */
export interface DesignFingerprint {
  id: string
  styleProfile: string
  schemaVersion: string
  sourceType: string
  sourceId?: string
  status: string
  signature?: string
  confidence?: number
  priority?: number
  tone?: string
  fingerprintJson?: string
  reviewRemark?: string
  createTime?: string
  updateTime?: string
}

/** 指纹分页查询请求（管理员） */
export interface FingerprintQueryRequest extends PageRequest {
  styleProfile?: string
  status?: string
  sourceType?: string
  tone?: string
}

/** 指纹审核操作请求 */
export interface FingerprintReviewRequest {
  id: string
  remark?: string
}

// ==================== 生成流水线结构化 SSE 事件 ====================

/** 生成模式意图/实际值 */
export type GenerationMode = 'low_cost' | 'standard' | 'high_quality'

/** 生成链路类型 */
export type PipelineType = 'V2' | 'V1' | 'CLASSIC'

/**
 * 生成开始事件：后端在 VUE 生成流首部下发，
 * 文本形如 `\n[生成] {json}\n`，json 即本对象。
 */
export interface GenerationStartedEvent {
  event: 'generation_started'
  /** 本次生成全局追踪 ID */
  generationId: string
  /** 实际选择的链路（V2/V1/CLASSIC） */
  pipeline: PipelineType | string
  /** 套餐封顶后实际生效的生成模式 */
  mode: GenerationMode | string
  /** 是否允许降级 */
  fallbackEnabled: boolean
}

/**
 * 链路降级事件：后端在发生 V2→V1 / V2→Classic / V1→Classic 降级时下发，
 * 文本形如 `\n[降级] {json}\n`，json 即本对象。
 */
export interface PipelineFallbackEvent {
  event: 'pipeline_fallback'
  /** 降级前链路 */
  from: PipelineType | string
  /** 降级后链路 */
  to: PipelineType | string
  /** 降级原因摘要 */
  reason: string
}

// ==================== 用户套餐 ====================

/** 套餐中文名映射 */
export const PLAN_LABELS: Record<string, string> = {
  FREE: '免费用户',
  PRO: '普通会员',
  PREMIUM: '高级会员',
  ENTERPRISE: '企业版',
  ADMIN: '管理员',
}

/** 套餐能力（后端 /user/plan 返回） */
export interface PlanCapabilities {
  planType: string
  planName: string
  maxMode: string
  availableModes: string[]
}
