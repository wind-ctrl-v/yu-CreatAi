<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listGoodAppByPage } from '../api/app'
import AppCard from '../components/AppCard.vue'
import CEmpty from '../components/ui/CEmpty.vue'
import CPagination from '../components/ui/CPagination.vue'
import CSpin from '../components/ui/CSpin.vue'
import { useAppPage } from '../composables/useAppPage'
import type { AppVO } from '../types'

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

function openApp(app: AppVO) {
  router.push({ name: 'appShare', params: { id: String(app.id) } })
}

onMounted(() => {
  loadApps()
})
</script>

<template>
  <div class="page container">
    <header class="page-head">
      <div>
        <p class="eyebrow">Showcase</p>
        <h1 class="page-title">精选应用</h1>
        <p class="page-subtitle">来自社区的优质作品，点开即可查看效果与实现思路。</p>
      </div>
      <span class="count">共 {{ total }} 个</span>
    </header>

    <CSpin :spinning="loading">
      <div v-if="apps.length" class="grid">
        <AppCard v-for="app in apps" :key="app.id" :app="app" @open="openApp" />
      </div>
      <CEmpty
        v-else-if="!loading"
        icon="compass"
        description="暂无精选应用"
        hint="精选内容由管理员挑选，稍后再来看看。"
      />
    </CSpin>

    <div v-if="total > pageSize" class="pager">
      <CPagination
        :current="pageNum"
        :total="total"
        :page-size="pageSize"
        @change="(p) => changePage(p)"
      />
    </div>
  </div>
</template>

<style scoped>
.page {
  flex: 1;
  padding-top: var(--sp-10);
  padding-bottom: var(--sp-16);
}

.count {
  font-size: var(--fs-xs);
  color: var(--text-muted);
  padding: 4px 10px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-pill);
  background: var(--bg-surface);
  font-variant-numeric: tabular-nums;
}

.grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-4);
}

.pager {
  margin-top: var(--sp-8);
}

@media (max-width: 980px) {
  .grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 600px) {
  .grid { grid-template-columns: 1fr; }
  .page { padding-top: var(--sp-6); }
}
</style>
