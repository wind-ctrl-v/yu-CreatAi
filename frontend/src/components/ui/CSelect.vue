<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import CIcon from './CIcon.vue'

export interface SelectOption {
  label: string
  value: string | number
  desc?: string
  icon?: string
  disabled?: boolean
}

const props = withDefaults(
  defineProps<{
    modelValue?: string | number | undefined
    options: SelectOption[]
    placeholder?: string
    size?: 'sm' | 'md' | 'lg'
    disabled?: boolean
    allowClear?: boolean
    width?: string
    align?: 'left' | 'right'
    /** 面板弹出方向，底部空间不足时用 top */
    placement?: 'bottom' | 'top'
    /** 面板最小宽度，选项含描述时建议放宽 */
    menuWidth?: string
    bare?: boolean
    id?: string
  }>(),
  {
    size: 'md',
    disabled: false,
    allowClear: false,
    align: 'left',
    placement: 'bottom',
    bare: false,
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: string | number | undefined): void
  (e: 'change', v: string | number | undefined): void
}>()

const open = ref(false)
const root = ref<HTMLElement | null>(null)
const activeIndex = ref(-1)

const selected = computed(() => props.options.find((o) => o.value === props.modelValue))

function toggle() {
  if (props.disabled) return
  open.value = !open.value
  if (open.value) {
    activeIndex.value = props.options.findIndex((o) => o.value === props.modelValue)
  }
}

function pick(opt: SelectOption) {
  if (opt.disabled) return
  emit('update:modelValue', opt.value)
  emit('change', opt.value)
  open.value = false
}

function clear(ev: MouseEvent) {
  ev.stopPropagation()
  emit('update:modelValue', undefined)
  emit('change', undefined)
}

function onDocClick(ev: MouseEvent) {
  if (root.value && !root.value.contains(ev.target as Node)) open.value = false
}

function onKeydown(ev: KeyboardEvent) {
  if (props.disabled) return
  if (!open.value && (ev.key === 'Enter' || ev.key === ' ' || ev.key === 'ArrowDown')) {
    ev.preventDefault()
    toggle()
    return
  }
  if (!open.value) return
  if (ev.key === 'Escape') {
    open.value = false
  } else if (ev.key === 'ArrowDown') {
    ev.preventDefault()
    activeIndex.value = Math.min(activeIndex.value + 1, props.options.length - 1)
  } else if (ev.key === 'ArrowUp') {
    ev.preventDefault()
    activeIndex.value = Math.max(activeIndex.value - 1, 0)
  } else if (ev.key === 'Enter') {
    ev.preventDefault()
    const opt = props.options[activeIndex.value]
    if (opt) pick(opt)
  }
}

watch(open, (v) => {
  if (v) document.addEventListener('click', onDocClick)
  else document.removeEventListener('click', onDocClick)
})

onMounted(() => void 0)
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))
</script>

<template>
  <div
    ref="root"
    class="c-select"
    :class="[`s-${size}`, { 'is-open': open, 'is-disabled': disabled, 'is-bare': bare }]"
    :style="width ? { width } : undefined"
  >
    <button
      :id="id"
      class="c-select__trigger"
      type="button"
      :disabled="disabled"
      role="combobox"
      :aria-expanded="open"
      aria-haspopup="listbox"
      @click="toggle"
      @keydown="onKeydown"
    >
      <CIcon v-if="selected?.icon" :name="selected.icon" :size="15" class="c-select__lead" />
      <span class="c-select__value" :class="{ 'is-placeholder': !selected }">
        {{ selected?.label ?? placeholder ?? '请选择' }}
      </span>
      <span
        v-if="allowClear && selected && !disabled"
        class="c-select__clear"
        role="button"
        aria-label="清除"
        @click="clear"
      >
        <CIcon name="x" :size="12" />
      </span>
      <CIcon name="chevron-down" :size="14" class="c-select__arrow" />
    </button>

    <Transition name="pop">
      <ul
        v-if="open"
        class="c-select__menu"
        :class="[`align-${align}`, `place-${placement}`]"
        :style="menuWidth ? { minWidth: `max(100%, ${menuWidth})` } : undefined"
        role="listbox"
      >
        <li
          v-for="(opt, i) in options"
          :key="opt.value"
          class="c-select__opt"
          :class="{
            'is-selected': opt.value === modelValue,
            'is-active': i === activeIndex,
            'is-disabled': opt.disabled,
          }"
          role="option"
          :aria-selected="opt.value === modelValue"
          @click="pick(opt)"
          @mouseenter="activeIndex = i"
        >
          <CIcon v-if="opt.icon" :name="opt.icon" :size="15" class="c-select__opt-icon" />
          <span class="c-select__opt-body">
            <span class="c-select__opt-label">{{ opt.label }}</span>
            <span v-if="opt.desc" class="c-select__opt-desc">{{ opt.desc }}</span>
          </span>
          <CIcon v-if="opt.value === modelValue" name="check" :size="14" class="c-select__tick" />
        </li>
        <li v-if="!options.length" class="c-select__empty">暂无选项</li>
      </ul>
    </Transition>
  </div>
