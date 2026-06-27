<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '../stores/loginUser'
import { getPlanCapabilities } from '../api/user'
import { PLAN_LABELS, type PlanCapabilities } from '../types'

const router = useRouter()
const store = useLoginUserStore()
const user = computed(() => store.loginUser)
const plan = ref<PlanCapabilities | null>(null)

const planColor = (pt: string) => {
  const colors: Record<string, string> = { FREE: '#8c8c8c', PRO: '#1677ff', PREMIUM: '#722ed1', ENTERPRISE: '#f5a623', ADMIN: '#f5222d' }
  return colors[pt] || '#8c8c8c'
}

onMounted(async () => {
  const res = await getPlanCapabilities().catch(() => null)
  if (res?.code === 0) plan.value = res.data
})
</script>

<template>
  <div class="page">
    <div class="card">
      <a-avatar :src="user?.userAvatar" :size="72" class="avatar">
        {{ (user?.userName || user?.userAccount || 'U').charAt(0) }}
      </a-avatar>
      <h2 class="name">{{ user?.userName || user?.userAccount }}</h2>
      <p class="account">@{{ user?.userAccount }}</p>
      <div class="tags">
        <a-tag v-if="user?.userRole === 'admin'" color="gold">管理员</a-tag>
        <a-tag v-if="plan" :color="planColor(plan.planType)">{{ plan.planName }}</a-tag>
      </div>

      <div class="detail-list">
        <div class="detail-row">
          <span class="label">昵称</span>
          <span class="value">{{ user?.userName || '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="label">简介</span>
          <span class="value">{{ user?.userProfile || '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="label">注册时间</span>
          <span class="value">{{ user?.createTime || '-' }}</span>
        </div>
        <div v-if="plan" class="detail-row">
          <span class="label">套餐能力</span>
          <span class="value">{{ plan.availableModes.map(m => m === 'low_cost' ? '快速' : m === 'standard' ? '标准' : '高质量').join('、') }}</span>
        </div>
      </div>

      <div class="links">
        <a-button type="primary" ghost @click="router.push('/apps')">我的作品</a-button>
        <a-button v-if="user?.userRole === 'admin'" @click="router.push('/admin')">管理后台</a-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  flex: 1; overflow-y: auto; padding: 48px 48px 64px; background: var(--bg-page);
  display: flex; justify-content: center; align-items: flex-start;
}
.card {
  background: var(--geist-white); border-radius: var(--r-image);
  box-shadow: var(--shadow-card); padding: 40px; width: 100%; max-width: 480px;
  text-align: center;
}
.avatar { display: inline-flex; margin-bottom: 16px; }
.name { font-size: 22px; font-weight: 600; margin: 0 0 4px; }
.account { font-size: 14px; color: var(--gray-400); margin: 0 0 8px; }
.tags { display: flex; gap: 6px; justify-content: center; }
.detail-list { text-align: left; margin: 28px 0; }
.detail-row {
  display: flex; justify-content: space-between; padding: 10px 0;
  border-bottom: 1px solid var(--gray-50); font-size: 14px;
}
.label { color: var(--gray-400); }
.value { color: var(--geist-black); font-weight: 500; }
.links { display: flex; gap: 10px; justify-content: center; }
</style>
