<script setup lang="ts">
import CIcon from './CIcon.vue'

withDefaults(
  defineProps<{
    tone?: 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'violet' | 'spark' | 'solid'
    size?: 'sm' | 'md'
    icon?: string
    dot?: boolean
    closable?: boolean
  }>(),
  { tone: 'neutral', size: 'md', dot: false, closable: false },
)

const emit = defineEmits<{ (e: 'close'): void }>()
</script>

<template>
  <span class="c-tag" :class="[`t-${tone}`, `s-${size}`]">
    <span v-if="dot" class="c-tag__dot" />
    <CIcon v-if="icon" :name="icon" :size="size === 'sm' ? 11 : 12" />
    <slot />
    <button v-if="closable" class="c-tag__x" type="button" aria-label="移除" @click="emit('close')">
      <CIcon name="x" :size="10" />
    </button>
  </span>
</template>

<style scoped>
.c-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0 8px;
  height: 22px;
  border: 1px solid transparent;
  border-radius: var(--r-xs);
  font-size: var(--fs-2xs);
  font-weight: 500;
  letter-spacing: var(--tracking-tight);
  line-height: 1;
  white-space: nowrap;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.s-sm { height: 19px; padding: 0 6px; font-size: 11px; }

.c-tag__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}

.c-tag__x {
  display: inline-flex;
  align-items: center;
  margin-right: -3px;
  padding: 2px;
  border: none;
  background: transparent;
  color: inherit;
  opacity: 0.6;
  cursor: pointer;
  border-radius: var(--r-xs);
}

.c-tag__x:hover { opacity: 1; }

.t-neutral {
  background: var(--n-100);
  border-color: var(--border-subtle);
  color: var(--text-secondary);
}

.t-solid {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-fg);
}

.t-info { background: var(--info-soft); border-color: var(--info-border); color: var(--info); }
.t-success { background: var(--success-soft); border-color: var(--success-border); color: var(--success); }
.t-warning { background: var(--warning-soft); border-color: var(--warning-border); color: var(--warning); }
.t-danger { background: var(--danger-soft); border-color: var(--danger-border); color: var(--danger); }
.t-violet { background: var(--violet-soft); border-color: var(--violet-border); color: var(--violet); }
.t-spark { background: var(--spark-soft); border-color: var(--spark-border); color: var(--spark); }
</style>
