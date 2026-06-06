<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { addApp, listGoodAppByPage } from '../api/app'
import type { AppVO } from '../types'
import AppCard from '../components/AppCard.vue'

const router = useRouter()

const prompt = ref('')
const creating = ref(false)

const goodApps = ref<AppVO[]>([])
const loadingApps = ref(false)

const suggestions = [
  '做一个企业官网首页，包含导航、轮播图和产品介绍',
  '生成一个个人博客页面，简约风格，带文章列表',
  '帮我做一个待办事项 ToDo 应用',
  '设计一个餐厅菜单展示页，含图片和价格',
]

async function loadGoodApps() {
  loadingApps.value = true
  const res = await listGoodAppByPage({ pageNum: 1, pageSize: 12 }).catch(() => null)
  loadingApps.value = false
  if (res && res.code === 0) {
    goodApps.value = res.data.records || []
  }
}

async function handleCreate() {
  const initPrompt = prompt.value.trim()
  if (!initPrompt) {
    message.warning('请先描述你想要生成的网站')
    return
  }
  creating.value = true
  const res = await addApp({ initPrompt }).catch(() => null)
  creating.value = false
  if (res && res.code === 0) {
    // 携带初始 prompt，进入对话页后自动发起首轮生成
    router.push({
      name: 'appChat',
      params: { id: String(res.data) },
      query: { init: '1' },
    })
  }
}

function useSuggestion(text: string) {
  prompt.value = text
}

function openApp(app: AppVO) {
  router.push({ name: 'appChat', params: { id: String(app.id) } })
}

onMounted(loadGoodApps)
</script>

<template>
  <div class="home">
    <section class="hero">
      <h1 class="title">一句话，<span class="grad">生成你的网站</span></h1>
      <p class="subtitle">描述你的需求，AI 实时为你编写并预览完整代码</p>

      <div class="prompt-box">
        <a-textarea
          v-model:value="prompt"
          :auto-size="{ minRows: 3, maxRows: 6 }"
          placeholder="例如：帮我做一个现代风格的咖啡馆官网，包含首页、菜单和联系方式"
          :bordered="false"
          @keydown.enter.exact.prevent="handleCreate"
        />
        <div class="prompt-actions">
          <span class="tip">Enter 发送 · Shift+Enter 换行</span>
          <a-button
            type="primary"
            size="large"
            :loading="creating"
            @click="handleCreate"
          >
            生成应用
          </a-button>
        </div>
      </div>

      <div class="suggestions">
        <span
          v-for="s in suggestions"
          :key="s"
          class="chip"
          @click="useSuggestion(s)"
        >
          {{ s }}
        </span>
      </div>
    </section>

    <section class="good-section">
      <h2 class="section-title">精选应用</h2>
      <a-spin :spinning="loadingApps">
        <div v-if="goodApps.length" class="grid">
          <AppCard
            v-for="app in goodApps"
            :key="app.id"
            :app="app"
            @open="openApp"
          />
        </div>
        <a-empty v-else-if="!loadingApps" description="暂无精选应用" />
      </a-spin>
    </section>
  </div>
</template>

<style scoped>
.home {
  flex: 1;
  overflow-y: auto;
  padding: 0 24px 60px;
}

.hero {
  max-width: 760px;
  margin: 0 auto;
  padding: 56px 0 32px;
  text-align: center;
}

.title {
  font-size: 40px;
  font-weight: 700;
  margin: 0 0 12px;
  color: var(--text-primary);
}
.grad {
  background: linear-gradient(120deg, #1677ff, #4096ff);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.subtitle {
  font-size: 16px;
  color: var(--text-secondary);
  margin: 0 0 32px;
}

.prompt-box {
  background: #fff;
  border-radius: 16px;
  padding: 12px 14px 10px;
  box-shadow: 0 10px 40px rgba(22, 119, 255, 0.12);
  border: 1px solid rgba(22, 119, 255, 0.12);
  text-align: left;
  transition: box-shadow 0.2s, border-color 0.2s;
}
.prompt-box:focus-within {
  border-color: #4096ff;
  box-shadow: 0 12px 46px rgba(22, 119, 255, 0.2);
}

.prompt-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
.tip {
  font-size: 12px;
  color: #999;
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  justify-content: center;
  margin-top: 22px;
}
.chip {
  font-size: 13px;
  color: var(--text-secondary);
  background: #fff;
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 16px;
  padding: 6px 14px;
  cursor: pointer;
  transition: all 0.2s;
}
.chip:hover {
  color: #1677ff;
  border-color: #4096ff;
  background: rgba(22, 119, 255, 0.06);
}

.good-section {
  max-width: 1160px;
  margin: 24px auto 0;
}
.section-title {
  font-size: 22px;
  font-weight: 600;
  margin: 0 0 18px;
  color: var(--text-primary);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(248px, 1fr));
  gap: 20px;
}
</style>
