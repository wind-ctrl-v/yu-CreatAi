<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { addApp, listGoodAppByPage, listMyAppByPage } from '../api/app'
import type { AppVO } from '../types'
import AppCard from '../components/AppCard.vue'

const router = useRouter()

const prompt = ref('')
const creating = ref(false)

const goodApps = ref<AppVO[]>([])
const loadingApps = ref(false)

const myApps = ref<AppVO[]>([])
const loadingMyApps = ref(false)

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

async function loadMyApps() {
  loadingMyApps.value = true
  const res = await listMyAppByPage({ pageNum: 1, pageSize: 12 }).catch(() => null)
  loadingMyApps.value = false
  if (res && res.code === 0) {
    myApps.value = res.data.records || []
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

onMounted(() => {
  loadGoodApps()
  loadMyApps()
})
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

    <section v-if="myApps.length || loadingMyApps" class="good-section">
      <h2 class="section-title">我的作品</h2>
      <a-spin :spinning="loadingMyApps">
        <div v-if="myApps.length" class="grid">
          <AppCard
            v-for="app in myApps"
            :key="app.id"
            :app="app"
            @open="openApp"
          />
        </div>
        <a-empty
          v-else-if="!loadingMyApps"
          description="还没有作品，从上面开始创作吧"
        />
      </a-spin>
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
  position: relative;
  max-width: 760px;
  margin: 0 auto;
  padding: 64px 0 36px;
  text-align: center;
}
.hero::before {
  content: '';
  position: absolute;
  top: -40px;
  left: 50%;
  width: 520px;
  height: 320px;
  transform: translateX(-50%);
  background: radial-gradient(
    ellipse at center,
    rgba(124, 92, 255, 0.18),
    transparent 70%
  );
  filter: blur(10px);
  z-index: -1;
  pointer-events: none;
}

.title {
  font-size: 46px;
  font-weight: 800;
  letter-spacing: -0.5px;
  margin: 0 0 14px;
  color: var(--text-primary);
}
.grad {
  background: var(--brand-gradient);
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
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(8px);
  border-radius: 18px;
  padding: 14px 16px 12px;
  box-shadow: var(--shadow-brand);
  border: 1px solid rgba(91, 108, 255, 0.16);
  text-align: left;
  transition: box-shadow 0.25s, border-color 0.25s, transform 0.25s;
}
.prompt-box:focus-within {
  border-color: var(--brand-primary);
  box-shadow: 0 20px 56px rgba(91, 108, 255, 0.26);
  transform: translateY(-2px);
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
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid rgba(20, 22, 40, 0.08);
  border-radius: 18px;
  padding: 7px 15px;
  cursor: pointer;
  transition: all 0.2s;
}
.chip:hover {
  color: var(--brand-primary);
  border-color: var(--brand-primary);
  background: rgba(91, 108, 255, 0.08);
  transform: translateY(-1px);
}

.good-section {
  max-width: 1160px;
  margin: 40px auto 0;
}
.section-title {
  position: relative;
  font-size: 22px;
  font-weight: 700;
  margin: 0 0 18px;
  padding-left: 14px;
  color: var(--text-primary);
}
.section-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 5px;
  height: 20px;
  border-radius: 3px;
  background: var(--brand-gradient);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(248px, 1fr));
  gap: 20px;
}
</style>