</template>

<style scoped>
.c-select {
  --h: 38px;
  position: relative;
  display: inline-block;
  min-width: 120px;
}

.c-select__trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: var(--h);
  padding: 0 10px 0 12px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-xs);
  cursor: pointer;
  font-size: var(--fs-sm);
  text-align: left;
  color: var(--text-primary);
  transition: all var(--t-fast) var(--ease-out);
}

.s-sm { --h: 32px; }
.s-sm .c-select__trigger { border-radius: var(--r-sm); font-size: var(--fs-xs); }
.s-lg { --h: 46px; }
.s-lg .c-select__trigger { border-radius: var(--r-lg); font-size: var(--fs-base); }

.c-select__trigger:hover:not(:disabled) {
  border-color: var(--border-strong);
}

.is-open .c-select__trigger {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-ring);
}

.is-disabled .c-select__trigger {
  cursor: not-allowed;
  background: var(--n-100);
  color: var(--text-muted);
  box-shadow: none;
}

/* 裸模式：嵌在其他容器（如输入卡工具栏）内 */
.is-bare .c-select__trigger {
  background: transparent;
  border-color: transparent;
  box-shadow: none;
  padding: 0 6px;
}

.is-bare .c-select__trigger:hover:not(:disabled) {
  background: var(--bg-hover);
  border-color: transparent;
}

.is-bare.is-open .c-select__trigger {
  box-shadow: none;
  border-color: transparent;
  background: var(--bg-hover);
}

.c-select__value {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  letter-spacing: var(--tracking-tight);
}

.c-select__value.is-placeholder {
  color: var(--text-faint);
}

.c-select__lead { color: var(--text-secondary); }

.c-select__arrow {
  flex: none;
  color: var(--text-faint);
  transition: transform var(--t-base) var(--ease-out);
}

.is-open .c-select__arrow {
  transform: rotate(180deg);
}

.c-select__clear {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  flex: none;
  border-radius: var(--r-pill);
  color: var(--text-faint);
  transition: all var(--t-fast) var(--ease-out);
}

.c-select__clear:hover {
  background: var(--bg-active);
  color: var(--text-primary);
}

/* ── 下拉面板 ── */
.c-select__menu {
  position: absolute;
  /* 至少与触发器同宽，内容更宽时按内容展开，避免描述文字被挤成多行 */
  min-width: 100%;
  width: max-content;
  max-width: min(300px, calc(100vw - 32px));
  max-height: 288px;
  overflow-y: auto;
  padding: 5px;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-lg);
  z-index: var(--z-dropdown);
}

.place-bottom { top: calc(100% + 6px); }
.place-top { bottom: calc(100% + 6px); }

.align-left { left: 0; }
.align-right { right: 0; }

.c-select__opt {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 8px 10px;
  border-radius: var(--r-sm);
  font-size: var(--fs-sm);
  color: var(--text-primary);
  cursor: pointer;
  transition: background-color var(--t-fast) var(--ease-out);
}

.c-select__opt.is-active {
  background: var(--bg-hover);
}

.c-select__opt.is-selected {
  font-weight: 500;
}

.c-select__opt.is-disabled {
  color: var(--text-faint);
  cursor: not-allowed;
}

.c-select__opt-icon {
  color: var(--text-secondary);
  flex: none;
  margin-top: 2px;
}

.c-select__opt-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.c-select__opt-label {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.c-select__opt-desc {
  font-size: var(--fs-2xs);
  color: var(--text-muted);
  line-height: 1.45;
}

.c-select__tick {
  color: var(--text-primary);
  flex: none;
  margin-top: 3px;
}

.c-select__empty {
  padding: 14px;
  text-align: center;
  font-size: var(--fs-xs);
  color: var(--text-faint);
}

.pop-enter-active,
.pop-leave-active {
  transition:
    opacity var(--t-fast) var(--ease-out),
    transform var(--t-fast) var(--ease-out);
}

.pop-enter-from,
.pop-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.985);
}

.place-top.pop-enter-from,
.place-top.pop-leave-to {
  transform: translateY(4px) scale(0.985);
}
</style>
