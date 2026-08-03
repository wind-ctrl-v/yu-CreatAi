<script setup lang="ts">
import CIcon from './CIcon.vue'
import { dismiss, toasts, type ToastTone } from './toast'

const ICON: Record<ToastTone, string> = {
  success: 'check-circle',
  error: 'x-circle',
  warning: 'alert-triangle',
  info: 'info',
  loading: 'refresh',
}
</script>

<template>
  <Teleport to="body">
    <div class="toast-host" role="region" aria-live="polite">
      <TransitionGroup name="toast">
        <div
          v-for="t in toasts"
          :key="t.id"
          class="toast"
          :class="`is-${t.tone}`"
          @click="dismiss(t.id)"
        >
          <CIcon :name="ICON[t.tone]" :size="15" :spin="t.tone === 'loading'" class="toast__icon" />
          <span class="toast__text">{{ t.content }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-host {
  position: fixed;
  top: 18px;
  left: 50%;
  transform: translateX(-50%);
  z-index: var(--z-message);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  pointer-events: none;
  width: max-content;
  max-width: calc(100vw - 32px);
}

.toast {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 10px 15px;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-pill);
  box-shadow: var(--shadow-lg);
  font-size: var(--fs-sm);
  font-weight: 450;
  color: var(--text-primary);
  letter-spacing: var(--tracking-tight);
  pointer-events: auto;
  cursor: pointer;
  max-width: 100%;
}

.toast__text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.toast__icon { flex: none; }

.is-success .toast__icon { color: var(--success); }
.is-error .toast__icon { color: var(--danger); }
.is-warning .toast__icon { color: var(--warning); }
.is-info .toast__icon { color: var(--info); }
.is-loading .toast__icon { color: var(--text-muted); }

.is-error { border-color: var(--danger-border); }

.toast-enter-active,
.toast-leave-active {
  transition:
    opacity var(--t-base) var(--ease-out),
    transform var(--t-base) var(--ease-spring);
}

.toast-enter-from {
  opacity: 0;
  transform: translateY(-12px) scale(0.95);
}

.toast-leave-to {
  opacity: 0;
  transform: translateY(-6px) scale(0.97);
}

.toast-leave-active { position: absolute; }
</style>
