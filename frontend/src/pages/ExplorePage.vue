<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listGoodAppByPage } from '../api/app'
import type { AppVO } from '../types'
import AppCard from '../components/AppCard.vue'
import { useAppPage } from '../composables/useAppPage'

const router = useRouter()
const {
  records: apps,
  loading,
  total,
  pageNum,
  pageSize,
  load: loadApps,
  changePage,
} = useAppPage(listGoodAppByPage, 12)

function openApp(app: AppVO) { router.push({ name: 'appShare', params: { id: String(app.id) } }) }

function onPageChange(page: number) { changePage(page) }

onMounted(() => { loadApps() })
</script>

<template>
  <div class="page">
    <div class="page-header">
      <h2 class="page-title">精选应用</h2>
      <span class="page-count">{{ total }} 个</span>
    </div>
    <a-spin :spinning="loading">
      <div v-if="apps.length" class="grid">
        <AppCard v-for="app in apps" :key="app.id" :app="app" @open="openApp" />
      </div>
      <div v-else-if="!loading" class="empty-state">暂无精选应用</div>
    </a-spin>
    <div v-if="total > pageSize" class="pagination">
      <a-pagination :current="pageNum" :total="total" :page-size="pageSize" :show-size-changer="false" @change="onPageChange" />
    </div>
  </div>
</template>

<style scoped>
.page {
  flex: 1; overflow-y: auto; padding: 32px 48px 64px; background: var(--bg-page);
}
.page-header { display: flex; align-items: baseline; gap: 10px; margin-bottom: 24px; }
.page-title { font-size: 20px; font-weight: 600; margin: 0; }
.page-count {
  font-size: 12px; color: var(--gray-400); background: var(--gray-50);
  padding: 2px 8px; border-radius: var(--r-pill);
}
.grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.empty-state { color: var(--gray-400); text-align: center; padding: 60px 0; }
.pagination { display: flex; justify-content: center; margin-top: 32px; }
@media (max-width: 960px) { .grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 580px) { .grid { grid-template-columns: 1fr; } }
</style>
