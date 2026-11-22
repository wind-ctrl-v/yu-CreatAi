<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  buildDownloadUrl,
  buildPreviewUrl,
  deployApp,
  getAppVOById,
} from '../api/app'
import { listAppChatHistory } from '../api/chatHistory'
import { getPlanCapabilities } from '../api/user'
import CAvatar from '../components/ui/CAvatar.vue'
import CButton from '../components/ui/CButton.vue'
import CEmpty from '../components/ui/CEmpty.vue'
import CIcon from '../components/ui/CIcon.vue'
import CModal from '../components/ui/CModal.vue'
import CSelect from '../components/ui/CSelect.vue'
import CTag from '../components/ui/CTag.vue'
import CTextarea from '../components/ui/CTextarea.vue'
import { message } from '../components/ui/toast'
import { connectChatSSE, connectIncrementalEditSSE } from '../utils/sse'
import {
  attachElementPicker,
  buildEditContext,
  type PickedElement,
} from '../utils/visualEditor'
import type { AppVO } from '../types'
import { type PlanCapabilities } from '../types'
import { dislike, recordNegativeEvent } from '../api/preference'
import DislikeFeedbackModal from '../components/app-chat/DislikeFeedbackModal.vue'

interface ChatMessage {
  id: number
  type: 'user' | 'ai'
  content: string
}

/** 工具调用中文名 */
const TOOL_LABELS: Record<string, string> = {
  readDir: '读取目录',
  readFile: '读取文件',
  modifyFile: '修改文件',
  writeFile: '写入文件',
  deleteFile: '删除文件',
}
function toolLabel(name: string): string {
  return TOOL_LABELS[name] || name
}
/** 消息内文本行前缀 */
const P_TEXT = '· '
const P_RUNNING = '▸ '
const P_DONE = '✓ '
const P_WARN = '! '

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
// SSE 连接状态
const reconnecting = ref(false)
const reconnectHint = ref('')
// 生成模式意图（low_cost/standard/high_quality），后端按套餐封顶
const genMode = ref<string>('standard')
// 本轮生成的结构化运行信息（生效模式/链路/降级提示），由 SSE 结构化事件填充
const runInfo = ref<GenerationRunInfo | null>(null)
// 套餐能力（拉取一次，整个会话复用）
const planCaps = ref<PlanCapabilities | null>(null)

