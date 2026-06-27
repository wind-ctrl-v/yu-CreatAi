<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '../stores/loginUser'

const router = useRouter()
const store = useLoginUserStore()
const user = computed(() => store.loginUser)
</script>

<template>
  <div class="page">
    <div class="card">
      <a-avatar :src="user?.userAvatar" :size="72" class="avatar">
        {{ (user?.userName || user?.userAccount || 'U').charAt(0) }}
      </a-avatar>
      <h2 class="name">{{ user?.userName || user?.userAccount }}</h2>
      <p class="account">@{{ user?.userAccount }}</p>
      <a-tag v-if="user?.userRole === 'admin'" color="gold">管理员</a-tag>

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
.detail-list { text-align: left; margin: 28px 0; }
.detail-row {
  display: flex; justify-content: space-between; padding: 10px 0;
  border-bottom: 1px solid var(--gray-50); font-size: 14px;
}
.label { color: var(--gray-400); }
.value { color: var(--geist-black); font-weight: 500; }
.links { display: flex; gap: 10px; justify-content: center; }
</style>
