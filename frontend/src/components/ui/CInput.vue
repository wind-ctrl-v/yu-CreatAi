<script setup lang="ts">
import { computed, ref } from 'vue'
import CIcon from './CIcon.vue'

const props = withDefaults(
  defineProps<{
    modelValue?: string | number
    type?: 'text' | 'password' | 'email' | 'number' | 'search'
    placeholder?: string
    disabled?: boolean
    readonly?: boolean
    size?: 'sm' | 'md' | 'lg'
    icon?: string
    clearable?: boolean
    maxlength?: number
    min?: number
    max?: number
    autocomplete?: string
    invalid?: boolean
    id?: string
  }>(),
  { type: 'text', size: 'md', disabled: false, readonly: false, clearable: false, invalid: false },
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: string | number): void
  (e: 'enter'): void
  (e: 'blur'): void
  (e: 'focus'): void
  (e: 'search'): void
}>()

const revealed = ref(false)
const inputEl = ref<HTMLInputElement | null>(null)

const actualType = computed(() => {
  if (props.type === 'password') return revealed.value ? 'text' : 'password'
  if (props.type === 'search') return 'text'
  return props.type
})

const showClear = computed(
  () => props.clearable && !props.disabled && !props.readonly && `${props.modelValue ?? ''}` !== '',
)

function onInput(e: Event) {
  const el = e.target as HTMLInputElement
  emit('update:modelValue', props.type === 'number' ? (el.value === '' ? '' : Number(el.value)) : el.value)
}

function clear() {
  emit('update:modelValue', '')
  inputEl.value?.focus()
}

function onKeyup(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    emit('enter')
    if (props.type === 'search') emit('search')
  }
}

defineExpose({ focus: () => inputEl.value?.focus() })
</script>

<template>
  <div
    class="c-input"
    :class="[`s-${size}`, { 'is-disabled': disabled, 'is-invalid': invalid }]"
  >
    <CIcon v-if="icon" :name="icon" :size="15" class="c-input__lead" />
    <input
      ref="inputEl"
      :id="id"
      class="c-input__el"
      :type="actualType"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      :readonly="readonly"
      :maxlength="maxlength"
      :min="min"
      :max="max"
      :autocomplete="autocomplete"
      :aria-invalid="invalid || undefined"
      @input="onInput"
      @keyup="onKeyup"
      @blur="emit('blur')"
      @focus="emit('focus')"
    />
    <button
      v-if="showClear"
      class="c-input__act"
      type="button"
      tabindex="-1"
      aria-label="清空"
      @click="clear"
    >
      <CIcon name="x" :size="13" />
    </button>
    <button
      v-if="type === 'password'"
      class="c-input__act"
      type="button"
      tabindex="-1"
      :aria-label="revealed ? '隐藏密码' : '显示密码'"
      @click="revealed = !revealed"
    >
      <CIcon :name="revealed ? 'eye-off' : 'eye'" :size="15" />
    </button>
    <button
      v-if="type === 'search'"
      class="c-input__act is-primary"
      type="button"
      aria-label="搜索"
      @click="emit('search')"
    >
      <CIcon name="search" :size="15" />
    </button>
    <slot name="suffix" />
  </div>
</template>

<style scoped>
.c-input {
  --h: 38px;
  display: flex;
  align-items: center;
  gap: 8px;
  height: var(--h);
  padding: 0 12px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-xs);
  transition:
    border-color var(--t-fast) var(--ease-out),
    box-shadow var(--t-fast) var(--ease-out),
    background-color var(--t-fast) var(--ease-out);
}

.c-input:hover:not(.is-disabled) {
  border-color: var(--border-strong);
}

.c-input:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-ring);
}

.c-input.is-invalid {
  border-color: var(--danger);
}

.c-input.is-invalid:focus-within {
  box-shadow: 0 0 0 3px var(--danger-soft);
}

.c-input.is-disabled {
  background: var(--n-100);
  cursor: not-allowed;
  box-shadow: none;
}

.s-sm { --h: 32px; padding: 0 10px; border-radius: var(--r-sm); }
.s-lg { --h: 46px; padding: 0 16px; border-radius: var(--r-lg); }

.c-input__el {
  flex: 1;
  min-width: 0;
  height: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-size: var(--fs-sm);
  color: var(--text-primary);
  letter-spacing: var(--tracking-tight);
}

.s-lg .c-input__el { font-size: var(--fs-base); }

.c-input__el::placeholder {
  color: var(--text-faint);
}

.c-input__el:disabled {
  cursor: not-allowed;
  color: var(--text-muted);
}

.c-input__el::-webkit-outer-spin-button,
.c-input__el::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}

.c-input__lead {
  color: var(--text-faint);
  flex: none;
}

.c-input__act {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex: none;
  padding: 0;
  border: none;
  border-radius: var(--r-xs);
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition: all var(--t-fast) var(--ease-out);
}

.c-input__act:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.c-input__act.is-primary {
  background: var(--accent);
  color: var(--accent-fg);
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  margin-right: -6px;
}

.c-input__act.is-primary:hover {
  background: var(--accent-hover);
  color: var(--accent-fg);
}
</style>
