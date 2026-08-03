<script setup lang="ts">
import { computed, useSlots } from 'vue'
import CIcon from './CIcon.vue'

type Variant = 'primary' | 'secondary' | 'ghost' | 'text' | 'link' | 'danger' | 'outline'
type Size = 'xs' | 'sm' | 'md' | 'lg'

const props = withDefaults(
  defineProps<{
    variant?: Variant
    size?: Size
    icon?: string
    iconRight?: string
    loading?: boolean
    disabled?: boolean
    block?: boolean
    pill?: boolean
    danger?: boolean
    href?: string
    target?: string
    type?: 'button' | 'submit' | 'reset'
    ariaLabel?: string
  }>(),
  {
    variant: 'secondary',
    size: 'md',
    loading: false,
    disabled: false,
    block: false,
    pill: false,
    danger: false,
    type: 'button',
  },
)

const emit = defineEmits<{ (e: 'click', ev: MouseEvent): void }>()

const slots = useSlots()

const isDisabled = computed(() => props.disabled || props.loading)
const tag = computed(() => (props.href ? 'a' : 'button'))
/** 无文字内容且仅有一个图标时，渲染为正方形图标按钮 */
const iconOnly = computed(() => !slots.default && (!!props.icon || !!props.iconRight))

const classes = computed(() => [
  'c-btn',
  `v-${props.variant}`,
  `s-${props.size}`,
  {
    'is-danger': props.danger,
    'is-block': props.block,
    'is-pill': props.pill,
    'is-loading': props.loading,
    'is-disabled': isDisabled.value,
    'is-icon-only': iconOnly.value,
  },
])

function onClick(ev: MouseEvent) {
  if (isDisabled.value) {
    ev.preventDefault()
    ev.stopPropagation()
    return
  }
  emit('click', ev)
}
</script>

<template>
  <component
    :is="tag"
    :class="classes"
    :type="href ? undefined : type"
    :href="href && !isDisabled ? href : undefined"
    :target="href ? target : undefined"
    :rel="target === '_blank' ? 'noopener noreferrer' : undefined"
    :disabled="href ? undefined : isDisabled"
    :aria-disabled="isDisabled || undefined"
    :aria-label="ariaLabel"
    :aria-busy="loading || undefined"
    @click="onClick"
  >
    <span v-if="loading" class="c-btn__spinner" />
    <CIcon v-else-if="icon" :name="icon" :size="size === 'lg' ? 17 : size === 'xs' ? 13 : 15" />
    <span v-if="$slots.default" class="c-btn__label"><slot /></span>
    <CIcon
      v-if="iconRight && !loading"
      :name="iconRight"
      :size="size === 'lg' ? 17 : size === 'xs' ? 13 : 15"
      class="c-btn__right"
    />
  </component>
</template>

<style scoped>
.c-btn {
  --btn-h: 38px;
  --btn-px: 15px;
  --btn-fs: var(--fs-sm);
  --btn-gap: 7px;
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--btn-gap);
  height: var(--btn-h);
  padding: 0 var(--btn-px);
  border: 1px solid transparent;
  border-radius: var(--r-md);
  font-size: var(--btn-fs);
  font-weight: 500;
  letter-spacing: var(--tracking-tight);
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  user-select: none;
  text-decoration: none;
  background: transparent;
  transition:
    background-color var(--t-fast) var(--ease-out),
    border-color var(--t-fast) var(--ease-out),
    color var(--t-fast) var(--ease-out),
    box-shadow var(--t-fast) var(--ease-out),
    transform var(--t-fast) var(--ease-out);
}

.c-btn:active:not(.is-disabled) {
  transform: scale(0.975);
}

.c-btn.is-block {
  display: flex;
  width: 100%;
}

.c-btn.is-pill {
  border-radius: var(--r-pill);
}

.c-btn__label {
  display: inline-block;
}

/* ── 尺寸 ── */
.s-xs {
  --btn-h: 26px;
  --btn-px: 9px;
  --btn-fs: var(--fs-2xs);
  --btn-gap: 5px;
  border-radius: var(--r-sm);
}

.s-sm {
  --btn-h: 32px;
  --btn-px: 12px;
  --btn-fs: var(--fs-xs);
  --btn-gap: 6px;
  border-radius: var(--r-sm);
}

