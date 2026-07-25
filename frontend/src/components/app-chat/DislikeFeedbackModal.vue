<script setup lang="ts">
import { DISLIKE_REASONS } from '../../types'

const open = defineModel<boolean>('open', { required: true })
const reason = defineModel<string>('reason', { required: true })
const emit = defineEmits<{ confirm: [] }>()
</script>

<template>
  <a-modal
    v-model:open="open"
    title="点踩反馈"
    ok-text="提交"
    cancel-text="取消"
    @ok="emit('confirm')"
  >
    <p class="hint">告诉 AI 你不喜欢哪里，帮助后续生成更符合你的喜好</p>
    <a-radio-group v-model:value="reason" class="reason-group">
      <a-radio v-for="item in DISLIKE_REASONS" :key="item.value" :value="item.value">
        {{ item.label }}
      </a-radio>
    </a-radio-group>
  </a-modal>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--text-secondary, #666);
}
.reason-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
