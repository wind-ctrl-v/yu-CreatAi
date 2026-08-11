<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { addApp, listGoodAppByPage, listMyAppByPage } from '../api/app'
import AppCard from '../components/AppCard.vue'
import CButton from '../components/ui/CButton.vue'
import CEmpty from '../components/ui/CEmpty.vue'
import CIcon from '../components/ui/CIcon.vue'
import CSelect from '../components/ui/CSelect.vue'
import CSpin from '../components/ui/CSpin.vue'
import CTextarea from '../components/ui/CTextarea.vue'
import { message } from '../components/ui/toast'
import { useAppPage } from '../composables/useAppPage'
import type { AppVO } from '../types'

const router = useRouter()

const prompt = ref('')
const creating = ref(false)
const genMode = ref<string>('standard')
const visualStyle = ref('brand')
const layoutDirection = ref('adaptive')
const noveltyLevel = ref('distinctive')

const {
  records: goodApps,
  loading: loadingApps,
  load: loadGoodApps,
} = useAppPage(listGoodAppByPage, 6)
const {
  records: myApps,
  loading: loadingMyApps,
  load: loadMyApps,
} = useAppPage(listMyAppByPage, 6)

const modeOptions = [
  { value: 'low_cost', label: '快速', desc: '响应最快，适合草稿验证', icon: 'activity' },
  { value: 'standard', label: '标准', desc: '速度与质量均衡', icon: 'layers' },
  { value: 'high_quality', label: '高质量', desc: '细节最完整，耗时更长', icon: 'sparkles' },
]

const visualStyleOptions = [
  { value: 'brand', label: '品牌驱动', desc: '从行业和品牌内容提炼专属视觉语言' },
  { value: 'editorial', label: '编辑杂志', desc: '强调排版、内容节奏和视觉层级' },
  { value: 'minimal', label: '精致克制', desc: '少装饰、强秩序与高质量留白' },
  { value: 'digital', label: '数字实验', desc: '更鲜明的数字感、几何感和动效表达' },
  { value: 'humanist', label: '复古人文', desc: '温暖、叙事化并带有手工质感' },
]

const layoutDirectionOptions = [
  { value: 'adaptive', label: '智能布局', desc: '根据内容选择差异化页面骨架' },
  { value: 'asymmetric', label: '非对称网格', desc: '错位构图和不等宽内容组织' },
  { value: 'split', label: '分屏叙事', desc: '左右分屏并突出主要视觉焦点' },
  { value: 'editorial', label: '编辑排版', desc: '目录、栏目和长短内容混排' },
  { value: 'immersive', label: '沉浸媒体', desc: '以大图、视频感区域和场景切换为主' },
  { value: 'data-story', label: '数据叙事', desc: '统计带、时间线和数据模块组合' },
]

const noveltyOptions = [
  { value: 'safe', label: '稳妥', desc: '保持熟悉体验，同时避免模板化' },
  { value: 'distinctive', label: '差异化', desc: '明显区别于常见同类网站' },
  { value: 'experimental', label: '实验性', desc: '允许更大胆的构图和交互表达' },
]

const visualStyleBriefs: Record<string, string> = {
  brand: '品牌驱动，从行业、受众和内容中提炼专属视觉语言',
  editorial: '编辑杂志感，突出字体层级、栏目节奏和内容叙事',
  minimal: '精致克制，减少装饰并通过留白和细节建立质感',
  digital: '数字实验感，使用鲜明几何秩序与适度动态表达',
  humanist: '复古人文感，强调温暖色调、故事性和手工质感',
}

const layoutBriefs: Record<string, string> = {
  adaptive: '根据内容自适应选择骨架，避免固定 Hero 加三栏卡片结构',
  asymmetric: '使用非对称网格和错位视觉重心',
  split: '使用分屏叙事，并通过左右区域形成清晰焦点',
  editorial: '采用编辑式排版，组合目录、栏目、长短内容和跨栏元素',
  immersive: '采用沉浸式媒体布局，以大幅视觉区域组织浏览节奏',
  'data-story': '采用数据叙事布局，组合统计带、时间线和信息模块',
}

