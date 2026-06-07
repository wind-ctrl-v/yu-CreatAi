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
import { connectChatSSE } from '../utils/sse'
import type { AppVO } from '../types'

interface ChatMessage {
  id: number
  type: 'user' | 'ai'
  content: string
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

const previewUrl = ref('')
const previewKey = ref(0) // 用于强制刷新 iframe
const deployUrl = ref('')

const chatListRef = ref<HTMLElement | null>(null)
let closeSSE: (() => void) | null = null

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

function sendMessage(text: string) {
  const content = text.trim()
  if (!content || generating.value) return

  messages.value.push({ id: ++msgSeq, type: 'user', content })
  messages.value.push({ id: ++msgSeq, type: 'ai', content: '' })
  // 记录 AI 消息索引，通过响应式代理修改以触发视图更新
  const aiIndex = messages.value.length - 1
  generating.value = true
  scrollToBottom()

  closeSSE = connectChatSSE(appId.value, content, {
    onChunk: (chunk) => {
      messages.value[aiIndex].content += chunk
      scrollToBottom()
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
      messages.value[aiIndex].content = `⚠️ ${errMsg}`
      message.error(errMsg)
    },
    onError: () => {
      generating.value = false
      if (!messages.value[aiIndex].content) {
        messages.value[aiIndex].content = '⚠️ 生成中断，请重试。'
      }
    },
  })
}

function handleSend() {
  const text = input.value
  input.value = ''
  sendMessage(text)
}

async function handleDeploy() {
  deploying.value = true
  const res = await deployApp(appId.value).catch(() => null)
  deploying.value = false
  if (res && res.code === 0) {
    deployUrl.value = res.data
    Modal.success({
      title: '部署成功 🎉',
      content: h('div', {}, [
        h('span', '访问地址：'),
        h('a', { href: deployUrl.value, target: '_blank' }, deployUrl.value),
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
  generating.value = false
  messages.value = []
  app.value = null
  previewUrl.value = ''
  deployUrl.value = ''

  await loadApp()
  await loadHistory()
  // 从首页带 init=1 进入：自动发起首轮生成
  if (route.query.init === '1' && app.value?.initPrompt) {
    sendMessage(app.value.initPrompt)
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
})
</script>

<template>
  <div class="chat-page">
    <div class="toolbar">
      <div class="left">
        <a-button type="text" @click="router.push('/')">← 返回</a-button>
        <span class="app-name">{{ appName }}</span>
        <a-tag v-if="app" color="blue">{{ app.codeGenType }}</a-tag>
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
          <div
            v-for="m in messages"
            :key="m.id"
            class="msg-row"
            :class="m.type"
          >
            <a-avatar
              class="avatar"
              :style="{
                background: m.type === 'ai' ? '#1677ff' : '#52c41a',
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
          <a-textarea
            v-model:value="input"
            :auto-size="{ minRows: 1, maxRows: 4 }"
            placeholder="继续描述需求，回车发送"
            :disabled="generating"
            @keydown.enter.exact.prevent="handleSend"
          />
          <a-button
            type="primary"
            :loading="generating"
            :disabled="!input.trim()"
            @click="handleSend"
          >
            发送
          </a-button>
        </div>
      </section>

      <!-- 右：预览区 -->
      <section class="preview-panel">
        <div class="preview-head">
          <span>实时预览</span>
          <a-button
            type="link"
            size="small"
            :disabled="!canPreview"
            @click="openPreviewInNewTab"
          >
            在新窗口打开
          </a-button>
        </div>
        <div class="preview-body">
          <iframe
            v-if="canPreview"
            :key="previewKey"
            :src="previewUrl"
            sandbox="allow-scripts allow-forms allow-popups"
          />
          <div v-else class="preview-empty">
            生成完成后，这里将实时展示你的网站
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
  background: #fff;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
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
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.05);
  overflow: hidden;
}

.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 18px;
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
  background: #e6f0ff;
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
  background: #1677ff;
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
  gap: 10px;
  padding: 12px 16px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
  align-items: flex-end;
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
