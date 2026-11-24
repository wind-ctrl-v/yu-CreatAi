import { API_BASE_URL } from '../request'
import type { GenerationStartedEvent, PipelineFallbackEvent, TokenUsageEvent } from '../types'

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
  /** 某一步模型调用完成，载荷是该步骤和整次生成的累计 token */
  onTokenUsage?: (event: TokenUsageEvent) => void
  /** 重连中：retriesLeft 为剩余重试次数，delayMs 为等待毫秒数 */
  onReconnecting?: (retriesLeft: number, delayMs: number) => void
  /** 重连成功（重新建立 SSE 后触发） */
  onReconnected?: () => void
  /** 生成已成功，后端开始本地部署（写 deployKey 并截封面） */
  onAutoDeployStarted?: () => void
  /** 本地自动部署失败，生成结果仍保留 */
  onAutoDeployFailed?: (message: string) => void
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
 * 当前旧接口是带副作用的 GET，网络中断时禁止自动重放，避免重复生成和扣额。
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

    bindAutoDeployEvents(source, callbacks)
    bindTokenUsage(source, callbacks.onTokenUsage)

    source.addEventListener('done', () => {
      doneReceived = true
      if (!businessErrored) callbacks.onDone?.()
      finalClose()
    })

    source.onerror = (err: Event) => {
      if (doneReceived || closed) { finalClose(); return }
      callbacks.onError?.(err)
      finalClose()
    }
  }

  es = new EventSource(url, { withCredentials: true })
  bindEvents(es)

  return finalClose
}

/**
 * 增量编辑 SSE 回调接口
 */
export interface IncrementalEditCallbacks {
  /** AI 文本响应片段 */
  onText: (text: string) => void
  /** 工具调用请求（AI 决定调用工具），带 id 用于去重 */
  onToolCall: (id: string, toolName: string, args: Record<string, unknown>) => void
  /** 工具执行完成 */
  onToolExecuted: (id: string, toolName: string, args: Record<string, unknown>, result: string) => void
  /** 发生错误 */
  onError: (message: string) => void
  /** 流式结束 */
  onDone: () => void
  /** 编辑已写出新页面，后端开始本地部署 */
  onAutoDeployStarted?: () => void
  /** 本地自动部署失败，修改结果仍保留 */
  onAutoDeployFailed?: (message: string) => void
  /** 增量编辑的模型调用完成 */
  onTokenUsage?: (event: TokenUsageEvent) => void
}

/**
 * 连接增量编辑的 SSE 流
 */
export function connectIncrementalEditSSE(
  appId: string,
  instruction: string,
  callbacks: IncrementalEditCallbacks,
): () => void {
  const url = `${API_BASE_URL}/app/incremental-edit-stream?appId=${appId}&instruction=${encodeURIComponent(instruction)}`

  let closed = false
  let doneReceived = false
  let es: EventSource | null = null
  const seenToolCalls = new Set<string>() // 去重：同一 tool_call_id 只触发一次 onToolCall

  const finalClose = () => { closed = true; if (es) { es.close(); es = null } }

  function bindEvents(source: EventSource) {
    source.onmessage = (e: MessageEvent) => {
      if (!e.data) return
      try {
        const wrapper = JSON.parse(e.data)
        if (typeof wrapper.d !== 'string') return
        const msg = JSON.parse(wrapper.d)

        switch (msg.type) {
          case 'ai_response':
            callbacks.onText(msg.data || '')
            break
          case 'tool_request': {
            const id = msg.id || ''
            if (!seenToolCalls.has(id)) {
              seenToolCalls.add(id)
              callbacks.onToolCall(id, msg.name, parseArgs(msg.arguments))
            }
            break
          }
          case 'tool_executed':
            callbacks.onToolExecuted(msg.id || '', msg.name, parseArgs(msg.arguments), msg.result || '')
            break
          case 'error':
            callbacks.onError(msg.message || '未知错误')
            break
        }
      } catch {
        try {
          const wrapper = JSON.parse(e.data)
          if (typeof wrapper.d === 'string') callbacks.onText(wrapper.d)
        } catch { /* ignore */ }
      }
    }

    bindAutoDeployEvents(source, callbacks)
    bindTokenUsage(source, callbacks.onTokenUsage)

    source.addEventListener('done', () => {
      doneReceived = true
      callbacks.onDone()
      finalClose()
    })

    source.onerror = () => {
      if (doneReceived || closed) { finalClose(); return }
      callbacks.onError('连接中断')
      finalClose()
    }
  }

  es = new EventSource(url, { withCredentials: true })
  bindEvents(es)

  return finalClose
}

const AUTO_DEPLOY_WARNING = '自动部署失败，请手动点击「部署」'

function bindAutoDeployEvents(
  source: EventSource,
  callbacks: {
    onAutoDeployStarted?: () => void
    onAutoDeployFailed?: (message: string) => void
  },
) {
  source.addEventListener('auto-deploy-started', () => {
    callbacks.onAutoDeployStarted?.()
  })
  source.addEventListener('auto-deploy-failed', (e: Event) => {
    const me = e as MessageEvent
    let text = AUTO_DEPLOY_WARNING
    try {
      const parsed = JSON.parse(me.data)
      if (typeof parsed.message === 'string' && parsed.message) text = parsed.message
    } catch {
      /* 使用默认提示 */
    }
    callbacks.onAutoDeployFailed?.(text)
  })
}

function bindTokenUsage(
  source: EventSource,
  onTokenUsage?: (event: TokenUsageEvent) => void,
) {
  source.addEventListener('token-usage', (e: Event) => {
    const me = e as MessageEvent
    if (!me.data) return
    try {
      const parsed = JSON.parse(me.data) as TokenUsageEvent
      if (parsed.event === 'token_usage') onTokenUsage?.(parsed)
    } catch (err) {
      console.warn('token-usage 事件解析失败：', err, me.data)
    }
  })
}

function parseArgs(args: string): Record<string, unknown> {
  try {
    return JSON.parse(args)
  } catch {
    return {}
  }
}
