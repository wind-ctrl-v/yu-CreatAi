<script setup lang="ts">
export interface RadioItem {
  label: string
  value: string | number
  desc?: string
}

const props = defineProps<{
  modelValue?: string | number
  options: RadioItem[]
  name?: string
  direction?: 'vertical' | 'horizontal'
  card?: boolean
}>()

const emit = defineEmits<{ (e: 'update:modelValue', v: string | number): void }>()

const groupName = props.name ?? `radio-${Math.random().toString(36).slice(2, 9)}`
</script>

<template>
  <div
    class="c-radio-group"
    :class="[direction === 'horizontal' ? 'is-row' : 'is-col', { 'is-card': card }]"
    role="radiogroup"
  >
    <label
      v-for="opt in options"
      :key="opt.value"
      class="c-radio"
      :class="{ 'is-checked': opt.value === modelValue }"
    >
      <input
        class="c-radio__input"
        type="radio"
        :name="groupName"
        :value="opt.value"
        :checked="opt.value === modelValue"
        @change="emit('update:modelValue', opt.value)"
      />
      <span class="c-radio__mark" aria-hidden="true" />
      <span class="c-radio__body">
        <span class="c-radio__label">{{ opt.label }}</span>
        <span v-if="opt.desc" class="c-radio__desc">{{ opt.desc }}</span>
      </span>
    </label>
  </div>
</template>

<style scoped>
.c-radio-group {
  display: flex;
  gap: 8px;
}

.is-col { flex-direction: column; }
.is-row { flex-direction: row; flex-wrap: wrap; gap: 16px; }

.c-radio {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  cursor: pointer;
  font-size: var(--fs-sm);
  color: var(--text-primary);
  user-select: none;
}

.is-card .c-radio {
  padding: 11px 13px;
  border: 1px solid var(--border);
  border-radius: var(--r-md);
  background: var(--bg-surface);
  transition: all var(--t-fast) var(--ease-out);
}

.is-card .c-radio:hover { border-color: var(--border-strong); background: var(--n-25); }

.is-card .c-radio.is-checked {
  border-color: var(--accent);
  box-shadow: 0 0 0 1px var(--accent);
}

.c-radio__input {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
}

.c-radio__mark {
  position: relative;
  flex: none;
  width: 16px;
  height: 16px;
  margin-top: 2px;
  border: 1.5px solid var(--border-strong);
  border-radius: 50%;
  background: var(--bg-surface);
  transition: all var(--t-fast) var(--ease-out);
}

.c-radio:hover .c-radio__mark { border-color: var(--text-muted); }

.c-radio.is-checked .c-radio__mark {
  border-color: var(--accent);
  border-width: 5px;
}

.c-radio__input:focus-visible + .c-radio__mark {
  box-shadow: 0 0 0 3px var(--accent-ring);
}

.c-radio__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.c-radio__label { line-height: 1.45; }

.c-radio__desc {
  font-size: var(--fs-2xs);
  color: var(--text-muted);
  line-height: 1.5;
}
</style>
