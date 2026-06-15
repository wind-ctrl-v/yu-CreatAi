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

const toolItems = [
  { key: 'type',   label: '应用类型' },
  { key: 'upload', label: '上传参考' },
  { key: 'style',  label: '风格偏好' },
  { key: 'depth',  label: '深度模式' },
]

const suggestions = [
  '企业官网首页',
  '个人博客',
  '待办事项 App',
  '餐厅菜单页',
  '作品集展示',
  '电商商品页',
  '数据大屏',
]

async function loadGoodApps() {
  loadingApps.value = true
  const res = await listGoodAppByPage({ pageNum: 1, pageSize: 12 }).catch(() => null)
  loadingApps.value = false
  if (res?.code === 0) goodApps.value = res.data.records || []
}

async function loadMyApps() {
  loadingMyApps.value = true
  const res = await listMyAppByPage({ pageNum: 1, pageSize: 12 }).catch(() => null)
  loadingMyApps.value = false
  if (res?.code === 0) myApps.value = res.data.records || []
}

async function handleCreate() {
  const initPrompt = prompt.value.trim()
  if (!initPrompt) { message.warning('请先描述你想要生成的网站'); return }
  creating.value = true
  const res = await addApp({ initPrompt }).catch(() => null)
  creating.value = false
  if (res?.code === 0) {
    router.push({ name: 'appChat', params: { id: String(res.data) }, query: { init: '1' } })
  }
}

function useSuggestion(text: string) { prompt.value = text }
function openApp(app: AppVO) { router.push({ name: 'appChat', params: { id: String(app.id) } }) }

onMounted(() => { loadGoodApps(); loadMyApps() })
</script>

<template>
  <div class="home">

    <!-- ── Hero ── -->
    <section class="hero">
      <!-- Pill Badge -->
      <div class="hero-badge">
        <span class="badge-dot" />
        描述一句话，AI 实时生成
      </div>

      <h1 class="title">
        从想法到产品<br />
        <span class="title-accent">只需一句话。</span>
      </h1>
      <p class="subtitle">
        CreatAi 理解你的需求，秒级生成完整代码与交互预览——<br />
        无需任何开发经验。
      </p>

      <!-- Prompt 输入框 -->
      <div class="prompt-wrap">
        <div class="prompt-box">
          <a-textarea
            v-model:value="prompt"
            :auto-size="{ minRows: 3, maxRows: 6 }"
            placeholder="例如：帮我做一个现代风格的咖啡馆官网，包含首页、菜单和联系方式…"
            :bordered="false"
            class="prompt-textarea"
            @keydown.enter.exact.prevent="handleCreate"
          />
          <!-- 工具栏 -->
          <div class="prompt-toolbar">
            <div class="toolbar-left">
              <span v-for="t in toolItems" :key="t.key" class="tool-chip">
                {{ t.label }}
              </span>
            </div>
            <div class="toolbar-right">
              <kbd class="kbd-hint">⏎ Enter</kbd>
              <button
                class="send-btn"
                :class="{ loading: creating }"
                :disabled="creating"
                @click="handleCreate"
              >
                <span v-if="!creating">生成应用</span>
                <span v-else class="btn-spinner" />
              </button>
            </div>
          </div>
        </div>

        <!-- Suggestion chips -->
        <div class="suggestions">
          <button
            v-for="s in suggestions"
            :key="s"
            class="chip"
            @click="useSuggestion(s)"
          >
            {{ s }}
          </button>
        </div>
      </div>
    </section>

    <!-- ── 节分隔线 ── -->
    <div class="section-divider" />

    <!-- ── 我的作品 ── -->
    <section v-if="myApps.length || loadingMyApps" class="card-section">
      <div class="section-header">
        <h2 class="section-title">我的作品</h2>
        <span class="section-count">{{ myApps.length }} 个</span>
      </div>
      <a-spin :spinning="loadingMyApps">
        <div v-if="myApps.length" class="grid">
          <AppCard v-for="app in myApps" :key="app.id" :app="app" @open="openApp" />
        </div>
        <div v-else-if="!loadingMyApps" class="empty-state">
          还没有作品，从上面开始创作吧
        </div>
      </a-spin>
    </section>

    <!-- ── 精选应用 ── -->
    <section class="card-section">
      <div class="section-header">
        <h2 class="section-title">精选应用</h2>
        <span class="section-count">{{ goodApps.length }} 个</span>
      </div>
      <a-spin :spinning="loadingApps">
        <div v-if="goodApps.length" class="grid">
          <AppCard v-for="app in goodApps" :key="app.id" :app="app" @open="openApp" />
        </div>
        <div v-else-if="!loadingApps" class="empty-state">暂无精选应用</div>
      </a-spin>
    </section>

  </div>
</template>

<style scoped>
/* ── 页面容器 ── */
.home {
  flex: 1;
  overflow-y: auto;
  padding: 0 48px 96px;
  background: var(--bg-page);
}

/* ── Hero ── */
.hero {
  max-width: 680px;
  margin: 0 auto;
  padding: 96px 0 64px;
  text-align: center;
}

