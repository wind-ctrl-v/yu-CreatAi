import request from '../request'
import type { BaseResponse } from '../types'

/** 负反馈事件类型 */
export type NegativeEventType =
  | 'UNDO'
  | 'REVERT_TO_GENERATED'
  | 'ABANDON'
  | 'REJECT_SUGGESTION'

/**
 * 显式点踩
 * POST /api/preference/feedback/dislike
 */
export function dislike(params: {
  appId: string
  reason?: string
  tags?: string[]
}) {
  return request.post<any, BaseResponse<void>>('/preference/feedback/dislike', params)
}

/**
 * 通用负反馈事件埋点（UNDO / REVERT / ABANDON / REJECT）
 * POST /api/preference/feedback/event
 */
export function recordNegativeEvent(params: {
  appId: string
  eventType: NegativeEventType
  metadata?: Record<string, unknown>
}) {
  return request.post<any, BaseResponse<void>>('/preference/feedback/event', params)
}
