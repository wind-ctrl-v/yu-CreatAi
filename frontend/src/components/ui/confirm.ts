import { reactive } from 'vue'

export interface ConfirmOptions {
  title: string
  /** 正文文案，支持多段（每项一行） */
  content?: string | string[]
  okText?: string
  cancelText?: string
  danger?: boolean
  icon?: string
}

interface ConfirmState extends ConfirmOptions {
  open: boolean
  resolve?: (v: boolean) => void
}

export const confirmState = reactive<ConfirmState>({
  open: false,
  title: '',
  content: '',
  okText: '确定',
  cancelText: '取消',
  danger: false,
  icon: undefined,
})

/**
 * 命令式确认弹窗。
 * const ok = await confirm({ title: '删除应用？', danger: true })
 */
export function confirm(options: ConfirmOptions): Promise<boolean> {
  return new Promise((resolve) => {
    confirmState.open = true
    confirmState.title = options.title
    confirmState.content = options.content ?? ''
    confirmState.okText = options.okText ?? '确定'
    confirmState.cancelText = options.cancelText ?? '取消'
    confirmState.danger = options.danger ?? false
    confirmState.icon = options.icon ?? (options.danger ? 'alert-triangle' : 'alert-circle')
    confirmState.resolve = resolve
  })
}

export function settleConfirm(value: boolean) {
  confirmState.open = false
  confirmState.resolve?.(value)
  confirmState.resolve = undefined
}