const noveltyBriefs: Record<string, string> = {
  safe: '稳妥创新，保留熟悉交互但不能照搬常见模板',
  distinctive: '明显差异化，至少使用两种不同的信息组织方式并强化品牌辨识度',
  experimental: '实验性表达，允许大胆构图与交互，但必须保证可读性和可用性',
}

const suggestions = [
  { text: '企业官网首页', icon: 'globe' },
  { text: '个人博客', icon: 'file' },
  { text: '待办事项 App', icon: 'check-circle' },
  { text: '餐厅菜单页', icon: 'layers' },
  { text: '作品集展示', icon: 'image' },
  { text: '电商商品页', icon: 'grid' },
  { text: '数据大屏', icon: 'trending-up' },
]

const capabilities = [
  { icon: 'sparkles', title: '一句话生成', desc: '自然语言描述需求，AI 输出可运行的完整项目代码。' },
  { icon: 'monitor', title: '实时预览', desc: '生成过程边写边看，所见即所得地调整每一处细节。' },
  { icon: 'rocket', title: '一键部署', desc: '确认效果后立即发布上线，并可下载完整源码。' },
]

async function handleCreate() {
  const basePrompt = prompt.value.trim()
  if (!basePrompt) {
    message.warning('请先描述你想要生成的网站')
    return
  }
  const creativeBrief = [
    visualStyleBriefs[visualStyle.value],
    layoutBriefs[layoutDirection.value],
    noveltyBriefs[noveltyLevel.value],
  ].filter(Boolean).join('；')
  const initPrompt = `${basePrompt}\n\n【创意方向】${creativeBrief}。请将这些要求落实到页面结构、排版、色彩、组件和媒体语言中。`
  creating.value = true
  const res = await addApp({ initPrompt }).catch(() => null)
  creating.value = false
  if (res?.code === 0) {
    router.push({
      name: 'appChat',
      params: { id: String(res.data) },
      query: { init: '1', mode: genMode.value },
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
    <!-- ── Hero ── -->
    <section class="hero">
      <div class="hero__bg surface-dots" aria-hidden="true" />

      <span class="hero__badge">
        <span class="hero__badge-dot" />
        描述一句话，AI 实时生成
      </span>

      <h1 class="hero__title">
        从想法到产品，<br />
        <span class="hero__title-soft">只需一句话。</span>
      </h1>

      <p class="hero__sub">
        CreatAi 理解你的需求，秒级生成完整代码与交互预览，无需任何开发经验。
      </p>

      <!-- Prompt 卡片 -->
      <div class="composer">
        <CTextarea
          v-model="prompt"
          bare
          autosize
          :rows="3"
          :max-rows="8"
          placeholder="例如：帮我做一个现代风格的咖啡馆官网，包含首页、菜单和联系方式…"
          @submit="handleCreate"
        />

        <div class="composer__controls" aria-label="创意方向">
          <label class="composer__control">
            <span>视觉风格</span>
            <CSelect
              v-model="visualStyle"
              :options="visualStyleOptions"
              size="sm"
              width="100%"
              menu-width="220px"
              placement="top"
            />
          </label>
          <label class="composer__control">
            <span>布局方向</span>
            <CSelect
              v-model="layoutDirection"
              :options="layoutDirectionOptions"
              size="sm"
              width="100%"
              menu-width="220px"
              placement="top"
            />
          </label>
          <label class="composer__control">
            <span>创意程度</span>
            <CSelect
              v-model="noveltyLevel"
              :options="noveltyOptions"
              size="sm"
              width="100%"
              menu-width="210px"
              placement="top"
              align="right"
            />
          </label>
        </div>

        <div class="composer__bar">
          <div class="composer__left">
            <CSelect
              v-model="genMode"
              :options="modeOptions"
              bare
              size="sm"
              width="112px"
              menu-width="248px"
              align="left"
            />
            <span class="composer__hint">
              <kbd>⌘</kbd><kbd>⏎</kbd> 快捷生成
            </span>
          </div>

          <CButton
            variant="primary"
            :loading="creating"
            :disabled="!prompt.trim()"
            icon-right="arrow-up"
            pill
            @click="handleCreate"
          >
            生成应用
          </CButton>
        </div>
      </div>

      <!-- 建议 -->
      <div class="chips">
        <button
          v-for="s in suggestions"
          :key="s.text"
          class="chip"
          type="button"
          @click="useSuggestion(s.text)"
        >
          <CIcon :name="s.icon" :size="13" />
          {{ s.text }}
        </button>
      </div>
    </section>

    <!-- ── 能力说明 ── -->
    <section class="caps container">
      <article v-for="c in capabilities" :key="c.title" class="cap">
        <span class="cap__icon"><CIcon :name="c.icon" :size="17" /></span>
        <h3 class="cap__title">{{ c.title }}</h3>
        <p class="cap__desc">{{ c.desc }}</p>
      </article>
    </section>

    <!-- ── 我的作品 ── -->
    <section v-if="myApps.length || loadingMyApps" class="section container">
      <header class="section__head">
        <h2 class="section__title">我的作品</h2>
        <span class="section__count">{{ myApps.length }}</span>
        <CButton variant="text" size="sm" icon-right="arrow-right" @click="router.push('/apps')">
          查看全部
        </CButton>
      </header>
      <CSpin :spinning="loadingMyApps">
        <div v-if="myApps.length" class="grid">
          <AppCard v-for="app in myApps" :key="app.id" :app="app" @open="openApp" />
        </div>
        <CEmpty
          v-else-if="!loadingMyApps"
          icon="sparkles"
          description="还没有作品"
          hint="在上方输入你的想法，几秒钟就能得到第一个应用。"
          size="sm"
        />
      </CSpin>
    </section>

    <!-- ── 精选应用 ── -->
    <section class="section container">
      <header class="section__head">
        <h2 class="section__title">精选应用</h2>
        <span class="section__count">{{ goodApps.length }}</span>
        <CButton variant="text" size="sm" icon-right="arrow-right" @click="router.push('/explore')">
          查看全部
        </CButton>
      </header>
      <CSpin :spinning="loadingApps">
        <div v-if="goodApps.length" class="grid">
          <AppCard v-for="app in goodApps" :key="app.id" :app="app" @open="openApp" />
        </div>
        <CEmpty v-else-if="!loadingApps" description="暂无精选应用" size="sm" />
      </CSpin>
    </section>
  </div>
</template>

<style scoped>
.home {
  flex: 1;
  padding-bottom: var(--sp-24);
}

/* ── Hero ── */
.hero {
  position: relative;
  max-width: 780px;
  margin: 0 auto;
  padding: var(--sp-20) var(--sp-6) var(--sp-12);
  text-align: center;
}

.hero__bg {
  position: absolute;
  inset: -20px -80px auto;
  height: 340px;
  opacity: 0.5;
  mask-image: radial-gradient(ellipse 60% 60% at 50% 30%, #000 20%, transparent 75%);
  -webkit-mask-image: radial-gradient(ellipse 60% 60% at 50% 30%, #000 20%, transparent 75%);
  pointer-events: none;
  z-index: 0;
}

/* 底纹之上的常规内容：不设 z-index，按文档顺序层叠 */
.hero > *:not(.hero__bg) { position: relative; }

.hero__badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 28px;
  padding: 0 13px;
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  background: var(--bg-surface);
  box-shadow: var(--shadow-xs);
  font-size: var(--fs-xs);
  font-weight: 450;
  color: var(--text-secondary);
  animation: fade-up 0.5s var(--ease-out) both;
}

.hero__badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--spark);
  animation: pulse-soft 2.4s var(--ease-in-out) infinite;
}

.hero__title {
  margin: var(--sp-6) 0 0;
  font-size: var(--fs-display);
  font-weight: 600;
  line-height: var(--lh-tight);
  letter-spacing: var(--tracking-display);
  color: var(--text-strong);
  animation: fade-up 0.55s 0.04s var(--ease-out) both;
}

.hero__title-soft { color: var(--n-400); }

.hero__sub {
  max-width: 520px;
  margin: var(--sp-5) auto 0;
  font-size: var(--fs-lg);
  line-height: 1.65;
  color: var(--text-secondary);
  animation: fade-up 0.6s 0.08s var(--ease-out) both;
}

/* ── Prompt 卡片 ── */
.composer {
  position: relative;
  /* 高于下方的建议 chips，避免模式下拉被覆盖 */
  z-index: 20;
  max-width: 680px;
  margin: var(--sp-10) auto 0;
  padding: 16px 16px 12px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--r-2xl);
  box-shadow: var(--shadow-md);
  text-align: left;
  transition:
    border-color var(--t-base) var(--ease-out),
    box-shadow var(--t-base) var(--ease-out);
  animation: fade-up 0.65s 0.12s var(--ease-out) both;
}

.composer:focus-within {
  border-color: var(--n-400);
  box-shadow: var(--shadow-lg);
}

.composer__controls {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-subtle);
}

.composer__control {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.composer__control > span {
  padding-left: 2px;
  font-size: var(--fs-2xs);
  font-weight: 550;
  color: var(--text-muted);
}

.composer__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding-top: 11px;
  border-top: 1px solid var(--border-subtle);
}

.composer__left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.composer__hint {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-2xs);
  color: var(--text-faint);
}

