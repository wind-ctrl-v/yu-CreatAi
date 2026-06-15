<script setup lang="ts">
import type { AppVO } from '../types'

defineProps<{ app: AppVO }>()
const emit = defineEmits<{ (e: 'open', app: AppVO): void }>()

const fallbackCover = 'https://placehold.co/600x360/171717/ffffff?text=CreatAi'
</script>

<template>
  <div class="app-card" @click="emit('open', app)">
    <!-- 封面图 -->
    <div class="cover">
      <img :src="app.cover || fallbackCover" :alt="app.appName" loading="lazy" />
      <!-- Hover overlay：Vercel 风格极简提示 -->
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
  /* 顶部圆角对齐 Vercel image radius */
  border-radius: var(--r-image) var(--r-image) 0 0;
}
.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.36s var(--smooth), filter 0.36s ease;
  display: block;
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
</style>
