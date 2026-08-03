<script setup lang="ts">
import CModal from '../ui/CModal.vue'
import CRadioGroup from '../ui/CRadioGroup.vue'
import { DISLIKE_REASONS } from '../../types'

const open = defineModel<boolean>('open', { required: true })
const reason = defineModel<string>('reason', { required: true })
const emit = defineEmits<{ confirm: [] }>()

const options = DISLIKE_REASONS.map((item) => ({ label: item.label, value: item.value }))
</script>

<template>
  <CModal
    v-model:open="open"
    title="反馈这次结果"
    description="告诉 AI 你不喜欢哪里，帮助后续生成更符合你的偏好。"
    icon="thumbs-down"
    ok-text="提交反馈"
    width="440px"
    @ok="emit('confirm')"
  >
    <CRadioGroup v-model="reason" :options="options" card />
  </CModal>
</template>
