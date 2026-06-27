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
  /** 重连中：retriesLeft 为剩余重试次数，delayMs 为等待毫秒数 */
  onReconnecting?: (retriesLeft: number, delayMs: number) => void
  /** 重连成功（重新建立 SSE 后触发） */
  onReconnected?: () => void
}

/** SSE 重连配置 */
const MAX_RETRIES = 3
const BASE_DELAY_MS = 2000

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
 * 连接 AI 代码生成的 SSE 流（支持断线自动重连）。
 */
export function connectChatSSE(
  appId: string,
  message: string,
  callbacks: SSECallbacks,
  mode?: string,
): () => void {
  let url = `${API_BASE_URL}/app/chat/gen/code?appId=${appId}&message=${encodeURIComponent(message)}`
  if (mode) url += `&mode=${encodeURIComponent(mode)}`

  let closed = false
  let doneReceived = false
  let businessErrored = false
  let es: EventSource | null = null
  let retriesLeft = MAX_RETRIES

  const dispose = () => { if (es) { es.close(); es = null } }
  const finalClose = () => { closed = true; dispose() }

  function bindEvents(source: EventSource) {
    source.onmessage = (e: MessageEvent) => {
      if (!e.data) return
      try {
        const parsed = JSON.parse(e.data)
        if (typeof parsed.d !== 'string') return
        const chunk: string = parsed.d
        if (chunk.includes(GENERATION_STARTED_PREFIX)) {
          const event = extractEventJson<GenerationStartedEvent>(chunk)
          if (event && event.event === 'generation_started') { callbacks.onGenerationStarted?.(event); return }
        } else if (chunk.includes(PIPELINE_FALLBACK_PREFIX)) {
          const event = extractEventJson<PipelineFallbackEvent>(chunk)
          if (event && event.event === 'pipeline_fallback') { callbacks.onPipelineFallback?.(event); return }
        }
        callbacks.onChunk(chunk)
      } catch (err) { console.error('SSE 解析失败：', err, e.data) }
    }

    source.addEventListener('business-error', (e: Event) => {
      businessErrored = true
      const me = e as MessageEvent
      let payload = { code: -1, message: '生成失败，请稍后重试' }
      try {
        const parsed = JSON.parse(me.data)
        payload = { code: Number(parsed.code) || -1, message: parsed.message || payload.message }
      } catch { /* ignore */ }
      callbacks.onBusinessError?.(payload)
    })

    source.addEventListener('done', () => {
      doneReceived = true
      if (!businessErrored) callbacks.onDone?.()
      finalClose()
    })

    source.onerror = (err: Event) => {
      if (doneReceived || closed) { finalClose(); return }
      dispose()
      if (retriesLeft > 0) {
        retriesLeft--
        const delay = BASE_DELAY_MS * Math.pow(2, MAX_RETRIES - retriesLeft - 1)
        callbacks.onReconnecting?.(retriesLeft, delay)
        setTimeout(() => {
          if (closed) return
          es = new EventSource(url, { withCredentials: true })
          bindEvents(es)
          callbacks.onReconnected?.()
        }, delay)
      } else {
        callbacks.onError?.(err)
        finalClose()
      }
    }
  }

  es = new EventSource(url, { withCredentials: true })
  bindEvents(es)

  return finalClose
}
