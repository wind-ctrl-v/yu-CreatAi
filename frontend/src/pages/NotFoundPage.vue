<script setup lang="ts">
import { useRouter } from 'vue-router'
import CButton from '../components/ui/CButton.vue'

const router = useRouter()
</script>

<template>
  <div class="page">
    <div class="box">
      <span class="code">404</span>
      <h1 class="title">页面未找到</h1>
      <p class="desc">你访问的页面不存在或已被移除。</p>
      <div class="actions">
        <CButton variant="primary" icon="home" @click="router.push('/')">返回首页</CButton>
        <CButton variant="secondary" icon="arrow-left" @click="router.back()">返回上一页</CButton>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-12) var(--sp-6);
  min-height: 70vh;
}

.box {
  text-align: center;
  max-width: 400px;
}

.code {
  display: block;
  font-size: 76px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: var(--tracking-display);
  color: var(--n-200);
  font-variant-numeric: tabular-nums;
}

.title {
  margin-top: var(--sp-4);
  font-size: var(--fs-h2);
  font-weight: 600;
  color: var(--text-strong);
}

.desc {
  margin-top: var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--text-muted);
}

.actions {
  display: flex;
  justify-content: center;
  gap: 9px;
  margin-top: var(--sp-6);
  flex-wrap: wrap;
}
</style>
