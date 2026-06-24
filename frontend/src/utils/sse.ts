import { API_BASE_URL } from '../request'
import type { GenerationStartedEvent, PipelineFallbackEvent } from '../types'

export interface SSECallbacks {
  /** 收到一个内容片段（已解析出的 d 字段） */
  onChunk: (chunk: string) => void
  /** 流式结束（done 事件） */
  onDone?: () => void
  /** 业务错误（后端通过 business-error 事件下发，如限流、未登录、无权限） */
  onBusinessError?: (err: { code: number; message: string }) => void
  /** 网络/传输层出错 */
  onError?: (err: Event) => void
  /** 生成开始：解析自流首部 `[生成] {json}` 结构化事件 */
  onGenerationStarted?: (event: GenerationStartedEvent) => void
  /** 链路降级：解析自 `[降级] {json}` 结构化事件 */
  onPipelineFallback?: (event: PipelineFallbackEvent) => void
}

/** 结构化事件文本前缀（与后端 GenerationPipelineRouter 保持一致） */
const GENERATION_STARTED_PREFIX = '[生成]'
const PIPELINE_FALLBACK_PREFIX = '[降级]'

/**
 * 从形如 `\n[生成] {json}\n` 的文本中提取并解析 JSON。
 * 用首个 `{` 到末个 `}` 截取，兼容前后的前缀与换行。
 * @returns 解析成功返回对象，失败返回 null
 */
function extractEventJson<T>(text: string): T | null {
  const start = text.indexOf('{')
  const end = text.lastIndexOf('}')
  if (start === -1 || end === -1 || end <= start) return null
  try {
    return JSON.parse(text.slice(start, end + 1)) as T
  } catch (err) {
    console.warn('SSE 结构化事件 JSON 解析失败：', err, text)
    return null
  }
}

/**
 * 连接 AI 代码生成的 SSE 流。
 * 后端：GET /app/chat/gen/code?appId=&message=&mode=
 * 每条数据：data: {"d": "片段"}；结束发送 event: done
 *
 * @param appId     应用 ID
 * @param message   用户消息
 * @param callbacks 回调
 * @param mode      生成模式意图（low_cost/standard/high_quality，可选；后端按套餐封顶）
 * @returns 关闭连接的函数
 */
export function connectChatSSE(
  appId: string,
  message: string,
  callbacks: SSECallbacks,
  mode?: string,
): () => void {
  let url = `${API_BASE_URL}/app/chat/gen/code?appId=${appId}&message=${encodeURIComponent(
    message,
  )}`
  if (mode) {
    url += `&mode=${encodeURIComponent(mode)}`
  }

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
      if (typeof parsed.d !== 'string') return
      const chunk: string = parsed.d
      // 结构化事件分流：命中前缀的 chunk 提取 JSON 解析后分发到对应回调，
      // 不再作为普通文本拼接进气泡；解析失败则安全兜底回退为普通文本。
      if (chunk.includes(GENERATION_STARTED_PREFIX)) {
        const event = extractEventJson<GenerationStartedEvent>(chunk)
        if (event && event.event === 'generation_started') {
          callbacks.onGenerationStarted?.(event)
          return
        }
      } else if (chunk.includes(PIPELINE_FALLBACK_PREFIX)) {
        const event = extractEventJson<PipelineFallbackEvent>(chunk)
        if (event && event.event === 'pipeline_fallback') {
          callbacks.onPipelineFallback?.(event)
          return
        }
      }
      // 普通文本片段（含 [阶段]/[错误] 可读文本）按原逻辑展示
      callbacks.onChunk(chunk)
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
