import { API_BASE_URL } from '../request'

export interface SSECallbacks {
  /** 收到一个内容片段（已解析出的 d 字段） */
  onChunk: (chunk: string) => void
  /** 流式结束（done 事件） */
  onDone?: () => void
  /** 业务错误（后端通过 business-error 事件下发，如限流、未登录、无权限） */
  onBusinessError?: (err: { code: number; message: string }) => void
  /** 网络/传输层出错 */
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
  // 是否已正常收到结束事件，用于区分「正常结束」与「真正异常」
  let doneReceived = false
  // 是否已收到后端下发的业务错误，收到后不再触发 onDone
  let businessErrored = false
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

  // 后端业务错误事件（限流、未登录、无权限等）
  es.addEventListener('business-error', (e: Event) => {
    businessErrored = true
    const me = e as MessageEvent
    let payload = { code: -1, message: '生成失败，请稍后重试' }
    try {
      const parsed = JSON.parse(me.data)
      payload = {
        code: Number(parsed.code) || -1,
        message: parsed.message || payload.message,
      }
    } catch (err) {
      console.error('SSE 业务错误解析失败：', err, me.data)
    }
    callbacks.onBusinessError?.(payload)
  })

  // 自定义结束事件
  es.addEventListener('done', () => {
    doneReceived = true
    // 业务错误后也会收到 done，此时不应再当作正常完成处理
    if (!businessErrored) callbacks.onDone?.()
    close()
  })

  es.onerror = (err: Event) => {
    // EventSource 在流正常结束后也可能触发 error；
    // 若已收到 done 事件则视为正常结束，不再上报错误，避免误报「生成中断」。
    if (doneReceived || closed) {
      close()
      return
    }
    callbacks.onError?.(err)
    close()
  }

  return close
}
