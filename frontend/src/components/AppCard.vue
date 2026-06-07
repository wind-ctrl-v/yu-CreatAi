<script setup lang="ts">
import type { AppVO } from '../types'

defineProps<{ app: AppVO }>()
const emit = defineEmits<{ (e: 'open', app: AppVO): void }>()

const fallbackCover =
  'https://placehold.co/600x360/1677ff/ffffff?text=AI+App'
</script>

<template>
  <div class="app-card" @click="emit('open', app)">
    <div class="cover">
      <img :src="app.cover || fallbackCover" :alt="app.appName" loading="lazy" />
    </div>
    <div class="meta">
      <div class="name" :title="app.appName">{{ app.appName || '未命名应用' }}</div>
      <div class="author">
        <a-avatar :src="app.user?.userAvatar" size="small">
          {{ (app.user?.userName || 'U').charAt(0) }}
        </a-avatar>
        <span>{{ app.user?.userName || app.user?.userAccount || '匿名用户' }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.app-card {
  background: var(--bg-card);
  border-radius: var(--radius-lg);
  overflow: hidden;
  cursor: pointer;
  border: 1px solid rgba(20, 22, 40, 0.05);
  box-shadow: var(--shadow-sm);
  transition: transform 0.24s ease, box-shadow 0.24s ease, border-color 0.24s ease;
}
.app-card:hover {
  transform: translateY(-6px);
  border-color: rgba(91, 108, 255, 0.3);
  box-shadow: var(--shadow-brand);
}

.cover {
  aspect-ratio: 16 / 10;
  overflow: hidden;
  background: linear-gradient(135deg, #eef0ff, #f3ecff);
}
.cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.3s ease;
}
.app-card:hover .cover img {
  transform: scale(1.05);
}

.meta {
  padding: 12px 14px 14px;
}
.name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.author {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}
</style>
