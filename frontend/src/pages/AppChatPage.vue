<script setup lang="ts">
import { computed, h, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  buildDownloadUrl,
  buildPreviewUrl,
  deployApp,
  getAppVOById,
} from '../api/app'
import { listAppChatHistory } from '../api/chatHistory'
import { getPlanCapabilities } from '../api/user'
import { connectChatSSE } from '../utils/sse'
import {
  attachElementPicker,
  buildEditContext,
  type PickedElement,
} from '../utils/visualEditor'
import type { AppVO } from '../types'
import { PLAN_LABELS, type PlanCapabilities } from '../types'

interface ChatMessage {
  id: number
  type: 'user' | 'ai'
  content: string
}

/** 生成模式 → 中文文案（与模式选择器 options 文案统一） */
const MODE_LABELS: Record<string, string> = {
  low_cost: '快速',
  standard: '标准',
  high_quality: '高质量',
}
function modeLabel(mode?: string): string {
  return (mode && MODE_LABELS[mode]) || mode || '标准'
}

/** 本轮生成的结构化运行信息 */
interface GenerationRunInfo {
  /** 实际生效模式 */
  mode: string
  /** 实际链路 */
  pipeline: string
  /** 降级提示（发生降级时） */
  fallbackText?: string
}

const route = useRoute()
const router = useRouter()
// 用 computed 让应用切换（路由参数变化）时能够响应
// 注意：后端雪花 ID 为 18 位，JS Number 会丢失精度，必须用 String
const appId = computed(() => String(route.params.id))
// 本地消息自增 id，保证 v-for key 唯一且稳定
let msgSeq = 0

const app = ref<AppVO | null>(null)
const messages = ref<ChatMessage[]>([])
const input = ref('')
const generating = ref(false)
const deploying = ref(false)
// 生成模式意图（low_cost/standard/high_quality），后端按套餐封顶
const genMode = ref<string>('standard')
// 本轮生成的结构化运行信息（生效模式/链路/降级提示），由 SSE 结构化事件填充
const runInfo = ref<GenerationRunInfo | null>(null)
// 套餐能力（拉取一次，整个会话复用）
const planCaps = ref<PlanCapabilities | null>(null)

const genModeOptions = computed(() => {
  const available = planCaps.value?.availableModes || ['low_cost', 'standard', 'high_quality']
  const allModes = [
    { value: 'low_cost', label: '快速生成', rank: 1 },
    { value: 'standard', label: '标准生成', rank: 2 },
    { value: 'high_quality', label: '高质量生成', rank: 3 },
  ]
  return allModes.map(m => ({
    ...m,
    disabled: !available.includes(m.value),
    // 不可用时追加说明
    label: available.includes(m.value) ? m.label : `${m.label}（需升级套餐）`,
  }))
})

const previewUrl = ref('')
const previewKey = ref(0) // 用于强制刷新 iframe
const deployUrl = ref('')

const chatListRef = ref<HTMLElement | null>(null)
const previewIframeRef = ref<HTMLIFrameElement | null>(null)
let closeSSE: (() => void) | null = null

// —— 可视化修改 ——
const selecting = ref(false) // 是否处于元素选择模式
const picked = ref<PickedElement | null>(null) // 当前选中的元素
let pickerDetach: (() => void) | null = null

function attachPicker() {
  if (!selecting.value) return
  const iframe = previewIframeRef.value
  if (!iframe) return
  pickerDetach?.()
  pickerDetach = attachElementPicker(iframe, (el) => {
    picked.value = el
  })
  if (!pickerDetach) {
    message.warning('当前预览暂不支持可视化选择')
    selecting.value = false
  }
}

function exitSelecting() {
  selecting.value = false
  pickerDetach?.()
  pickerDetach = null
}

function toggleSelecting() {
  if (!canPreview.value) {
    message.warning('请先生成页面后再选择元素')
    return
  }
  if (selecting.value) {
    exitSelecting()
  } else {
    selecting.value = true
    nextTick(attachPicker)
  }
}

function clearPicked() {
  picked.value = null
}

function onIframeLoad() {
  if (selecting.value) nextTick(attachPicker)
}

const appName = computed(() => app.value?.appName || `应用 #${appId.value}`)
const canPreview = computed(() => !!previewUrl.value)

