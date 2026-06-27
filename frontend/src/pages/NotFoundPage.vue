<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <div class="page">
    <div class="box">
      <div class="code">404</div>
      <h1 class="title">页面未找到</h1>
      <p class="desc">你访问的页面不存在或已被移除。</p>
      <a-button type="primary" @click="router.push('/')">返回首页</a-button>
    </div>
  </div>
</template>

<style scoped>
.page {
  flex: 1; display: flex; align-items: center; justify-content: center;
  background: var(--bg-page); padding: 48px;
}
.box { text-align: center; max-width: 360px; }
.code { font-size: 72px; font-weight: 700; color: var(--gray-200); line-height: 1; margin-bottom: 8px; }
.title { font-size: 22px; font-weight: 600; margin: 0 0 8px; }
.desc { font-size: 14px; color: var(--gray-400); margin: 0 0 24px; }
</style>