const genModeOptions = computed(() => {
  const available = planCaps.value?.availableModes || ['low_cost', 'standard', 'high_quality']
  const allModes = [
    { value: 'low_cost', label: '快速生成', icon: 'activity' },
    { value: 'standard', label: '标准生成', icon: 'layers' },
    { value: 'high_quality', label: '高质量生成', icon: 'sparkles' },
  ]
  return allModes.map((m) => ({
    value: m.value,
    label: m.label,
    icon: m.icon,
    disabled: !available.includes(m.value),
    desc: available.includes(m.value) ? undefined : '需升级套餐',
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

// —— 负反馈埋点 ——
const hasEdited = ref(false) // 是否做过增量编辑（控制"还原"按钮显示）
const dislikeModalOpen = ref(false)
const dislikeReason = ref<string>('other')

// —— 负反馈埋点处理 ——

/** 点踩：打开原因选择弹窗 */
function handleDislike() {
  dislikeReason.value = 'other'
  dislikeModalOpen.value = true
}

/** 确认点踩 */
async function confirmDislike() {
  await dislike({ appId: appId.value, reason: dislikeReason.value }).catch(() => null)
  dislikeModalOpen.value = false
  message.success('已记录你的反馈')
}

/** 换一批：拒绝当前 AI 推荐 */
function handleReject() {
  recordNegativeEvent({
    appId: appId.value,
    eventType: 'REJECT_SUGGESTION',
  }).catch(() => null)
  message.info('已记录，将尝试其他方向')
}

/** 还原到 AI 原始版本 */
function handleRevert() {
  recordNegativeEvent({
    appId: appId.value,
    eventType: 'REVERT_TO_GENERATED',
  }).catch(() => null)
  message.info('已记录反馈')
  // TODO: 调用后端还原接口
}

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
  reconnecting.value = false
  reconnectHint.value = ''
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
    onAutoDeployStarted: () => {
      generating.value = false
      deploying.value = true
    },
    onAutoDeployFailed: (text) => {
      message.warning(text)
    },
    onDone: () => {
      generating.value = false
      deploying.value = false
      // 本地部署（含 Vue 构建与封面截图）已在 done 之前由后端完成
      refreshPreview()
    },
    onBusinessError: ({ code, message: errMsg }) => {
      generating.value = false
      // 会话失效：提示并引导重新登录
      if (code === 40100) {
        messages.value[aiIndex].content = `${P_WARN}登录已失效，请重新登录后重试。`
        message.error('登录已失效，请重新登录')
        return
      }
      // 限流：提醒等待后重试
      if (code === 42900) {
        messages.value[aiIndex].content = `${P_WARN}请求过于频繁，请稍等片刻后重试（每 60 秒最多 5 次）。`
        message.warning('AI 对话请求过于频繁，请稍后再试')
        return
      }
      messages.value[aiIndex].content = `${P_WARN}${errMsg}`
      message.error(errMsg)
    },
    onReconnecting: (retriesLeft, delayMs) => {
      reconnecting.value = true
      reconnectHint.value = `连接断开，${delayMs / 1000}s 后重试（剩余 ${retriesLeft + 1} 次）…`
    },
    onReconnected: () => {
      reconnecting.value = false
      reconnectHint.value = ''
      message.success('已重新连接')
    },
    onError: () => {
      reconnecting.value = false
      reconnectHint.value = ''
      generating.value = false
      if (!messages.value[aiIndex].content) {
        messages.value[aiIndex].content = `${P_WARN}生成中断，请重试。`
      }
    },
    },
    genMode.value,
  )
}

/** 执行增量编辑（SSE 流式），抽取公共逻辑 */
function doIncrementalEdit(instruction: string, userDisplay?: string) {
  const display = userDisplay || instruction
  messages.value.push({ id: ++msgSeq, type: 'user', content: display })
  messages.value.push({ id: ++msgSeq, type: 'ai', content: '' })
  const aiIndex = messages.value.length - 1
  const toolLines: string[] = []
  let hasError = false

  // UNDO 埋点：识别撤销类指令
  if (/撤销|undo|回退|撤回/i.test(instruction)) {
    recordNegativeEvent({
      appId: appId.value,
      eventType: 'UNDO',
      metadata: { instruction: instruction.slice(0, 100) },
    }).catch(() => null)
  }
  hasEdited.value = true

  generating.value = true
  closeSSE = connectIncrementalEditSSE(
    appId.value,
    instruction,
    {
      onText: (text) => {
        if (toolLines[toolLines.length - 1]?.startsWith(P_TEXT)) {
          toolLines[toolLines.length - 1] =
            P_TEXT + (toolLines[toolLines.length - 1].slice(P_TEXT.length) + text)
        } else {
          toolLines.push(P_TEXT + text)
        }
        messages.value[aiIndex].content = toolLines.join('\n')
        scrollToBottom()
      },
      onToolCall: (_id, toolName) => {
        toolLines.push(`${P_RUNNING}${toolLabel(toolName)}…`)
        messages.value[aiIndex].content = toolLines.join('\n')
        scrollToBottom()
      },
      onToolExecuted: (_id, toolName, _args, result) => {
        const shortResult = result.length > 120 ? result.slice(0, 120) + '...' : result
        for (let i = toolLines.length - 1; i >= 0; i--) {
          if (toolLines[i].includes(toolLabel(toolName))) {
            toolLines[i] = `${P_DONE}${toolLabel(toolName)} → ${shortResult}`
            break
          }
        }
        messages.value[aiIndex].content = toolLines.join('\n')
        scrollToBottom()
      },
      onError: (msg) => {
        hasError = true
        toolLines.push(`${P_WARN}${msg}`)
        messages.value[aiIndex].content = toolLines.join('\n')
      },
      onAutoDeployStarted: () => {
        generating.value = false
        deploying.value = true
      },
      onAutoDeployFailed: (text) => {
        message.warning(text)
      },
      onDone: () => {
        generating.value = false
        deploying.value = false
        if (!hasError) {
          toolLines.push(`${P_DONE}修改完成`)
        }
        messages.value[aiIndex].content = toolLines.join('\n')
        refreshPreview()
      },
    },
  )
  scrollToBottom()
}

async function handleSend() {
  const userText = input.value.trim()
  input.value = ''
  if (picked.value) {
    // 选中元素场景：拼装元素上下文，走增量编辑
    // 偏好提取由后端 PromptPreferenceExtractor 统一处理（2026-07-04 重构）
    const target = picked.value
    const instruction = userText || '请优化这个元素的样式与交互体验'
    const display = `${PICK_PREFIX}<${target.tagName}>${
      target.text ? ` "${target.text.slice(0, 20)}"` : ''
    }\n${instruction}`
    clearPicked()
    exitSelecting()
    doIncrementalEdit(buildEditContext(target) + instruction, display)
  } else if (app.value?.codeGenType === 'vue_project' && userText) {
    // 未选中元素：默认走增量编辑（偏好提取由后端 LLM 统一处理）
    doIncrementalEdit(userText)
  } else {
    sendMessage(userText)
  }
}

/** 部署成功弹窗 */
const deployedOpen = ref(false)

async function handleDeploy() {
  deploying.value = true
  const res = await deployApp(appId.value).catch(() => null)
  deploying.value = false
  if (res && res.code === 0) {
    deployUrl.value = res.data
    deployedOpen.value = true
  }
}

async function copyDeployUrl() {
  try {
    await navigator.clipboard.writeText(deployUrl.value)
    message.success('已复制到剪贴板')
  } catch {
    message.error('复制失败，请手动选中复制')
  }
}

/** 选中元素的展示前缀 */
const PICK_PREFIX = '已选中 '

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
  reconnecting.value = false
  reconnectHint.value = ''
  hasEdited.value = false
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
  // 如果还在生成中，记录 ABANDON 事件（用户中途离开）
  if (generating.value) {
    recordNegativeEvent({
      appId: appId.value,
      eventType: 'ABANDON',
    }).catch(() => null)
  }
  closeSSE?.()
  pickerDetach?.()
})
</script>

