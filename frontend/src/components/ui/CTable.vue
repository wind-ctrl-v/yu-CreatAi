<script setup lang="ts" generic="T extends Record<string, any>">
import CEmpty from './CEmpty.vue'
import CSpin from './CSpin.vue'

export interface TableColumn {
  key: string
  title: string
  width?: string
  minWidth?: string
  align?: 'left' | 'center' | 'right'
  ellipsis?: boolean
  mono?: boolean
}

const props = withDefaults(
  defineProps<{
    columns: TableColumn[]
    data: T[]
    rowKey?: string
    loading?: boolean
    emptyText?: string
    minWidth?: string
    hoverable?: boolean
    compact?: boolean
  }>(),
  { rowKey: 'id', loading: false, emptyText: '暂无数据', hoverable: true, compact: false },
)

const emit = defineEmits<{ (e: 'rowClick', record: T): void }>()

defineSlots<
  {
    empty?: () => any
  } & Record<string, (props: { record: T; index: number; value: any }) => any>
>()

function keyOf(record: T, index: number) {
  return (record as Record<string, any>)?.[props.rowKey] ?? index
}
</script>

<template>
  <div class="c-table">
    <CSpin :spinning="loading">
      <div class="c-table__scroll">
        <table :style="minWidth ? { minWidth } : undefined">
          <thead>
            <tr>
              <th
                v-for="col in columns"
                :key="col.key"
                :style="{
                  width: col.width,
                  minWidth: col.minWidth,
                  textAlign: col.align ?? 'left',
                }"
              >
                {{ col.title }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(record, index) in data"
              :key="keyOf(record, index)"
              :class="{ 'is-hoverable': hoverable }"
              @click="emit('rowClick', record)"
            >
              <td
                v-for="col in columns"
                :key="col.key"
                :class="{ 'is-ellipsis': col.ellipsis, mono: col.mono, 'is-compact': compact }"
                :style="{ textAlign: col.align ?? 'left' }"
              >
                <slot :name="`cell:${col.key}`" :record="record" :index="index" :value="record[col.key]">
                  <span :title="col.ellipsis ? String(record[col.key] ?? '') : undefined">
                    {{ record[col.key] ?? '—' }}
                  </span>
                </slot>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="!data.length && !loading" class="c-table__empty">
        <slot name="empty">
          <CEmpty :description="emptyText" size="sm" />
        </slot>
      </div>
    </CSpin>
  </div>
</template>

<style scoped>
.c-table {
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-xl);
  overflow: hidden;
  box-shadow: var(--shadow-xs);
}

.c-table__scroll {
  overflow-x: auto;
  overflow-y: hidden;
}

table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: var(--fs-sm);
}

thead th {
  position: sticky;
  top: 0;
  padding: 11px 16px;
  background: var(--n-25);
  border-bottom: 1px solid var(--border-subtle);
  font-size: var(--fs-2xs);
  font-weight: 600;
  letter-spacing: var(--tracking-wide);
  text-transform: uppercase;
  color: var(--text-muted);
  white-space: nowrap;
  z-index: 1;
}

tbody td {
  padding: 13px 16px;
  border-bottom: 1px solid var(--border-subtle);
  color: var(--text-primary);
  vertical-align: middle;
  letter-spacing: var(--tracking-tight);
}

tbody td.is-compact { padding: 9px 16px; }

tbody tr:last-child td { border-bottom: none; }

tbody tr.is-hoverable:hover td {
  background: var(--n-25);
}

td.is-ellipsis {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

td.mono {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--text-secondary);
}

.c-table__empty {
  border-top: 1px solid var(--border-subtle);
}
</style>
