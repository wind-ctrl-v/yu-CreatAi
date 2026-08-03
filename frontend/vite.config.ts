import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { devMockPlugin } from './mock'

/** 离线演示模式：VITE_MOCK=1 时用内置 mock 中间件替代后端（仅开发服务器生效） */
const useMock = process.env.VITE_MOCK === '1'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue(), ...(useMock ? [devMockPlugin()] : [])],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: true,
    // 开启离线演示时不再转发到后端，避免请求落到未启动的服务上
    proxy: useMock
      ? undefined
      : {
          // 开发期将 /api 转发到后端，规避跨域并统一同源 Cookie 行为
          '/api': {
            target: 'http://localhost:8123',
            changeOrigin: true,
          },
        },
  },
})
