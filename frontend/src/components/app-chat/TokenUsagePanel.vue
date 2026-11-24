<script setup lang="ts">
import type { GenerationTokenUsage, StepTokenUsage } from '../../types'

defineProps<{
  usage: GenerationTokenUsage | null
  active?: boolean
}>()

const STEP_LABELS: Record<string, string> = {
  PlanningAgent: '规划',
  DynamicSpecSynthesizer: '设计合成',
  PageGenerateAgent: '页面生成',
  CoderAgent: '代码生成',
  StyleAgent: '样式',
  CriticAgent: '质检',
  ClassicHtml: 'HTML 生成',
  ClassicMultiFile: '多文件生成',
  ClassicVue: 'Vue 生成',
  IncrementalEdit: '增量编辑',
  unattributed: '未归属调用',
}

function stepLabel(step: string): string {
  return STEP_LABELS[step] || step
}

function formatTokens(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '—'
  return value.toLocaleString('zh-CN')
}

function stepTitle(step: StepTokenUsage): string {
  const model = step.model ? ` · ${step.model}` : ''
  return `${step.step}${model}`
}
</script>

<template>
  <section class="tokens" aria-label="Token 用量">
    <header class="tokens__head">
      <span class="tokens__title">Token 用量</span>
      <span v-if="usage?.generationId" class="tokens__id mono" :title="usage.generationId">
        {{ usage.generationId }}
      </span>
      <span v-else-if="active" class="tokens__id">统计中</span>
    </header>

    <p v-if="!usage?.steps?.length" class="tokens__wait">
      {{ active ? '等待模型返回用量…' : '这次运行还没有模型调用' }}
    </p>

    <ul v-else class="tokens__list">
      <li v-for="step in usage.steps" :key="step.step" class="tokens__row" :title="stepTitle(step)">
        <span class="tokens__name">
          <span>{{ stepLabel(step.step) }}</span>
          <span class="tokens__code mono">{{ step.step }}</span>
        </span>
        <span class="tokens__nums mono">
          入 {{ formatTokens(step.inputTokens) }}
          出 {{ formatTokens(step.outputTokens) }}
          总 {{ formatTokens(step.totalTokens) }}
          · {{ step.callCount }} 次
        </span>
      </li>
      <li class="tokens__row is-total">
        <span class="tokens__name">合计</span>
        <span class="tokens__nums mono">
          入 {{ formatTokens(usage.inputTokens) }}
          出 {{ formatTokens(usage.outputTokens) }}
          总 {{ formatTokens(usage.totalTokens) }}
          · {{ usage.callCount }} 次
        </span>
      </li>
    </ul>

    <p v-if="usage?.unknownUsageCalls" class="tokens__hint">
      有 {{ usage.unknownUsageCalls }} 次调用上游未返回 token，未计入上面的数字。
    </p>
  </section>
</template>

<style scoped>
.tokens {
  padding: 10px 12px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-md);
  background: var(--bg-surface);
  box-shadow: var(--shadow-xs);
}

.tokens__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.tokens__title {
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--text-strong);
}

.tokens__id {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-2xs);
  color: var(--text-muted);
}

.tokens__wait,
.tokens__hint {
  margin: 0;
  font-size: var(--fs-2xs);
  line-height: 1.5;
  color: var(--text-muted);
}

.tokens__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.tokens__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  font-size: var(--fs-2xs);
  line-height: 1.45;
  color: var(--text-primary);
}

.tokens__row.is-total {
  padding-top: 6px;
  border-top: 1px solid var(--border-subtle);
  color: var(--text-strong);
  font-weight: 600;
}

.tokens__name {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.tokens__code {
  color: var(--text-muted);
  font-weight: 400;
}

.tokens__nums {
  flex: none;
  text-align: right;
  color: var(--text-muted);
}

.tokens__row.is-total .tokens__nums {
  color: var(--text-strong);
}

.tokens__hint {
  margin-top: 8px;
}
</style>
