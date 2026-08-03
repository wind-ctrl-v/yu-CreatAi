<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'

withDefaults(
  defineProps<{
    align?: 'left' | 'right'
    placement?: 'bottom' | 'top'
    width?: string
    trigger?: 'click' | 'hover'
  }>(),
  { align: 'right', placement: 'bottom', trigger: 'click' },
)

const open = ref(false)
const root = ref<HTMLElement | null>(null)

function onDocClick(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) open.value = false
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') open.value = false
}

watch(open, (v) => {
  if (v) {
    document.addEventListener('click', onDocClick)
    document.addEventListener('keydown', onKey)
  } else {
    document.removeEventListener('click', onDocClick)
    document.removeEventListener('keydown', onKey)
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onKey)
})
</script>

<template>
  <div
    ref="root"
    class="c-dd"
    @mouseenter="trigger === 'hover' && (open = true)"
    @mouseleave="trigger === 'hover' && (open = false)"
  >
    <div class="c-dd__trigger" @click="trigger === 'click' && (open = !open)">
      <slot name="trigger" :open="open" />
    </div>
    <Transition name="dd">
      <div
        v-if="open"
        class="c-dd__panel"
        :class="[`align-${align}`, `place-${placement}`]"
        :style="width ? { width } : undefined"
        @click="open = false"
      >
        <slot :close="() => (open = false)" />
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.c-dd {
  position: relative;
  display: inline-flex;
}

.c-dd__trigger { display: inline-flex; }

.c-dd__panel {
  position: absolute;
  min-width: 180px;
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

.dd-enter-active,
.dd-leave-active {
  transition:
    opacity var(--t-fast) var(--ease-out),
    transform var(--t-fast) var(--ease-out);
}

.dd-enter-from,
.dd-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.985);
}

.place-top.dd-enter-from,
.place-top.dd-leave-to {
  transform: translateY(4px) scale(0.985);
}
</style>