.composer__hint kbd {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-xs);
  background: var(--n-50);
  font-family: var(--font-sans);
  font-size: 10.5px;
  color: var(--text-muted);
}

/* ── 建议 chips ── */
.chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 7px;
  margin-top: var(--sp-5);
  animation: fade-up 0.7s 0.16s var(--ease-out) both;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-pill);
  background: var(--bg-surface);
  font-size: var(--fs-xs);
  font-weight: 450;
  color: var(--text-secondary);
  cursor: pointer;
  white-space: nowrap;
  transition: all var(--t-fast) var(--ease-out);
}

.chip:hover {
  border-color: var(--n-900);
  color: var(--text-strong);
  transform: translateY(-1px);
}

.chip :deep(.c-icon) { color: var(--text-faint); }
.chip:hover :deep(.c-icon) { color: var(--text-primary); }

/* ── 能力 ── */
.caps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  margin-top: var(--sp-10);
  padding-top: var(--sp-10);
  border-top: 1px solid var(--border-subtle);
}

.cap {
  padding: 0 var(--sp-6);
  border-left: 1px solid var(--border-subtle);
}

.cap:first-child { border-left: none; padding-left: 0; }

.cap__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: var(--r-md);
  background: var(--n-100);
  border: 1px solid var(--border-subtle);
  color: var(--text-primary);
}

