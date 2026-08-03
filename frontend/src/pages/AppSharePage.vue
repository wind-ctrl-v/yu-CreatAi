<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { buildDownloadUrl, buildPreviewUrl, deployApp, getAppVOById } from '../api/app'
import CAvatar from '../components/ui/CAvatar.vue'
import CButton from '../components/ui/CButton.vue'
import CEmpty from '../components/ui/CEmpty.vue'
import CIcon from '../components/ui/CIcon.vue'
import CSpin from '../components/ui/CSpin.vue'
import CTag from '../components/ui/CTag.vue'
import { message } from '../components/ui/toast'
import { useLoginUserStore } from '../stores/loginUser'
import type { AppVO } from '../types'

const route = useRoute()
const router = useRouter()
const store = useLoginUserStore()

const appId = computed(() => String(route.params.id))
const app = ref<AppVO | null>(null)
const loading = ref(false)
const deploying = ref(false)
const deployUrl = ref('')

const TYPE_LABELS: Record<string, string> = {
  vue_project: 'Vue 项目',
  html: 'HTML 页面',
  multi_file: '多文件',
}

const isOwner = computed(
  () => !!store.loginUser && String(store.loginUser.id) === String(app.value?.userId),
)

const authorName = computed(
  () => app.value?.user?.userName || app.value?.user?.userAccount || '匿名用户',
)

const typeLabel = computed(() =>
  app.value ? TYPE_LABELS[app.value.codeGenType] || app.value.codeGenType : '',
)

async function loadApp() {
  loading.value = true
  const res = await getAppVOById(appId.value).catch(() => null)
  loading.value = false
  if (res?.code === 0) app.value = res.data
}

async function handleDeploy() {
  deploying.value = true
  const res = await deployApp(appId.value).catch(() => null)
  deploying.value = false
  if (res?.code === 0) {
    deployUrl.value = res.data
    message.success('部署成功')
  }
}

async function copyDeployUrl() {
  if (!deployUrl.value) return
  try {
    await navigator.clipboard.writeText(deployUrl.value)
    message.success('部署地址已复制')
  } catch {
    message.error('复制失败，请手动复制')
  }
}

onMounted(loadApp)
</script>

<template>
  <div class="page container">
    <CButton variant="text" size="sm" icon="arrow-left" class="back" @click="router.push('/')">
      返回首页
    </CButton>

    <CSpin :spinning="loading">
      <template v-if="app">
        <header class="hero">
          <div class="hero__main">
            <div class="hero__titles">
              <h1 class="hero__name">{{ app.appName || '未命名应用' }}</h1>
              <CTag tone="neutral" icon="code">{{ typeLabel }}</CTag>
            </div>
            <p v-if="app.initPrompt" class="hero__prompt">{{ app.initPrompt }}</p>
            <div class="hero__meta">
              <span class="hero__author">
                <CAvatar :src="app.user?.userAvatar" :name="authorName" :size="20" />
                {{ authorName }}
              </span>
              <span class="hero__dot">·</span>
              <span>{{ app.createTime || '创建时间未知' }}</span>
            </div>
          </div>

          <div class="hero__actions">
            <CButton
              variant="secondary"
              icon="message-square"
              @click="router.push({ name: 'appChat', params: { id: appId } })"
            >
              进入对话
            </CButton>
            <CButton
              v-if="isOwner"
              variant="secondary"
              icon="download"
              :href="buildDownloadUrl(appId)"
              target="_blank"
            >
              下载源码
            </CButton>
            <CButton
              v-if="isOwner"
              variant="primary"
              icon="rocket"
              :loading="deploying"
              @click="handleDeploy"
            >
              部署上线
            </CButton>
          </div>
        </header>

        <div v-if="deployUrl" class="deployed">
          <CIcon name="check-circle" :size="16" class="deployed__icon" />
          <span class="deployed__label">已上线</span>
          <a :href="deployUrl" target="_blank" rel="noopener noreferrer" class="deployed__link">
            {{ deployUrl }}
          </a>
          <CButton variant="ghost" size="xs" icon="copy" aria-label="复制地址" @click="copyDeployUrl" />
        </div>

        <section class="preview">
          <div class="preview__bar">
            <span class="preview__dots" aria-hidden="true">
              <i /><i /><i />
            </span>
            <span class="preview__label">实时预览</span>
            <CButton
              variant="ghost"
              size="xs"
              icon-right="external-link"
              :href="buildPreviewUrl(app.codeGenType, app.id)"
              target="_blank"
            >
              新窗口打开
            </CButton>
          </div>
          <iframe
            v-if="app.codeGenType && app.id"
            class="preview__frame"
            :src="buildPreviewUrl(app.codeGenType, app.id)"
            sandbox="allow-scripts"
            title="应用预览"
          />
          <CEmpty v-else icon="monitor" description="暂无预览" />
        </section>
      </template>

      <CEmpty
        v-else-if="!loading"
        icon="alert-circle"
        description="应用不存在"
        hint="它可能已被删除，或链接不正确。"
      >
        <CButton variant="primary" size="sm" @click="router.push('/explore')">去看看精选应用</CButton>
      </CEmpty>
    </CSpin>
  </div>
</template>

<style scoped>
.page {
  flex: 1;
  padding-top: var(--sp-5);
  padding-bottom: var(--sp-16);
}

.back { margin-left: -8px; }

.hero {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-6);
  flex-wrap: wrap;
  margin: var(--sp-5) 0 var(--sp-6);
}

.hero__main { min-width: 0; flex: 1; }

.hero__titles {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.hero__name {
  font-size: var(--fs-h1);
  font-weight: 600;
  letter-spacing: var(--tracking-heading);
  color: var(--text-strong);
}

.hero__prompt {
  max-width: 680px;
  margin-top: var(--sp-3);
  font-size: var(--fs-sm);
  line-height: 1.7;
  color: var(--text-secondary);
}

.hero__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: var(--sp-4);
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

.hero__author {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.hero__dot { color: var(--text-faint); }

.hero__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

/* ── 部署结果 ── */
.deployed {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 11px 14px;
  margin-bottom: var(--sp-5);
  border: 1px solid var(--success-border);
  border-radius: var(--r-lg);
  background: var(--success-soft);
  font-size: var(--fs-sm);
}

.deployed__icon { color: var(--success); flex: none; }

.deployed__label {
  font-weight: 550;
  color: var(--success);
}

.deployed__link {
  flex: 1;
  min-width: 0;
  color: var(--text-primary);
  text-decoration: underline;
  text-underline-offset: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── 预览 ── */
.preview {
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-xl);
  background: var(--bg-surface);
  overflow: hidden;
  box-shadow: var(--shadow-sm);
}

.preview__bar {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 42px;
  padding: 0 12px;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--n-25);
}

.preview__dots {
  display: inline-flex;
  gap: 5px;
}

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

.preview__bar :deep(.c-btn) { margin-left: auto; }

.preview__frame {
  display: block;
  width: 100%;
  height: 560px;
  border: none;
  background: #fff;
}

@media (max-width: 700px) {
  .hero__actions { width: 100%; }
  .preview__frame { height: 420px; }
}
</style>