/* Badge */
.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--gray-600);
  background: var(--geist-white);
  box-shadow: var(--shadow-ring);
  border-radius: var(--r-pill);
  padding: 4px 12px;
  margin-bottom: 32px;
  letter-spacing: 0.01em;
}
.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  animation: pulse 2s ease infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50%       { opacity: 0.5; transform: scale(0.8); }
}

/* 标题 */
.title {
  font-size: 52px;
  font-weight: 600;
  line-height: 1.08;
  letter-spacing: var(--tracking-display);
  color: var(--geist-black);
  margin: 0 0 20px;
}
.title-accent {
  color: var(--gray-400);
}

.subtitle {
  font-size: 16px;
  font-weight: 400;
  color: var(--gray-600);
  line-height: 1.75;
  margin: 0 0 40px;
}

/* ── Prompt 区 ── */
.prompt-wrap {
  max-width: 640px;
  margin: 0 auto;
}

.prompt-box {
  background: var(--geist-white);
  border-radius: var(--r-image);
  padding: 14px 14px 10px;
  box-shadow: var(--shadow-card);
  text-align: left;
  transition: box-shadow var(--duration-base) var(--smooth);
}
.prompt-box:focus-within {
  box-shadow:
    rgba(0,0,0,0.1) 0px 0px 0px 1px,
    rgba(0,0,0,0.06) 0px 4px 8px,
    rgba(0,0,0,0.04) 0px 16px 24px -8px,
    rgba(10,114,239,0.12) 0px 0px 0px 3px,
    #fafafa 0px 0px 0px 1px;
}

.prompt-textarea :deep(textarea) {
  font-size: 14px;
  font-weight: 400;
  line-height: 1.7;
  color: var(--geist-black);
  resize: none;
  font-family: inherit;
}
.prompt-textarea :deep(textarea::placeholder) {
  color: var(--gray-400);
}

.prompt-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--gray-100);
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

/* B09 半透明发光按钮风格 tool chip */
.tool-chip {
  font-size: 11.5px;
  font-weight: 500;
  color: var(--gray-500);
  background: transparent;
  box-shadow: var(--shadow-ring);
  border-radius: var(--r-base);
  padding: 3px 8px;
  cursor: pointer;
  user-select: none;
  transition: all var(--duration-fast) var(--spring);
  letter-spacing: var(--tracking-label);
}
.tool-chip:hover {
  background: var(--gray-50);
  color: var(--geist-black);
  box-shadow: var(--shadow-border);
  transform: translateY(-1px) scale(1.02);
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.kbd-hint {
  font-size: 11px;
  color: var(--gray-400);
  background: var(--gray-50);
  border-radius: var(--r-subtle);
  padding: 2px 6px;
  font-family: inherit;
  box-shadow: var(--shadow-ring);
  letter-spacing: 0;
}

/* E05 弹性反馈主按钮 */
.send-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 32px;
  padding: 0 14px;
  border-radius: var(--r-base);
  border: none;
  background: var(--geist-black);
  color: var(--geist-white);
  font-size: 13px;
  font-weight: 500;
  letter-spacing: var(--tracking-label);
  cursor: pointer;
  font-family: inherit;
  transition: all var(--duration-fast) var(--spring);
}
.send-btn:hover:not(:disabled) {
  background: #2a2a2a;
  transform: translateY(-1px) scale(1.02);
  box-shadow: rgba(0,0,0,0.15) 0px 4px 12px;
}
.send-btn:active:not(:disabled) {
  transform: scale(0.97);
  transition-duration: 0.08s;
}
.send-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-spinner {
  width: 13px;
  height: 13px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* Suggestions */
.suggestions {
  display: flex;
  gap: 6px;
  margin-top: 12px;
  overflow-x: auto;
  padding-bottom: 2px;
  scrollbar-width: none;
}
.suggestions::-webkit-scrollbar { display: none; }

.chip {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 500;
  color: var(--gray-600);
  background: var(--geist-white);
  box-shadow: var(--shadow-ring);
  border-radius: var(--r-pill);
  padding: 5px 12px;
  border: none;
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
  letter-spacing: var(--tracking-label);
  transition: all var(--duration-fast) var(--spring);
}
.chip:hover {
  background: var(--geist-black);
  color: var(--geist-white);
  box-shadow: none;
  transform: translateY(-1px) scale(1.03);
}

/* ── 分隔线 ── */
.section-divider {
  max-width: 1200px;
  margin: 0 auto;
  height: 1px;
  background: var(--gray-100);
}

/* ── 卡片区 ── */
.card-section {
  max-width: 1200px;
  margin: 64px auto 0;
}

.section-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 24px;
}

.section-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--geist-black);
  margin: 0;
  letter-spacing: var(--tracking-heading);
}

.section-count {
  font-size: 12px;
  font-weight: 500;
  color: var(--gray-400);
  background: var(--gray-50);
  box-shadow: var(--shadow-ring);
  border-radius: var(--r-pill);
  padding: 2px 8px;
  letter-spacing: 0;
}

.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.empty-state {
  font-size: 14px;
  color: var(--gray-400);
  padding: 40px 0;
  text-align: center;
}

@media (max-width: 960px) {
  .grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 580px) {
  .home { padding: 0 20px 64px; }
  .title { font-size: 36px; }
  .grid { grid-template-columns: 1fr; }
  .hero { padding: 64px 0 48px; }
}
</style>
