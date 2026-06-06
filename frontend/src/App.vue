<script setup lang="ts">
import { ConfigProvider } from 'ant-design-vue'

// 统一品牌主色，呼应设计规范
const theme = {
  token: {
    colorPrimary: '#1677ff',
    borderRadius: 8,
    fontFamily: "'PingFang SC', -apple-system, BlinkMacSystemFont, sans-serif",
  },
}
</script>

<template>
  <ConfigProvider :theme="theme">
    <router-view />
  </ConfigProvider>
</template>