<template>
  <div class="chat">
    <!-- ── 顶部工具条 ── -->
    <header class="bar">
      <div class="bar__left">
        <CButton
          variant="ghost"
          size="sm"
          icon="arrow-left"
          aria-label="返回首页"
          @click="router.push('/')"
        />
        <h1 class="bar__name" :title="appName">{{ appName }}</h1>
        <CTag v-if="app" tone="neutral" icon="code">{{ app.codeGenType }}</CTag>
        <CTag v-if="runInfo" tone="info" dot>
          {{ modeLabel(runInfo.mode) }} · {{ runInfo.pipeline }}
        </CTag>
      </div>

      <div class="bar__right">
        <CButton v-if="hasEdited" variant="ghost" size="sm" icon="undo" @click="handleRevert">
          还原
        </CButton>
        <CButton variant="secondary" size="sm" icon="download" @click="handleDownload">
          下载代码
        </CButton>
        <CButton
          variant="primary"
          size="sm"
          icon="rocket"
          :loading="deploying"
          @click="handleDeploy"
        >
          部署
        </CButton>
      </div>
    </header>

    <div class="body">
      <!-- ── 左：对话 ── -->
      <section class="panel conv">
        <div ref="chatListRef" class="conv__list">
          <div v-if="runInfo?.fallbackText" class="notice">
            <CIcon name="alert-triangle" :size="14" />
            <span>{{ runInfo.fallbackText }}</span>
          </div>

          <div v-for="m in messages" :key="m.id" class="msg" :class="`is-${m.type}`">
            <div class="msg__head">
              <CAvatar
                v-if="m.type === 'ai'"
                icon="sparkles"
                :size="24"
                square
                class="msg__avatar"
              />
              <span class="msg__who">{{ m.type === 'ai' ? 'CreatAi' : '我' }}</span>
            </div>

            <div class="msg__bubble">
              <pre v-if="m.content">{{ m.content }}</pre>
              <span v-else-if="m.type === 'ai' && generating" class="typing">
                正在生成<i /><i /><i />
              </span>
            </div>

            <div v-if="m.type === 'ai' && m.content && !generating" class="msg__acts">
              <CButton variant="ghost" size="xs" icon="thumbs-down" @click="handleDislike">
                不满意
              </CButton>
              <CButton variant="ghost" size="xs" icon="refresh" @click="handleReject">
                换一批
              </CButton>
            </div>
          </div>

          <CEmpty
            v-if="!messages.length"
            icon="message-square"
            description="开始你的第一句需求描述"
            hint="例如「把首页主色改成米白，并加一个联系我们区块」。"
          />
        </div>

        <!-- 输入区 -->
        <div class="composer">
          <div v-if="picked" class="picked">
            <CIcon name="wand" :size="13" class="picked__icon" />
            <span class="picked__tag">{{ picked.tagName }}</span>
            <span class="picked__text truncate">{{ picked.text || picked.selector }}</span>
            <CButton variant="ghost" size="xs" icon="x" aria-label="取消选中" @click="clearPicked" />
          </div>

          <div v-if="reconnecting" class="notice is-center">
            <CIcon name="refresh" :size="13" spin />
            <span>{{ reconnectHint }}</span>
          </div>

          <CTextarea
            v-model="input"
            bare
            autosize
            :rows="1"
            :max-rows="6"
            :disabled="generating"
            :placeholder="
              picked ? '描述如何修改选中的元素…' : '继续描述你的需求，例如调整配色或新增模块…'
            "
            @submit="handleSend"
          />

          <div class="composer__bar">
            <div class="composer__left">
              <CSelect
                v-model="genMode"
                :options="genModeOptions"
                :disabled="generating"
                bare
                size="sm"
                width="132px"
                menu-width="240px"
                placement="top"
              />
              <CButton
                variant="ghost"
                size="sm"
                :icon="selecting ? 'check' : 'wand'"
                :disabled="!canPreview"
                @click="toggleSelecting"
              >
                {{ selecting ? '选择中' : '选元素改' }}
              </CButton>
            </div>

            <CButton
              variant="primary"
              size="sm"
              icon-right="arrow-up"
              pill
              :loading="generating"
              :disabled="!input.trim() && !picked"
              @click="handleSend"
            >
              发送
            </CButton>
          </div>
        </div>
      </section>

      <!-- ── 右：预览 ── -->
      <section class="panel preview">
        <div class="preview__bar">
          <span class="preview__dots" aria-hidden="true"><i /><i /><i /></span>
          <span class="preview__label">实时预览</span>
          <div class="preview__tools">
            <CButton
              variant="ghost"
              size="xs"
              icon="refresh"
              :disabled="!canPreview"
              aria-label="刷新预览"
              @click="refreshPreview"
            />
            <CButton
              variant="ghost"
              size="xs"
              icon-right="external-link"
              :disabled="!canPreview"
              @click="openPreviewInNewTab"
            >
              新窗口
            </CButton>
          </div>
        </div>

        <div class="preview__stage" :class="{ 'is-selecting': selecting }">
          <iframe
            v-if="canPreview"
            ref="previewIframeRef"
            :key="previewKey"
            :src="previewUrl"
            sandbox="allow-scripts allow-forms allow-same-origin"
            title="应用预览"
            @load="onIframeLoad"
          />
          <div v-else class="preview__empty surface-grid">
            <CEmpty
              icon="monitor"
              description="等待生成"
              hint="生成完成后，这里会实时展示你的网站。"
            />
          </div>

          <div v-if="selecting" class="preview__hint">
            <CIcon name="wand" :size="13" />
            将鼠标移到页面上，点击你想修改的元素
          </div>
        </div>
      </section>
    </div>

    <DislikeFeedbackModal
      v-model:open="dislikeModalOpen"
      v-model:reason="dislikeReason"
      @confirm="confirmDislike"
    />

    <!-- 部署成功 -->
    <CModal
      v-model:open="deployedOpen"
      title="部署成功"
      description="应用已上线，可以把链接分享给任何人。"
      icon="rocket"
      width="480px"
      hide-footer
    >
      <div class="deployed">
        <a
          :href="deployUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="deployed__url mono"
        >
          {{ deployUrl }}
        </a>
        <div class="deployed__acts">
          <CButton variant="secondary" size="sm" icon="copy" @click="copyDeployUrl">
            复制链接
          </CButton>
          <CButton
            variant="primary"
            size="sm"
            icon-right="external-link"
            :href="deployUrl"
            target="_blank"
          >
            访问站点
          </CButton>
        </div>
      </div>
    </CModal>
  </div>
