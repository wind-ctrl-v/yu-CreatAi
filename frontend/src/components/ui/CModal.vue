<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import CButton from './CButton.vue'
import CIcon from './CIcon.vue'

const props = withDefaults(
  defineProps<{
    open: boolean
    title?: string
    description?: string
    width?: string
    okText?: string
    cancelText?: string
    okLoading?: boolean
    okDisabled?: boolean
    danger?: boolean
    hideFooter?: boolean
    closable?: boolean
    maskClosable?: boolean
    icon?: string
  }>(),
  {
    width: '480px',
    okText: '确定',
    cancelText: '取消',
    okLoading: false,
    okDisabled: false,
    danger: false,
    hideFooter: false,
    closable: true,
    maskClosable: true,
  },
)

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'ok'): void
  (e: 'cancel'): void
}>()

function close() {
  emit('update:open', false)
  emit('cancel')
}

function onMask() {
  if (props.maskClosable) close()
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && props.open) close()
}

watch(
  () => props.open,
  (v) => {
    if (typeof document === 'undefined') return
    document.body.style.overflow = v ? 'hidden' : ''
    if (v) document.addEventListener('keydown', onKey)
    else document.removeEventListener('keydown', onKey)
  },
)

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKey)
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="c-modal" role="dialog" aria-modal="true">
        <div class="c-modal__mask" @click="onMask" />
        <div class="c-modal__panel" :style="{ width, maxWidth: 'calc(100vw - 32px)' }">
          <button v-if="closable" class="c-modal__close" type="button" aria-label="关闭" @click="close">
            <CIcon name="x" :size="16" />
          </button>

          <div class="c-modal__head">
            <span v-if="icon" class="c-modal__icon" :class="{ 'is-danger': danger }">
              <CIcon :name="icon" :size="18" />
            </span>
            <div class="c-modal__titles">
              <h3 v-if="title" class="c-modal__title">{{ title }}</h3>
              <p v-if="description" class="c-modal__desc">{{ description }}</p>
            </div>
          </div>

          <div v-if="$slots.default" class="c-modal__body">
            <slot />
          </div>

          <div v-if="!hideFooter" class="c-modal__foot">
            <slot name="footer">
              <CButton variant="secondary" @click="close">{{ cancelText }}</CButton>
              <CButton
                :variant="danger ? 'danger' : 'primary'"
                :loading="okLoading"
                :disabled="okDisabled"
                @click="emit('ok')"
              >
                {{ okText }}
              </CButton>
            </slot>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.c-modal {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.c-modal__mask {
  position: absolute;
  inset: 0;
  background: var(--bg-overlay);
  backdrop-filter: blur(3px);
}

.c-modal__panel {
  position: relative;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-2xl);
  box-shadow: var(--shadow-xl);
  max-height: calc(100vh - 32px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.c-modal__close {
  position: absolute;
  top: 14px;
  right: 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  transition: all var(--t-fast) var(--ease-out);
  z-index: 1;
}

.c-modal__close:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.c-modal__head {
  display: flex;
  gap: 13px;
  padding: 22px 24px 0;
}

.c-modal__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: var(--r-md);
  background: var(--n-100);
  border: 1px solid var(--border-subtle);
  color: var(--text-primary);
}

.c-modal__icon.is-danger {
  background: var(--danger-soft);
  border-color: var(--danger-border);
  color: var(--danger);
}

.c-modal__titles {
  min-width: 0;
  padding-right: 28px;
}

.c-modal__title {
  font-size: var(--fs-lg);
  font-weight: 600;
  letter-spacing: var(--tracking-heading);
  color: var(--text-strong);
}

.c-modal__desc {
  margin-top: 5px;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
  line-height: 1.6;
}

.c-modal__body {
  padding: 18px 24px 0;
  overflow-y: auto;
  flex: 1;
}

.c-modal__foot {
  display: flex;
  justify-content: flex-end;
  gap: 9px;
  padding: 22px 24px;
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity var(--t-base) var(--ease-out);
}

.modal-enter-active .c-modal__panel,
.modal-leave-active .c-modal__panel {
  transition:
    transform var(--t-base) var(--ease-out),
    opacity var(--t-base) var(--ease-out);
}

.modal-enter-from,
.modal-leave-to { opacity: 0; }

.modal-enter-from .c-modal__panel,
.modal-leave-to .c-modal__panel {
  transform: translateY(10px) scale(0.97);
  opacity: 0;
}
</style>
