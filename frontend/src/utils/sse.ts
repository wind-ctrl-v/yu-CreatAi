import { API_BASE_URL } from '../request'

export interface SSECallbacks {
  /** 收到一个内容片段（已解析出的 d 字段） */
  onChunk: (chunk: string) => void
  /** 流式结束（done 事件） */
  onDone?: () => void
  /** 出错 */
  onError?: (err: Event) => void
}

/**
 * 连接 AI 代码生成的 SSE 流。
 * 后端：GET /app/chat/gen/code?appId=&message=
 * 每条数据：data: {"d": "片段"}；结束发送 event: done
 *
 * @returns 关闭连接的函数
 */
export function connectChatSSE(
  appId: number,
  message: string,
  callbacks: SSECallbacks,
): () => void {
  const url = `${API_BASE_URL}/app/chat/gen/code?appId=${appId}&message=${encodeURIComponent(
    message,
  )}`

  // 原生 EventSource，GET 请求会自动携带同源 Cookie
  const es = new EventSource(url, { withCredentials: true })

  let closed = false
  const close = () => {
    if (!closed) {
      closed = true
      es.close()
    }
  }

  es.onmessage = (e: MessageEvent) => {
    if (!e.data) return
    try {
      const parsed = JSON.parse(e.data)
      if (typeof parsed.d === 'string') {
        callbacks.onChunk(parsed.d)
      }
    } catch (err) {
      console.error('SSE 数据解析失败：', err, e.data)
    }
  }

  // 自定义结束事件
  es.addEventListener('done', () => {
    callbacks.onDone?.()
    close()
  })

  es.onerror = (err: Event) => {
    // EventSource 在流正常结束后也可能触发 error，需判断是否已收到 done
    callbacks.onError?.(err)
    close()
  }

  return close
}
