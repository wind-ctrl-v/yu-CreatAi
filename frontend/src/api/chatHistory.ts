import request from '../request'
import type { BaseResponse, ChatHistory, Page } from '../types'

/** 分页查询某应用的对话历史（游标查询） */
export function listAppChatHistory(
  appId: number,
  pageSize = 10,
  lastCreateTime?: string,
) {
  return request.get<any, BaseResponse<Page<ChatHistory>>>(
    `/chatHistory/app/${appId}`,
    {
      params: { pageSize, lastCreateTime },
    },
  )
}
