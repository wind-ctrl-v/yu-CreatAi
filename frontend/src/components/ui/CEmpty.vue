<script setup lang="ts">
import CIcon from './CIcon.vue'

withDefaults(
  defineProps<{
    description?: string
    hint?: string
    icon?: string
    size?: 'sm' | 'md' | 'lg'
  }>(),
  { description: '暂无数据', icon: 'inbox', size: 'md' },
)
</script>

<template>
  <div class="c-empty" :class="`s-${size}`">
    <span class="c-empty__icon">
      <CIcon :name="icon" :size="size === 'lg' ? 26 : size === 'sm' ? 18 : 22" :stroke="1.5" />
    </span>
    <p class="c-empty__desc">{{ description }}</p>
    <p v-if="hint" class="c-empty__hint">{{ hint }}</p>
    <div v-if="$slots.default" class="c-empty__action"><slot /></div>
  </div>
</template>

<style scoped>
.c-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 56px 24px;
  text-align: center;
}

.s-sm { padding: 32px 16px; gap: 8px; }
.s-lg { padding: 88px 24px; }

.c-empty__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: var(--r-lg);
  background: var(--n-100);
  border: 1px solid var(--border-subtle);
  color: var(--text-faint);
}

.s-sm .c-empty__icon { width: 38px; height: 38px; border-radius: var(--r-md); }
.s-lg .c-empty__icon { width: 58px; height: 58px; border-radius: var(--r-xl); }

.c-empty__desc {
  font-size: var(--fs-sm);
  font-weight: 500;
  color: var(--text-secondary);
  letter-spacing: var(--tracking-tight);
}

.c-empty__hint {
  font-size: var(--fs-xs);
  color: var(--text-faint);
  max-width: 340px;
  line-height: 1.6;
}

.c-empty__action { margin-top: 6px; }
</style>