function scrollToBottom() {
  nextTick(() => {
    const el = chatListRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function refreshPreview() {
  if (!app.value) return
  previewUrl.value = buildPreviewUrl(app.value.codeGenType, appId.value)
  previewKey.value += 1
}

function openPreviewInNewTab() {
  if (previewUrl.value) window.open(previewUrl.value, '_blank')
}

async function loadApp() {
  const res = await getAppVOById(appId.value).catch(() => null)
  if (res && res.code === 0) {
    app.value = res.data
  } else {
    message.error('应用不存在')
    router.replace('/')
  }
}

async function loadHistory() {
  const res = await listAppChatHistory(appId.value, 20).catch(() => null)
  if (res && res.code === 0) {
    // 后端按创建时间倒序返回，需反转为正序展示
    const records = [...(res.data.records || [])].reverse()
    messages.value = records.map((r) => ({
      id: ++msgSeq,
      type: r.messageType === 'ai' ? 'ai' : 'user',
      content: r.message,
    }))
    scrollToBottom()
  }
}

function sendMessage(content: string, displayText?: string) {
  const real = content.trim()
  if (!real || generating.value) return

  messages.value.push({ id: ++msgSeq, type: 'user', content: displayText ?? real })
  messages.value.push({ id: ++msgSeq, type: 'ai', content: '' })
  // 记录 AI 消息索引，通过响应式代理修改以触发视图更新
  const aiIndex = messages.value.length - 1
  generating.value = true
  // 重置上一轮的结构化运行信息
  runInfo.value = null
  scrollToBottom()

  closeSSE = connectChatSSE(
    appId.value,
    real,
    {
    onChunk: (chunk) => {
      messages.value[aiIndex].content += chunk
      scrollToBottom()
    },
    onGenerationStarted: (event) => {
      // 展示后端封顶后实际生效的模式与链路
      runInfo.value = {
        mode: event.mode,
        pipeline: event.pipeline,
        fallbackText: runInfo.value?.fallbackText,
      }
    },
    onPipelineFallback: (event) => {
      const reason = (event.reason || '').slice(0, 60)
      const text = `已从 ${event.from} 链路自动切换至 ${event.to} 链路${
        reason ? `（${reason}）` : ''
      }`
      message.warning(text)
      runInfo.value = {
        mode: runInfo.value?.mode || genMode.value,
        pipeline: event.to,
        fallbackText: text,
      }
    },
    onDone: () => {
      generating.value = false
      refreshPreview()
    },
    onBusinessError: ({ code, message: errMsg }) => {
      generating.value = false
      // 会话失效：提示并引导重新登录
      if (code === 40100) {
        messages.value[aiIndex].content = '⚠️ 登录已失效，请重新登录后重试。'
        message.error('登录已失效，请重新登录')
        return
      }
      // 限流：提醒等待后重试
      if (code === 42900) {
        messages.value[aiIndex].content = '⚠️ 请求过于频繁，请稍等片刻后重试（每60秒最多5次）。'
        message.warning('AI 对话请求过于频繁，请稍后再试')
        return
      }
      messages.value[aiIndex].content = `⚠️ ${errMsg}`
      message.error(errMsg)
    },
    onError: () => {
      generating.value = false
      if (!messages.value[aiIndex].content) {
        messages.value[aiIndex].content = '⚠️ 生成中断，请重试。'
      }
    },
    },
    genMode.value,
  )
}

function handleSend() {
  const userText = input.value.trim()
  input.value = ''
  if (picked.value) {
    const target = picked.value
    const real =
      buildEditContext(target) + (userText || '请优化这个元素的样式与交互体验')
    const display = `🎯 已选中 <${target.tagName}>${
      target.text ? ` “${target.text.slice(0, 20)}”` : ''
    }\n${userText || '请优化这个元素的样式与交互体验'}`
    clearPicked()
    exitSelecting()
    sendMessage(real, display)
  } else {
    sendMessage(userText)
  }
}

async function handleDeploy() {
  deploying.value = true
  const res = await deployApp(appId.value).catch(() => null)
  deploying.value = false
  if (res && res.code === 0) {
    deployUrl.value = res.data
    Modal.success({
      title: '部署成功 🎉',
      content: h('div', { style: 'display:flex;flex-direction:column;gap:12px;' }, [
        h('div', { style: 'word-break:break-all;font-size:13px;color:var(--gray-600);' }, [
          h('span', '访问地址：'),
          h('a', { href: deployUrl.value, target: '_blank', style: 'color:var(--accent);' }, deployUrl.value),
        ]),
        h(
          'a-button',
          {
            type: 'default',
            size: 'small',
            onClick: async () => {
              try {
                await navigator.clipboard.writeText(deployUrl.value)
                message.success('已复制到剪贴板')
              } catch {
                message.error('复制失败，请手动选中复制')
              }
            },
          },
          () => '📋 复制链接',
        ),
      ]),
    })
  }
}

function handleDownload() {
  window.open(buildDownloadUrl(appId.value), '_blank')
}

// 初始化 / 切换应用时重新加载
async function init() {
  // 切换应用前清理上一次的 SSE 连接与状态
  closeSSE?.()
  closeSSE = null
  exitSelecting()
  clearPicked()
  generating.value = false
  messages.value = []
  app.value = null
  previewUrl.value = ''
  deployUrl.value = ''
  runInfo.value = null
  // 拉取套餐能力（应用切换不重复拉，只在进入时首次获取）
  if (!planCaps.value) {
    const res = await getPlanCapabilities().catch(() => null)
    if (res?.code === 0) planCaps.value = res.data
  }
  // 从首页 ?mode= 参数读取并预设生成模式，但不能超过套餐上限
  const queriedMode = route.query.mode as string | undefined
  const available = planCaps.value?.availableModes || ['low_cost', 'standard', 'high_quality']
  if (queriedMode && available.includes(queriedMode)) {
    genMode.value = queriedMode
  } else if (!available.includes(genMode.value)) {
    // 当前模式超出套餐范围时自动降级
    genMode.value = available[available.length - 1] || 'low_cost'
  }

  await loadApp()
  await loadHistory()
  // 从首页带 init=1 进入：自动发起首轮生成
  // 显式取出当前应用，避免 TS 因前面 app.value=null 的控制流收窄误判为 never
  const loadedApp = app.value as AppVO | null
  if (route.query.init === '1' && loadedApp?.initPrompt) {
    sendMessage(loadedApp.initPrompt)
    router.replace({ name: 'appChat', params: { id: appId.value } })
  } else {
    refreshPreview()
  }
}

onMounted(init)
// 同组件复用时（/app/chat/:id 切换 id），重新加载对应应用
watch(
  () => route.params.id,
  (id, prevId) => {
    if (id && id !== prevId) init()
  },
)

onBeforeUnmount(() => {
  closeSSE?.()
  pickerDetach?.()
})
</script>

<template>
  <div class="chat-page">
    <div class="toolbar">
      <div class="left">
        <a-button type="text" @click="router.push('/')">← 返回</a-button>
        <span class="app-name">{{ appName }}</span>
        <a-tag v-if="app" color="blue">{{ app.codeGenType }}</a-tag>
        <a-tag v-if="runInfo" color="purple">
          {{ modeLabel(runInfo.mode) }} · {{ runInfo.pipeline }}
        </a-tag>
      </div>
      <div class="right">
        <a-button @click="handleDownload">下载代码</a-button>
        <a-button type="primary" :loading="deploying" @click="handleDeploy">
          部署
        </a-button>
      </div>
    </div>

    <div class="body">
      <!-- 左：对话区 -->
      <section class="chat-panel">
        <div ref="chatListRef" class="chat-list">
          <div v-if="runInfo?.fallbackText" class="fallback-bar">
            <span class="fallback-icon">⚠️</span>
            <span class="fallback-text">{{ runInfo.fallbackText }}</span>
          </div>
          <div
            v-for="m in messages"
            :key="m.id"
            class="msg-row"
            :class="m.type"
          >
            <a-avatar
              class="avatar"
              :style="{
                background:
                  m.type === 'ai'
                    ? 'linear-gradient(135deg, #5b6cff, #7c5cff)'
                    : 'linear-gradient(135deg, #36cfc9, #52c41a)',
              }"
            >
              {{ m.type === 'ai' ? 'AI' : '我' }}
            </a-avatar>
            <div class="bubble">
              <pre v-if="m.content">{{ m.content }}</pre>
              <span v-else-if="m.type === 'ai' && generating" class="typing"
                >正在生成<i></i><i></i><i></i></span
              >
            </div>
          </div>
          <a-empty
            v-if="!messages.length"
            description="开始你的第一句需求描述吧"
          />
        </div>

        <div class="chat-input">
          <div v-if="picked" class="picked-bar">
            <span class="picked-tag">🎯 {{ picked.tagName }}</span>
            <span class="picked-text">{{
              picked.text || picked.selector
            }}</span>
            <a-button type="text" size="small" @click="clearPicked">✕</a-button>
          </div>
          <div class="chat-input-row">
            <a-select
              v-model:value="genMode"
              size="small"
              :disabled="generating"
              class="gen-mode-select"
              :options="genModeOptions"
            />
            <a-textarea
              v-model:value="input"
              :auto-size="{ minRows: 1, maxRows: 4 }"
              :placeholder="
                picked
                  ? '描述如何修改选中的元素，回车发送'
                  : '继续描述需求，回车发送'
              "
              :disabled="generating"
              @keydown.enter.exact.prevent="handleSend"
            />
            <a-button
              type="primary"
              :loading="generating"
              :disabled="!input.trim() && !picked"
              @click="handleSend"
            >
              发送
            </a-button>
          </div>
        </div>
      </section>

      <!-- 右：预览区 -->
      <section class="preview-panel">
        <div class="preview-head">
          <span class="preview-title">实时预览</span>
          <div class="preview-tools">
            <a-button
              :type="selecting ? 'primary' : 'default'"
              size="small"
              :disabled="!canPreview"
              @click="toggleSelecting"
            >
              {{ selecting ? '✓ 选择中…点击页面元素' : '🎯 选择元素修改' }}
            </a-button>
            <a-button
              type="link"
              size="small"
              :disabled="!canPreview"
              @click="openPreviewInNewTab"
            >
              在新窗口打开
            </a-button>
          </div>
        </div>
        <div class="preview-body" :class="{ selecting }">
          <iframe
            v-if="canPreview"
            ref="previewIframeRef"
            :key="previewKey"
            :src="previewUrl"
            sandbox="allow-scripts allow-forms allow-popups allow-same-origin"
            @load="onIframeLoad"
          />
          <div v-else class="preview-empty">
            生成完成后，这里将实时展示你的网站
          </div>
          <div v-if="selecting" class="selecting-hint">
            选择模式已开启：将鼠标移到页面上，点击你想修改的元素
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.chat-page {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid rgba(20, 22, 40, 0.06);
}
.toolbar .left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.app-name {
  font-size: 16px;
  font-weight: 600;
}
.toolbar .right {
  display: flex;
  gap: 10px;
}

.body {
  flex: 1;
  display: flex;
  min-height: 0;
  gap: 16px;
  padding: 16px 24px;
}

.chat-panel,
.preview-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--bg-card);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
  overflow: hidden;
}