</template>

<style scoped>
.chat {
  display: flex;
  flex-direction: column;
  height: calc(100vh - var(--header-h));
  max-height: calc(100vh - var(--header-h));
  min-height: 0;
  overflow: hidden;
}

/* ── 工具条 ── */
.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 20px;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--bg-surface);
  flex: none;
}

.bar__left {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.bar__name {
  font-size: var(--fs-base);
  font-weight: 600;
  letter-spacing: var(--tracking-tight);
  color: var(--text-strong);
  max-width: 260px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar__right {
  display: flex;
  align-items: center;
  gap: 7px;
  flex: none;
}

/* ── 主体 ── */
.body {
  flex: 1;
  display: flex;
  gap: var(--sp-4);
  padding: var(--sp-4) var(--sp-5);
  min-height: 0;
}

.panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-xs);
  overflow: hidden;
}

.conv {
  width: 420px;
  flex: none;
}

.preview { flex: 1; }

/* ── 消息列表 ── */
.conv__list {
  flex: 1;
  overflow-y: auto;
  padding: var(--sp-5);
}

.notice {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 9px 12px;
  margin-bottom: 14px;
  border: 1px solid var(--warning-border);
  border-radius: var(--r-md);
  background: var(--warning-soft);
  font-size: var(--fs-xs);
  line-height: 1.55;
  color: var(--warning);
}

