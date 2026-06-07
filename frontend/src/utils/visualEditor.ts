/**
 * 可视化元素选择器
 *
 * 在“实时预览”的 iframe 中开启「选择元素」模式：
 * - 鼠标悬停时高亮目标元素；
 * - 点击选中某个元素，并回调其定位信息（标签、选择器、文本、HTML 片段）。
 *
 * 预览页面与主站经 Vite 代理为同源，因此可直接访问 iframe.contentDocument
 * 并注入事件监听与高亮样式（iframe 需带 allow-same-origin）。
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

const HIGHLIGHT_STYLE_ID = '__visual_editor_style__'
const HOVER_CLASS = '__ve_hover__'
const SELECTED_CLASS = '__ve_selected__'

/** 生成元素的 CSS 选择器路径 */
function buildSelector(el: Element): string {
  const parts: string[] = []
  let node: Element | null = el
  while (node && node.nodeType === 1 && node.tagName.toLowerCase() !== 'html') {
    let part = node.tagName.toLowerCase()
    if (node.id) {
      part = `#${CSS.escape(node.id)}`
      parts.unshift(part)
      break
    }
    const classes = (node.getAttribute('class') || '')
      .trim()
      .split(/\s+/)
      .filter((c) => c && c !== HOVER_CLASS && c !== SELECTED_CLASS)
    if (classes.length) {
      part += '.' + classes.slice(0, 2).map((c) => CSS.escape(c)).join('.')
    }
    const parent: Element | null = node.parentElement
    if (parent) {
      const sameTag = Array.from(parent.children).filter(
        (c) => c.tagName === node!.tagName,
      )
      if (sameTag.length > 1) {
        part += `:nth-of-type(${sameTag.indexOf(node) + 1})`
      }
    }
    parts.unshift(part)
    node = node.parentElement
  }
  return parts.join(' > ')
}

function describe(el: Element): PickedElement {
  const text = (el.textContent || '').replace(/\s+/g, ' ').trim()
  const html = (el as HTMLElement).outerHTML || ''
  return {
    tagName: el.tagName.toLowerCase(),
    id: el.id || undefined,
    className: (el.getAttribute('class') || '').trim() || undefined,
    selector: buildSelector(el),
    text: text ? text.slice(0, 80) : undefined,
    htmlSnippet: html.length > 600 ? html.slice(0, 600) + '…' : html,
  }
}

/**
 * 在 iframe 上启用元素选择模式。
 * @returns 取消选择模式的清理函数；若 iframe 不可访问（跨域）则返回 null。
 */
export function attachElementPicker(
  iframe: HTMLIFrameElement,
  onPick: (picked: PickedElement) => void,
): (() => void) | null {
  let doc: Document | null = null
  try {
    doc = iframe.contentDocument || iframe.contentWindow?.document || null
  } catch {
    doc = null
  }
  if (!doc || !doc.body) return null

  const d = doc

  // 注入高亮样式
  if (!d.getElementById(HIGHLIGHT_STYLE_ID)) {
    const style = d.createElement('style')
    style.id = HIGHLIGHT_STYLE_ID
    style.textContent = `
      .${HOVER_CLASS} { outline: 2px dashed #7c5cff !important; outline-offset: 1px !important; cursor: pointer !important; }
      .${SELECTED_CLASS} { outline: 2px solid #7c5cff !important; outline-offset: 1px !important; box-shadow: 0 0 0 4px rgba(124,92,255,0.15) !important; }
    `
    d.head.appendChild(style)
  }

  let hovered: Element | null = null
  let selected: Element | null = null

  const clearHover = () => {
    if (hovered && hovered !== selected) hovered.classList.remove(HOVER_CLASS)
    hovered = null
  }

  const onMouseOver = (e: Event) => {
    const target = e.target as Element
    if (!target || target === d.body || target === d.documentElement) return
    clearHover()
    hovered = target
    if (target !== selected) target.classList.add(HOVER_CLASS)
  }

  const onMouseOut = () => clearHover()

  const onClick = (e: Event) => {
    e.preventDefault()
    e.stopPropagation()
    const target = e.target as Element
    if (!target || target === d.body || target === d.documentElement) return
    if (selected) selected.classList.remove(SELECTED_CLASS)
    selected = target
    target.classList.remove(HOVER_CLASS)
    target.classList.add(SELECTED_CLASS)
    onPick(describe(target))
  }

  d.addEventListener('mouseover', onMouseOver, true)
  d.addEventListener('mouseout', onMouseOut, true)
  d.addEventListener('click', onClick, true)

  // 清理函数
  return () => {
    try {
      d.removeEventListener('mouseover', onMouseOver, true)
      d.removeEventListener('mouseout', onMouseOut, true)
      d.removeEventListener('click', onClick, true)
      if (hovered) hovered.classList.remove(HOVER_CLASS)
      if (selected) selected.classList.remove(SELECTED_CLASS)
      d.getElementById(HIGHLIGHT_STYLE_ID)?.remove()
    } catch {
      /* iframe 可能已卸载，忽略 */
    }
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