.s-lg {
  --btn-h: 46px;
  --btn-px: 22px;
  --btn-fs: var(--fs-base);
  --btn-gap: 8px;
  border-radius: var(--r-lg);
}

/* ── 变体：primary（近黑实心） ── */
.v-primary {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-fg);
  box-shadow: var(--shadow-xs);
}

.v-primary:hover:not(.is-disabled) {
  background: var(--accent-hover);
  border-color: var(--accent-hover);
  box-shadow: var(--shadow-sm);
}

.v-primary:active:not(.is-disabled) {
  background: var(--accent-active);
}

/* ── secondary（白底描边，默认） ── */
.v-secondary {
  background: var(--bg-surface);
  border-color: var(--border);
  color: var(--text-primary);
  box-shadow: var(--shadow-xs);
}

.v-secondary:hover:not(.is-disabled) {
  border-color: var(--border-strong);
  background: var(--n-25);
  box-shadow: var(--shadow-sm);
}

.v-secondary:active:not(.is-disabled) {
  background: var(--n-100);
}

/* ── outline（透明底描边） ── */
.v-outline {
  border-color: var(--border);
  color: var(--text-primary);
}

.v-outline:hover:not(.is-disabled) {
  border-color: var(--text-primary);
  background: var(--n-100);
}

/* ── ghost（无边框，hover 出底） ── */
.v-ghost {
  color: var(--text-secondary);
}

.v-ghost:hover:not(.is-disabled) {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.v-ghost:active:not(.is-disabled) {
  background: var(--bg-active);
}

/* ── text（纯文本） ── */
.v-text {
  color: var(--text-secondary);
  padding: 0 8px;
}

.v-text:hover:not(.is-disabled) {
  color: var(--text-strong);
  background: var(--bg-hover);
}

/* ── link ── */
.v-link {
  color: var(--text-primary);
  padding: 0;
  height: auto;
  font-weight: 500;
}

.v-link:hover:not(.is-disabled) {
  color: var(--text-strong);
  text-decoration: underline;
  text-underline-offset: 3px;
  text-decoration-thickness: 1px;
}

/* ── danger（描边告警按钮） ── */
.v-danger {
  background: var(--bg-surface);
  border-color: var(--danger-border);
  color: var(--danger);
  box-shadow: var(--shadow-xs);
}

.v-danger:hover:not(.is-disabled) {
  background: var(--danger-soft);
  border-color: var(--danger);
}

/* ── danger 修饰符：与各变体组合，仅改变色彩语义 ── */
.v-primary.is-danger {
  background: var(--danger);
  border-color: var(--danger);
  color: #fff;
}

.v-primary.is-danger:hover:not(.is-disabled) {
  background: #a93526;
  border-color: #a93526;
}

.v-secondary.is-danger,
.v-outline.is-danger {
  border-color: var(--danger-border);
  color: var(--danger);
}

.v-secondary.is-danger:hover:not(.is-disabled),
.v-outline.is-danger:hover:not(.is-disabled) {
  background: var(--danger-soft);
  border-color: var(--danger);
}

.v-ghost.is-danger,
.v-text.is-danger,
.v-link.is-danger {
  color: var(--danger);
}

.v-ghost.is-danger:hover:not(.is-disabled),
.v-text.is-danger:hover:not(.is-disabled) {
  background: var(--danger-soft);
  color: var(--danger);
}

/* ── 禁用 ── */
.c-btn.is-disabled {
  cursor: not-allowed;
  opacity: 0.5;
  box-shadow: none;
  pointer-events: auto;
}

/* ── 图标按钮 ── */
.is-icon-only:not(.is-block) {
  padding: 0;
  width: var(--btn-h);
}

.is-icon-only.s-lg { width: 46px; }

/* ── 加载指示 ── */
.c-btn__spinner {
  width: 13px;
  height: 13px;
  flex: none;
  border: 1.8px solid currentColor;
  border-top-color: transparent;
  border-right-color: transparent;
  border-radius: 50%;
  opacity: 0.85;
  animation: spin 0.7s linear infinite;
}

.v-primary .c-btn__spinner {
  border-color: rgba(255, 255, 255, 0.9);
  border-top-color: transparent;
  border-right-color: transparent;
}
</style>
