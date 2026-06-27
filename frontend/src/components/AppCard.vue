<script setup lang="ts">
import { computed } from 'vue'
import type { AppVO } from '../types'
import { buildPreviewUrl } from '../api/app'

const props = defineProps<{ app: AppVO }>()
const emit = defineEmits<{ (e: 'open', app: AppVO): void }>()

const typeLabel = computed(() => {
  const map: Record<string, string> = { vue_project: 'Vue 项目', html: 'HTML 页面', multi_file: '多文件' }
  return map[props.app.codeGenType] || '应用'
})

const previewUrl = computed(() => buildPreviewUrl(props.app.codeGenType, props.app.id))
</script>

<template>
  <div class="app-card" @click="emit('open', app)">
    <!-- 封面图 -->
    <div class="cover">
      <!-- 有封面用封面，无封面用程序化预览 -->
      <img v-if="app.cover" :src="app.cover" :alt="app.appName" loading="lazy" />
      <div v-else class="cover-fallback">
        <div class="fallback-inner">
          <div class="fb-icon">
            <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">
              <polyline points="16 18 22 12 16 6" /><polyline points="8 6 2 12 8 18" />
            </svg>
          </div>
          <span class="fb-type">{{ typeLabel }}</span>
          <span class="fb-name">{{ app.appName || '未命名' }}</span>
        </div>
      </div>
      <!-- Hover overlay -->
      <div class="cover-overlay">
        <span class="open-label">打开 →</span>
      </div>
    </div>
    <!-- Meta 信息 -->
    <div class="meta">
      <div class="meta-row">
        <span class="app-name" :title="app.appName">{{ app.appName || '未命名应用' }}</span>
        <span class="arrow">↗</span>
      </div>
      <div class="author">
        <a-avatar :src="app.user?.userAvatar" :size="16">
          {{ (app.user?.userName || 'U').charAt(0) }}
        </a-avatar>
        <span>{{ app.user?.userName || app.user?.userAccount || '匿名用户' }}</span>
      </div>
    </div>
    <div v-if="$slots.actions" class="card-actions" @click.stop>
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
/* shadow-as-border 卡片系统 — Vercel Full Card Level 3 */
.app-card {
  background: var(--bg-card);
  border-radius: var(--r-card);
  overflow: hidden;
  cursor: pointer;
  border: none;
  box-shadow: var(--shadow-card);
  transition: box-shadow var(--duration-base) var(--smooth),
              transform var(--duration-base) var(--spring);
}
.app-card:hover {
  box-shadow: var(--shadow-card-hover);
  transform: translateY(-3px);
}
.app-card:active {
  transform: translateY(-1px) scale(0.99);
  transition-duration: 0.1s;
}

/* 封面 */
.cover {
  position: relative;
  aspect-ratio: 16 / 10;
  overflow: hidden;
  background: var(--gray-50);
  border-radius: var(--r-image) var(--r-image) 0 0;
}
.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.36s var(--smooth), filter 0.36s ease;
  display: block;
}

/* 无封面时程序化卡片 */
.cover-fallback {
  width: 100%;
  height: 100%;
  background: linear-gradient(135deg, #f0f2f5 0%, #e8ecf1 50%, #f0f2f5 100%);
  display: flex;
  align-items: center;
  justify-content: center;
}
.fallback-inner {
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.fb-icon {
  color: var(--gray-400);
  opacity: 0.6;
}
.fb-type {
  font-size: 11px;
  font-weight: 500;
  color: var(--gray-400);
  background: var(--bg-card);
  border-radius: var(--r-pill);
  padding: 2px 10px;
  letter-spacing: var(--tracking-label);
  box-shadow: var(--shadow-ring);
}
.fb-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--gray-600);
  max-width: 90%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.app-card:hover .cover img {
  transform: scale(1.03);
  filter: brightness(0.92);
}

/* Overlay */
.cover-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(23,23,23,0.48);
  backdrop-filter: blur(2px);
  opacity: 0;
  transition: opacity var(--duration-base) var(--smooth);
}
.app-card:hover .cover-overlay { opacity: 1; }

.open-label {
  font-size: 13px;
  font-weight: 600;
  color: #fff;
  letter-spacing: var(--tracking-label);
  background: rgba(255,255,255,0.12);
  border: 1px solid rgba(255,255,255,0.2);
  border-radius: var(--r-pill);
  padding: 6px 14px;
  /* B09 发光按钮效果 */
  box-shadow: 0 0 16px rgba(255,255,255,0.15);
}

/* Meta */
.meta {
  padding: 12px 14px 13px;
  border-top: 1px solid var(--gray-100);
}

.meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 7px;
}

.app-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--geist-black);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  letter-spacing: var(--tracking-label);
  flex: 1;
  min-width: 0;
}

.arrow {
  font-size: 12px;
  color: var(--gray-400);
  margin-left: 6px;
  flex-shrink: 0;
  transition: color var(--duration-fast) ease, transform var(--duration-fast) var(--spring);
}
.app-card:hover .arrow {
  color: var(--geist-black);
  transform: translate(1px, -1px);
}

.author {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--gray-400);
  letter-spacing: 0;
}

.card-actions {
  padding: 0 14px 12px;
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
</style>
