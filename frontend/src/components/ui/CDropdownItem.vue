<script setup lang="ts">
import CIcon from './CIcon.vue'

withDefaults(
  defineProps<{
    icon?: string
    danger?: boolean
    disabled?: boolean
    active?: boolean
  }>(),
  { danger: false, disabled: false, active: false },
)

const emit = defineEmits<{ (e: 'click', ev: MouseEvent): void }>()
</script>

<template>
  <button
    class="c-dd-item"
    :class="{ 'is-danger': danger, 'is-disabled': disabled, 'is-active': active }"
    type="button"
    :disabled="disabled"
    @click="emit('click', $event)"
  >
    <CIcon v-if="icon" :name="icon" :size="15" class="c-dd-item__icon" />
    <span class="c-dd-item__label"><slot /></span>
    <CIcon v-if="active" name="check" :size="14" class="c-dd-item__tick" />
  </button>
</template>

<style scoped>
.c-dd-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: var(--r-sm);
  background: transparent;
  font-size: var(--fs-sm);
  color: var(--text-primary);
  text-align: left;
  cursor: pointer;
  transition: background-color var(--t-fast) var(--ease-out);
}

.c-dd-item:hover:not(.is-disabled) { background: var(--bg-hover); }

.c-dd-item__icon { color: var(--text-secondary); flex: none; }
.c-dd-item__label { flex: 1; min-width: 0; }
.c-dd-item__tick { color: var(--text-primary); }

.is-danger { color: var(--danger); }
.is-danger .c-dd-item__icon { color: var(--danger); }
.is-danger:hover:not(.is-disabled) { background: var(--danger-soft); }

.is-disabled { color: var(--text-faint); cursor: not-allowed; }
</style>
