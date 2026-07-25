<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAppVOById, buildDownloadUrl, buildPreviewUrl, deployApp } from '../api/app'
import { message } from 'ant-design-vue'
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

const isOwner = computed(() => store.loginUser && String(store.loginUser.id) === String(app.value?.userId))

async function loadApp() {
  loading.value = true
  const res = await getAppVOById(appId.value).catch(() => null)
  loading.value = false
  if (res?.code === 0) { app.value = res.data }
}

async function handleDeploy() {
  deploying.value = true
  const res = await deployApp(appId.value).catch(() => null)
  deploying.value = false
  if (res?.code === 0) { deployUrl.value = res.data; message.success('部署成功') }
}

onMounted(() => { loadApp() })
</script>

<template>
  <div class="page">
    <div class="header">
      <a-button type="text" @click="router.push('/')">← 返回</a-button>
    </div>
    <a-spin :spinning="loading">
      <template v-if="app">
        <div class="info">
          <h1 class="name">{{ app.appName }}</h1>
          <a-tag color="blue">{{ app.codeGenType }}</a-tag>
          <p v-if="app.initPrompt" class="prompt">{{ app.initPrompt }}</p>
          <div class="meta">
            <span>创建者：{{ app.user?.userName || app.user?.userAccount || '匿名' }}</span>
            <span>创建时间：{{ app.createTime || '-' }}</span>
          </div>
        </div>

        <div class="preview-section">
          <h3>实时预览</h3>
          <iframe
            v-if="app.codeGenType && app.id"
            class="preview-frame"
            :src="buildPreviewUrl(app.codeGenType, app.id)"
            sandbox="allow-scripts"
          />
          <p v-else class="no-preview">暂无预览</p>
        </div>

        <div class="actions">
          <a-space>
            <a-button @click="router.push({ name: 'appChat', params: { id: appId } })">
              进入对话
            </a-button>
            <a-button v-if="isOwner" type="primary" :loading="deploying" @click="handleDeploy">
              部署
            </a-button>
            <a-button v-if="isOwner" :href="buildDownloadUrl(appId)" target="_blank">
              下载
            </a-button>
          </a-space>
          <div v-if="deployUrl" class="deploy-link">
            部署地址：<a :href="deployUrl" target="_blank">{{ deployUrl }}</a>
          </div>
        </div>
      </template>
    </a-spin>
  </div>
</template>

<style scoped>
.page { flex: 1; overflow-y: auto; padding: 24px 48px 64px; background: var(--bg-page); }
.header { margin-bottom: 24px; }
.info { margin-bottom: 32px; }
.name { font-size: 28px; font-weight: 600; margin: 0 0 8px; }
.prompt { font-size: 14px; color: var(--gray-500); margin: 12px 0; line-height: 1.6; max-width: 640px; }
.meta { display: flex; gap: 24px; font-size: 13px; color: var(--gray-400); margin-top: 12px; }
.preview-section { margin-bottom: 24px; }
.preview-section h3 { font-size: 15px; font-weight: 600; margin: 0 0 12px; }
.preview-frame {
  width: 100%; height: 480px; border: 1px solid var(--gray-100);
  border-radius: var(--r-image); background: #fff;
}
.no-preview { color: var(--gray-400); }
.actions { margin-top: 24px; }
.deploy-link { margin-top: 12px; font-size: 13px; color: var(--gray-600); }
.deploy-link a { color: var(--accent); word-break: break-all; }
</style>
