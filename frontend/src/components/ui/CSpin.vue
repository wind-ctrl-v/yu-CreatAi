<script setup lang="ts">
withDefaults(
  defineProps<{
    spinning?: boolean
    size?: 'sm' | 'md' | 'lg'
    tip?: string
    overlay?: boolean
  }>(),
  { spinning: true, size: 'md', overlay: true },
)
</script>

<template>
  <div v-if="$slots.default" class="c-spin-wrap">
    <div :class="{ 'c-spin-content': true, 'is-blurred': spinning && overlay }">
      <slot />
    </div>
    <Transition name="fade">
      <div v-if="spinning" class="c-spin-mask">
        <span class="c-spin" :class="`s-${size}`" role="status" aria-live="polite" />
        <span v-if="tip" class="c-spin-tip">{{ tip }}</span>
      </div>
    </Transition>
  </div>
  <span v-else-if="spinning" class="c-spin" :class="`s-${size}`" role="status" />
</template>

<style scoped>
.c-spin-wrap {
  position: relative;
  min-height: 60px;
}

.c-spin-content {
  transition: opacity var(--t-base) var(--ease-out), filter var(--t-base) var(--ease-out);
}

.c-spin-content.is-blurred {
  opacity: 0.42;
  pointer-events: none;
  filter: saturate(0.6);
}

.c-spin-mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  z-index: 2;
}

.c-spin {
  display: inline-block;
  width: 20px;
  height: 20px;
  border: 2px solid var(--n-200);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

.s-sm { width: 14px; height: 14px; border-width: 1.6px; }
.s-lg { width: 28px; height: 28px; border-width: 2.4px; }

.c-spin-tip {
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

.fade-enter-active,
.fade-leave-active { transition: opacity var(--t-base) var(--ease-out); }
.fade-enter-from,
.fade-leave-to { opacity: 0; }
</style>
