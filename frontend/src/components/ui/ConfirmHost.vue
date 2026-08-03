<script setup lang="ts">
import { computed } from 'vue'
import CModal from './CModal.vue'
import { confirmState, settleConfirm } from './confirm'

const lines = computed(() => {
  const c = confirmState.content
  if (!c) return []
  return Array.isArray(c) ? c : [c]
})
</script>

<template>
  <CModal
    :open="confirmState.open"
    :title="confirmState.title"
    :ok-text="confirmState.okText"
    :cancel-text="confirmState.cancelText"
    :danger="confirmState.danger"
    :icon="confirmState.icon"
    width="440px"
    @update:open="(v) => !v && settleConfirm(false)"
    @ok="settleConfirm(true)"
    @cancel="settleConfirm(false)"
  >
    <div v-if="lines.length" class="confirm-body">
      <p v-for="(line, i) in lines" :key="i">{{ line }}</p>
    </div>
  </CModal>
</template>

<style scoped>
.confirm-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
  line-height: 1.65;
  padding-left: 49px;
  margin-top: -12px;
}
</style>
