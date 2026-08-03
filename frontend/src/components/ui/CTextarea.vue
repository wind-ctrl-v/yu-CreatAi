<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue?: string
    placeholder?: string
    rows?: number
    maxlength?: number
    disabled?: boolean
    autosize?: boolean
    maxRows?: number
    bare?: boolean
    id?: string
  }>(),
  { rows: 3, disabled: false, autosize: false, maxRows: 10, bare: false },
)

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
  (e: 'submit'): void
  (e: 'focus'): void
  (e: 'blur'): void
}>()

const el = ref<HTMLTextAreaElement | null>(null)

function resize() {
  const node = el.value
  if (!node || !props.autosize) return
  node.style.height = 'auto'
  const lineHeight = parseFloat(getComputedStyle(node).lineHeight || '22')
  const max = lineHeight * props.maxRows
  node.style.height = `${Math.min(node.scrollHeight, max)}px`
  node.style.overflowY = node.scrollHeight > max ? 'auto' : 'hidden'
}

function onInput(e: Event) {
  emit('update:modelValue', (e.target as HTMLTextAreaElement).value)
  nextTick(resize)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && (e.metaKey || e.ctrlKey)) {
    e.preventDefault()
    emit('submit')
  }
}

watch(() => props.modelValue, () => nextTick(resize))
onMounted(resize)

const count = computed(() => (props.modelValue ?? '').length)

defineExpose({ focus: () => el.value?.focus() })
</script>

<template>
  <div class="c-textarea" :class="{ 'is-bare': bare, 'is-disabled': disabled }">
    <textarea
      ref="el"
      :id="id"
      class="c-textarea__el"
      :value="modelValue"
      :rows="rows"
      :placeholder="placeholder"
      :maxlength="maxlength"
      :disabled="disabled"
      @input="onInput"
      @keydown="onKeydown"
      @focus="emit('focus')"
      @blur="emit('blur')"
    />
    <span v-if="maxlength && !bare" class="c-textarea__count">{{ count }} / {{ maxlength }}</span>
  </div>
</template>

<style scoped>
.c-textarea {
  position: relative;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-xs);
  transition:
    border-color var(--t-fast) var(--ease-out),
    box-shadow var(--t-fast) var(--ease-out);
}

.c-textarea:hover:not(.is-disabled):not(.is-bare) {
  border-color: var(--border-strong);
}

.c-textarea:focus-within:not(.is-bare) {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-ring);
}

.c-textarea.is-bare {
  border: none;
  box-shadow: none;
  background: transparent;
  border-radius: 0;
}

.c-textarea.is-disabled {
  background: var(--n-100);
}

.c-textarea__el {
  display: block;
  width: 100%;
  padding: 11px 13px;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  font-family: inherit;
  font-size: var(--fs-sm);
  line-height: 1.6;
  color: var(--text-primary);
  letter-spacing: var(--tracking-tight);
}

.is-bare .c-textarea__el {
  padding: 0;
  font-size: var(--fs-base);
}

.c-textarea__el::placeholder {
  color: var(--text-faint);
}

.c-textarea__el:disabled {
  cursor: not-allowed;
  color: var(--text-muted);
}

.c-textarea__count {
  position: absolute;
  right: 10px;
  bottom: 6px;
  font-size: var(--fs-2xs);
  color: var(--text-faint);
  font-variant-numeric: tabular-nums;
  pointer-events: none;
}
</style>
