<script setup lang="ts">
import { computed } from 'vue'
import CIcon from './CIcon.vue'

const props = withDefaults(
  defineProps<{
    current: number
    pageSize: number
    total: number
    showTotal?: boolean
    siblings?: number
  }>(),
  { showTotal: true, siblings: 1 },
)

const emit = defineEmits<{
  (e: 'change', page: number, pageSize: number): void
  (e: 'update:current', page: number): void
}>()

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))

const pages = computed<(number | 'gap')[]>(() => {
  const total = totalPages.value
  const cur = props.current
  const s = props.siblings
  if (total <= 5 + s * 2) return Array.from({ length: total }, (_, i) => i + 1)

  const list: (number | 'gap')[] = [1]
  const start = Math.max(2, cur - s)
  const end = Math.min(total - 1, cur + s)
  if (start > 2) list.push('gap')
  for (let i = start; i <= end; i++) list.push(i)
  if (end < total - 1) list.push('gap')
  list.push(total)
  return list
})

const range = computed(() => {
  const from = props.total === 0 ? 0 : (props.current - 1) * props.pageSize + 1
  const to = Math.min(props.current * props.pageSize, props.total)
  return `${from}–${to}`
})

function go(page: number) {
  if (page < 1 || page > totalPages.value || page === props.current) return
  emit('update:current', page)
  emit('change', page, props.pageSize)
}
</script>

<template>
  <nav v-if="total > 0" class="c-pager" aria-label="分页">
    <span v-if="showTotal" class="c-pager__info">
      {{ range }} / 共 {{ total }} 条
    </span>
    <div class="c-pager__ctrl">
      <button
        class="c-pager__btn"
        type="button"
        :disabled="current <= 1"
        aria-label="上一页"
        @click="go(current - 1)"
      >
        <CIcon name="chevron-left" :size="14" />
      </button>
      <template v-for="(p, i) in pages" :key="`${p}-${i}`">
        <span v-if="p === 'gap'" class="c-pager__gap">···</span>
        <button
          v-else
          class="c-pager__btn"
          :class="{ 'is-active': p === current }"
          type="button"
          :aria-current="p === current ? 'page' : undefined"
          @click="go(p as number)"
        >
          {{ p }}
        </button>
      </template>
      <button
        class="c-pager__btn"
        type="button"
        :disabled="current >= totalPages"
        aria-label="下一页"
        @click="go(current + 1)"
      >
        <CIcon name="chevron-right" :size="14" />
      </button>
    </div>
  </nav>
</template>

<style scoped>
.c-pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.c-pager__info {
  font-size: var(--fs-xs);
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.c-pager__ctrl {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
}

.c-pager__btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 32px;
  height: 32px;
  padding: 0 8px;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  background: transparent;
  font-size: var(--fs-xs);
  font-weight: 500;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
  cursor: pointer;
  transition: all var(--t-fast) var(--ease-out);
}

.c-pager__btn:hover:not(:disabled):not(.is-active) {
  background: var(--bg-hover);
  color: var(--text-primary);
}

.c-pager__btn.is-active {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-fg);
}

.c-pager__btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.c-pager__gap {
  padding: 0 4px;
  font-size: var(--fs-xs);
  color: var(--text-faint);
  letter-spacing: 1px;
}
</style>