.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 18px;
}
.fallback-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  padding: 8px 12px;
  background: rgba(250, 173, 20, 0.1);
  border: 1px solid rgba(250, 173, 20, 0.35);
  border-radius: 10px;
  font-size: 12px;
  color: var(--text-secondary);
}
.fallback-icon {
  flex-shrink: 0;
}
.fallback-text {
  flex: 1;
  min-width: 0;
}
.msg-row {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
}
.msg-row.user {
  flex-direction: row-reverse;
}
.avatar {
  flex-shrink: 0;
  color: #fff;
  font-size: 12px;
}
.bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 12px;
  background: #f5f7fa;
  font-size: 14px;
  line-height: 1.6;
}
.msg-row.user .bubble {
  background: rgba(91, 108, 255, 0.1);
}
.bubble pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'SFMono-Regular', Consolas, monospace;
}

.typing i {
  display: inline-block;
  width: 5px;
  height: 5px;
  margin-left: 3px;
  border-radius: 50%;
  background: var(--brand-primary);
  animation: blink 1.2s infinite both;
}
.typing i:nth-child(2) {
  animation-delay: 0.2s;
}
.typing i:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes blink {
  0%,
  80%,
  100% {
    opacity: 0.2;
  }
  40% {
    opacity: 1;
  }
}