.notice.is-center {
  justify-content: center;
  align-items: center;
  margin: 0 0 8px;
}

.msg {
  margin-bottom: var(--sp-5);
}

.msg__head {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 6px;
}

.msg__avatar { color: var(--text-primary); }

.msg__who {
  font-size: var(--fs-2xs);
  font-weight: 600;
  letter-spacing: var(--tracking-wide);
  text-transform: uppercase;
  color: var(--text-faint);
}

.msg.is-user .msg__head { justify-content: flex-end; }

.msg__bubble {
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--text-primary);
}

.msg.is-user .msg__bubble {
  padding: 10px 13px;
  margin-left: auto;
  max-width: 92%;
  width: fit-content;
  background: var(--n-100);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-lg);
}

.msg__bubble pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: inherit;
}

.msg__acts {
  display: flex;
  gap: 4px;
  margin-top: 7px;
}

.typing {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: var(--fs-sm);
  color: var(--text-muted);
}

.typing i {
  width: 4px;
  height: 4px;
  margin-left: 2px;
  border-radius: 50%;
  background: var(--text-faint);
  animation: pulse-soft 1.2s infinite both;
}

.typing i:nth-child(2) { animation-delay: 0.18s; }
.typing i:nth-child(3) { animation-delay: 0.36s; }

/* ── 输入区 ── */
.composer {
  flex: none;
  padding: 12px 14px;
  border-top: 1px solid var(--border-subtle);
  background: var(--n-25);
}

.composer__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 10px;
}

.composer__left {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.picked {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 5px 6px 5px 10px;
  margin-bottom: 9px;
  border: 1px solid var(--spark-border);
  border-radius: var(--r-md);
  background: var(--spark-soft);
  font-size: var(--fs-2xs);
}

.picked__icon { color: var(--spark); flex: none; }

.picked__tag {
  font-family: var(--font-mono);
  font-weight: 600;
  color: var(--spark);
  flex: none;
}

.picked__text {
  flex: 1;
  min-width: 0;
  color: var(--text-secondary);
}

/* ── 预览 ── */
.preview__bar {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 42px;
  padding: 0 12px;
  flex: none;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--n-25);
}

.preview__dots { display: inline-flex; gap: 5px; }

.preview__dots i {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--n-200);
}

.preview__label {
  font-size: var(--fs-xs);
  font-weight: 500;
  color: var(--text-muted);
}

.preview__tools {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: auto;
}

.preview__stage {
  position: relative;
  flex: 1;
  min-height: 0;
  background: var(--n-50);
}

.preview__stage iframe {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  background: #fff;
}

.preview__stage.is-selecting iframe {
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}

.preview__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  background-color: var(--n-50);
}

.preview__hint {
  position: absolute;
  left: 50%;
  bottom: 18px;
  transform: translateX(-50%);
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 8px 14px;
  border-radius: var(--r-pill);
  background: var(--accent);
  color: var(--accent-fg);
  font-size: var(--fs-xs);
  box-shadow: var(--shadow-lg);
  pointer-events: none;
  white-space: nowrap;
}

/* ── 部署弹窗 ── */
.deployed {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.deployed__url {
  padding: 11px 13px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-md);
  background: var(--n-50);
  font-size: var(--fs-xs);
  color: var(--text-primary);
  word-break: break-all;
}

.deployed__url:hover { border-color: var(--border-strong); }

.deployed__acts {
  display: flex;
  gap: 8px;
}

@media (max-width: 1000px) {
  .chat { height: auto; }
  .body { flex-direction: column; padding: var(--sp-3) var(--sp-4); }
  .conv { width: auto; height: 62vh; }
  .preview { height: 70vh; }
  .bar { flex-wrap: wrap; }
  .bar__name { max-width: 160px; }
}
</style>
