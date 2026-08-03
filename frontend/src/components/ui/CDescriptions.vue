<script setup lang="ts">
export interface DescItem {
  label: string
  value?: string | number | null
  span?: 1 | 2 | 3
  mono?: boolean
  key?: string
}

withDefaults(
  defineProps<{
    items: DescItem[]
    columns?: 1 | 2 | 3
    bordered?: boolean
  }>(),
  { columns: 2, bordered: true },
)
</script>

<template>
  <dl class="c-desc" :class="[`cols-${columns}`, { 'is-bordered': bordered }]">
    <div
      v-for="(item, i) in items"
      :key="item.key ?? item.label ?? i"
      class="c-desc__row"
      :style="{ gridColumn: item.span && item.span > 1 ? `span ${item.span}` : undefined }"
    >
      <dt class="c-desc__label">{{ item.label }}</dt>
      <dd class="c-desc__value" :class="{ mono: item.mono }">
        <slot :name="item.key ?? item.label" :item="item">
          {{ item.value === null || item.value === undefined || item.value === '' ? '—' : item.value }}
        </slot>
      </dd>
    </div>
  </dl>
</template>

<style scoped>
.c-desc {
  display: grid;
  gap: 1px;
  margin: 0;
  border-radius: var(--r-lg);
  overflow: hidden;
}

.cols-1 { grid-template-columns: 1fr; }
.cols-2 { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.cols-3 { grid-template-columns: repeat(3, minmax(0, 1fr)); }

.c-desc.is-bordered {
  background: var(--border-subtle);
  border: 1px solid var(--border-subtle);
}

.c-desc__row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 15px;
  background: var(--bg-surface);
  min-width: 0;
}

.c-desc__label {
  font-size: var(--fs-2xs);
  font-weight: 500;
  color: var(--text-muted);
  letter-spacing: var(--tracking-tight);
}

.c-desc__value {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--text-primary);
  overflow-wrap: anywhere;
  line-height: 1.55;
}

.c-desc__value.mono {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}

@media (max-width: 640px) {
  .cols-2,
  .cols-3 { grid-template-columns: 1fr; }
  .c-desc__row { grid-column: span 1 !important; }
}
</style>