.chat-input {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}
.chat-input-row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}
.picked-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px 6px 12px;
  background: rgba(124, 92, 255, 0.08);
  border: 1px solid rgba(124, 92, 255, 0.25);
  border-radius: 10px;
  font-size: 12px;
}
.picked-tag {
  flex-shrink: 0;
  font-weight: 600;
  color: #7c5cff;
}
.picked-text {
  flex: 1;
  min-width: 0;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.preview-tools {
  display: flex;
  align-items: center;
  gap: 6px;
}
.preview-body {
  position: relative;
}
.preview-body.selecting iframe {
  outline: 2px solid #7c5cff;
  outline-offset: -2px;
}
.selecting-hint {
  position: absolute;
  left: 50%;
  bottom: 16px;
  transform: translateX(-50%);
  padding: 6px 14px;
  border-radius: 20px;
  background: rgba(124, 92, 255, 0.92);
  color: #fff;
  font-size: 12px;
  box-shadow: 0 6px 20px rgba(124, 92, 255, 0.35);
  pointer-events: none;
  white-space: nowrap;
}

.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  font-size: 14px;
  font-weight: 500;
}
.preview-body {
  flex: 1;
  min-height: 0;
  background: #fafafa;
}
.preview-body iframe {
  width: 100%;
  height: 100%;
  border: 0;
}
.preview-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #999;
  font-size: 14px;
}

@media (max-width: 900px) {
  .body {
    flex-direction: column;
  }
}
</style>
