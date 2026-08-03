import { reactive } from 'vue'

export type ToastTone = 'success' | 'error' | 'warning' | 'info' | 'loading'

export interface ToastItem {
  id: number
  tone: ToastTone
  content: string
  duration: number
}

let seed = 0
const timers = new Map<number, ReturnType<typeof setTimeout>>()

export const toasts = reactive<ToastItem[]>([])

export function dismiss(id: number) {
  const idx = toasts.findIndex((t) => t.id === id)
  if (idx > -1) toasts.splice(idx, 1)
  const timer = timers.get(id)
  if (timer) {
    clearTimeout(timer)
    timers.delete(id)
  }
}

function push(tone: ToastTone, content: string, duration?: number) {
  const id = ++seed
  const ms = duration ?? (tone === 'loading' ? 0 : tone === 'error' ? 4000 : 2600)
  toasts.push({ id, tone, content, duration: ms })
  if (toasts.length > 5) dismiss(toasts[0].id)
  if (ms > 0) timers.set(id, setTimeout(() => dismiss(id), ms))
  return () => dismiss(id)
}

/**
 * 全局轻提示。用法：message.success('已保存')
 * loading 类型返回关闭函数：const close = message.loading('处理中'); close()
 */
export const message = {
  success: (content: string, duration?: number) => push('success', content, duration),
  error: (content: string, duration?: number) => push('error', content, duration),
  warning: (content: string, duration?: number) => push('warning', content, duration),
  info: (content: string, duration?: number) => push('info', content, duration),
  loading: (content: string, duration?: number) => push('loading', content, duration ?? 0),
}