.cap__title {
  margin-top: 13px;
  font-size: var(--fs-base);
  font-weight: 550;
  color: var(--text-strong);
}

.cap__desc {
  margin-top: 6px;
  font-size: var(--fs-sm);
  line-height: 1.65;
  color: var(--text-muted);
}

/* ── 列表区 ── */
.section {
  margin-top: var(--sp-16);
}

.section__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: var(--sp-5);
}

.section__title {
  font-size: var(--fs-h3);
  font-weight: 600;
  letter-spacing: var(--tracking-heading);
  color: var(--text-strong);
}

.section__count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 20px;
  padding: 0 6px;
  border-radius: var(--r-pill);
  background: var(--n-100);
  border: 1px solid var(--border-subtle);
  font-size: var(--fs-2xs);
  font-weight: 600;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.section__head :deep(.c-btn) { margin-left: auto; }

.grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--sp-4);
}

@media (max-width: 980px) {
  .grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .caps { grid-template-columns: 1fr; gap: var(--sp-6); }
  .cap { border-left: none; padding: 0; }
}

@media (max-width: 600px) {
  .hero { padding: var(--sp-12) var(--sp-4) var(--sp-8); }
  .hero__sub { font-size: var(--fs-base); }
  .composer { padding: 14px 14px 10px; border-radius: var(--r-xl); }
  .composer__controls { grid-template-columns: 1fr; }
  .composer__hint { display: none; }
  .grid { grid-template-columns: 1fr; }
  .section { margin-top: var(--sp-12); }
}
</style>
