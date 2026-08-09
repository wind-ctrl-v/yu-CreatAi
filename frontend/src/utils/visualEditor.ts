/**
 * 可视化元素选择器
 *
 * 主页面与隔离预览通过受约束的 postMessage 协议通信。
 * 预览页负责高亮和选择元素，主页面只接收经过来源与结构校验的描述数据。
 */

export interface PickedElement {
  /** 标签名（小写） */
  tagName: string
  /** 元素 id */
  id?: string
  /** 元素 class 列表（原始字符串） */
  className?: string
  /** 计算得到的 CSS 选择器路径 */
  selector: string
  /** 元素可见文本（截断） */
  text?: string
  /** outerHTML 片段（截断，供 AI 精确定位修改） */
  htmlSnippet?: string
}

const MAX_TEXT_LENGTH = 80
const MAX_HTML_LENGTH = 600
const MAX_SELECTOR_LENGTH = 500

function isPickedElement(value: unknown): value is PickedElement {
  if (!value || typeof value !== 'object') return false
  const item = value as Record<string, unknown>
  return typeof item.tagName === 'string'
    && item.tagName.length <= 30
    && typeof item.selector === 'string'
    && item.selector.length <= MAX_SELECTOR_LENGTH
    && (item.text === undefined || (typeof item.text === 'string' && item.text.length <= MAX_TEXT_LENGTH))
    && (item.htmlSnippet === undefined
      || (typeof item.htmlSnippet === 'string' && item.htmlSnippet.length <= MAX_HTML_LENGTH))
}

/**
 * 通过受校验的 postMessage 协议启用元素选择。
 * 预览页未实现协议或使用不透明 sandbox Origin 时安全降级为不可用。
 */
export function attachElementPicker(
  iframe: HTMLIFrameElement,
  onPick: (picked: PickedElement) => void,
): (() => void) | null {
  const targetWindow = iframe.contentWindow
  if (!targetWindow || !iframe.src) return null

  let expectedOrigin: string
  try {
    expectedOrigin = new URL(iframe.src, window.location.href).origin
  } catch {
    return null
  }

  const onMessage = (event: MessageEvent) => {
    // 只校验消息来源是否为目标 iframe（origin在 allow-same-origin 前可能为 null，不强校验）
    if (event.source !== targetWindow) return
    const message = event.data as { type?: unknown; payload?: unknown } | null
    if (!message || message.type !== 'creatai:element-picked' || !isPickedElement(message.payload)) return
    onPick(message.payload)
  }

  window.addEventListener('message', onMessage)
  targetWindow.postMessage({ type: 'creatai:picker-enable' }, expectedOrigin)

  return () => {
    window.removeEventListener('message', onMessage)
    targetWindow.postMessage({ type: 'creatai:picker-disable' }, expectedOrigin)
  }
}

/** 把选中元素整理为给 AI 的修改上下文前缀 */
export function buildEditContext(picked: PickedElement): string {
  const lines: string[] = ['【可视化修改 - 目标元素】']
  lines.push(`- 选择器：${picked.selector}`)
  if (picked.text) lines.push(`- 文本：${picked.text}`)
  if (picked.htmlSnippet) lines.push(`- HTML：${picked.htmlSnippet}`)
  lines.push('【修改要求】')
  return lines.join('\n')
}
