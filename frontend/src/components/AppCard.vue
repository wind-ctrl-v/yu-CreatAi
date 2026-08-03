<script setup lang="ts">
import { computed } from 'vue'
import CAvatar from './ui/CAvatar.vue'
import CIcon from './ui/CIcon.vue'
import type { AppVO } from '../types'

const props = defineProps<{ app: AppVO }>()
const emit = defineEmits<{ (e: 'open', app: AppVO): void }>()

const TYPE_META: Record<string, { label: string; icon: string }> = {
  vue_project: { label: 'Vue 项目', icon: 'layers' },
  html: { label: 'HTML 页面', icon: 'code' },
  multi_file: { label: '多文件', icon: 'file' },
}

const typeMeta = computed(
  () => TYPE_META[props.app.codeGenType] ?? { label: '应用', icon: 'grid' },
)

const authorName = computed(
  () => props.app.user?.userName || props.app.user?.userAccount || '匿名用户',
)
</script>

<template>
  <article class="card" @click="emit('open', app)">
    <div class="card__cover">
      <img v-if="app.cover" :src="app.cover" :alt="app.appName" loading="lazy" />
      <div v-else class="card__placeholder surface-grid">
        <span class="card__ph-icon"><CIcon :name="typeMeta.icon" :size="22" :stroke="1.5" /></span>
        <span class="card__ph-name">{{ app.appName || '未命名' }}</span>
      </div>

      <span class="card__type">{{ typeMeta.label }}</span>

      <div class="card__veil">
        <span class="card__open">
          打开应用
          <CIcon name="arrow-right" :size="14" />
        </span>
      </div>
    </div>

    <div class="card__meta">
      <div class="card__row">
        <h3 class="card__name" :title="app.appName">{{ app.appName || '未命名应用' }}</h3>
        <CIcon name="external-link" :size="13" class="card__arrow" />
      </div>
      <div class="card__author">
        <CAvatar :src="app.user?.userAvatar" :name="authorName" :size="18" />
        <span class="truncate">{{ authorName }}</span>
      </div>
    </div>

    <div v-if="$slots.actions" class="card__actions" @click.stop>
      <slot name="actions" />
    </div>
  </article>
</template>

<style scoped>
.card {
  display: flex;
  flex-direction: column;
  background: var(--bg-card);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-xl);
  overflow: hidden;
  cursor: pointer;
  box-shadow: var(--shadow-xs);
  transition:
    border-color var(--t-base) var(--ease-out),
    box-shadow var(--t-base) var(--ease-out),
    transform var(--t-base) var(--ease-out);
}

.card:hover {
  border-color: var(--border-strong);
  box-shadow: var(--shadow-md);
  transform: translateY(-3px);
}

.card:active {
  transform: translateY(-1px);
  transition-duration: 0.08s;
}

/* ── 封面 ── */
.card__cover {
  position: relative;
  aspect-ratio: 16 / 10;
  background: var(--n-50);
  overflow: hidden;
  border-bottom: 1px solid var(--border-subtle);
}

.card__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform var(--t-slow) var(--ease-out);
}

.card:hover .card__cover img { transform: scale(1.035); }

.card__placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  background-color: var(--n-50);
}

.card__ph-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--r-lg);
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  box-shadow: var(--shadow-xs);
  color: var(--text-muted);
}

.card__ph-name {
  max-width: 78%;
  font-size: var(--fs-xs);
  font-weight: 500;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card__type {
  position: absolute;
  top: 10px;
  left: 10px;
  height: 21px;
  display: inline-flex;
  align-items: center;
  padding: 0 8px;
  border-radius: var(--r-xs);
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(6px);
  border: 1px solid var(--border-subtle);
  font-size: 11px;
  font-weight: 500;
  color: var(--text-secondary);
}

.card__veil {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(26, 26, 24, 0.4);
  backdrop-filter: blur(2px);
  opacity: 0;
  transition: opacity var(--t-base) var(--ease-out);
}

.card:hover .card__veil { opacity: 1; }

.card__open {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 34px;
  padding: 0 15px;
  border-radius: var(--r-pill);
  background: var(--bg-surface);
  color: var(--text-strong);
  font-size: var(--fs-sm);
  font-weight: 500;
  box-shadow: var(--shadow-md);
  transform: translateY(6px);
  transition: transform var(--t-base) var(--ease-spring);
}

.card:hover .card__open { transform: none; }

/* ── 信息 ── */
.card__meta { padding: 12px 14px 13px; }

.card__row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.card__name {
  flex: 1;
  min-width: 0;
  font-size: var(--fs-sm);
  font-weight: 550;
  color: var(--text-strong);
  letter-spacing: var(--tracking-tight);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card__arrow {
  flex: none;
  color: var(--text-faint);
  transition: color var(--t-fast) var(--ease-out);
}

.card:hover .card__arrow { color: var(--text-primary); }

.card__author {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  font-size: var(--fs-2xs);
  color: var(--text-muted);
}

.card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  padding: 0 12px 12px;
}
</style>
