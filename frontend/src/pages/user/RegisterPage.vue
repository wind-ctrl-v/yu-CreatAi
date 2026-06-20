<script setup lang="ts">
import { reactive, ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { register } from '../../api/user'

const router = useRouter()

const form = reactive({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
})

const loading = ref(false)

async function handleSubmit() {
  if (!form.userAccount || !form.userPassword || !form.checkPassword) {
    message.warning('请填写完整信息')
    return
  }
  if (form.userPassword !== form.checkPassword) {
    message.warning('两次输入的密码不一致')
    return
  }
  loading.value = true
  const res = await register(form).catch(() => null)
  loading.value = false
  if (res && res.code === 0) {
    message.success('注册成功，请登录')
    router.replace('/user/login')
  }
}

// 打字机：展示创意场景
const cases = [
  '打造你的第一个落地页',
  '用 AI 生成完整 Vue 项目',
  '一句话，变成真实产品',
  '和 AI 一起开始创作',
]
const caseIdx = ref(0)
const caseText = ref('')
let timer: ReturnType<typeof setTimeout> | null = null

function typeCase(idx: number) {
  caseText.value = ''
  const target = cases[idx]
  let i = 0
  function tick() {
    if (i < target.length) {
      caseText.value += target[i++]
      timer = setTimeout(tick, 52)
    } else {
      timer = setTimeout(() => {
        caseIdx.value = (idx + 1) % cases.length
        typeCase(caseIdx.value)
      }, 2600)
    }
  }
  tick()
}

onMounted(() => typeCase(0))
onUnmounted(() => { if (timer) clearTimeout(timer) })
</script>

<template>
  <div class="page">
    <!-- 极淡点阵背景 -->
    <div class="dot-bg" />

    <!-- 顶部导航条 -->
    <header class="top-nav">
      <div class="nav-logo">
        <div class="logo-icon">
          <svg width="13" height="13" viewBox="0 0 16 16" fill="none">
            <path d="M8 1L15 14H1L8 1Z" fill="currentColor"/>
          </svg>
        </div>
        <span class="logo-name">CreatAi</span>
      </div>
      <div class="nav-right">
        <span class="nav-tip">已有账号？</span>
        <router-link to="/user/login" class="nav-link">去登录 →</router-link>
      </div>
    </header>

    <!-- 主体区域 -->
    <main class="main">
      <div class="card">

        <!-- 标题区 -->
        <div class="card-head">
          <h1 class="title">创建账号</h1>
          <p class="subtitle">
            注册即可——
            <span class="typewriter">{{ caseText }}<span class="cursor" /></span>
          </p>
        </div>

        <!-- 分隔线 -->
        <div class="divider" />

        <!-- 表单 -->
        <a-form layout="vertical" class="form" @submit.prevent="handleSubmit">
          <a-form-item>
            <template #label>
              <span class="field-label">账号</span>
            </template>
            <a-input
              v-model:value="form.userAccount"
              size="large"
              placeholder="请输入账号（至少 4 位）"
              allow-clear
              class="field-input"
            />
          </a-form-item>

          <a-form-item>
            <template #label>
              <span class="field-label">密码</span>
            </template>
            <a-input-password
              v-model:value="form.userPassword"
              size="large"
              placeholder="请输入密码（至少 8 位）"
              class="field-input"
            />
          </a-form-item>

          <a-form-item>
            <template #label>
              <span class="field-label">确认密码</span>
            </template>
            <a-input-password
              v-model:value="form.checkPassword"
              size="large"
              placeholder="请再次输入密码"
              class="field-input"
              @keyup.enter="handleSubmit"
            />
          </a-form-item>

          <button
            class="submit-btn"
            :class="{ loading }"
            :disabled="loading"
            type="button"
            @click="handleSubmit"
          >
            <span v-if="!loading" class="btn-text">
              免费注册
              <span class="btn-arrow">→</span>
            </span>
            <span v-else class="btn-spinner" />
          </button>
        </a-form>

        <!-- 底部 -->
        <div class="card-foot">
          <span class="foot-text">已有账号？</span>
          <router-link to="/user/login" class="foot-link">立即登录</router-link>
        </div>

      </div>

      <!-- 底部品牌小字 -->
      <p class="page-foot">
        CreatAi · 用一句话，创造属于你的产品
      </p>
    </main>
  </div>
</template>

<style scoped>
/* ── 全局重置 ── */
.page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--geist-white);
  position: relative;
  overflow: hidden;
}

/* 点阵背景（极淡，Vercel 质感） */
.dot-bg {
  position: fixed;
  inset: 0;
  background-image: radial-gradient(circle, rgba(0,0,0,0.07) 1px, transparent 1px);
  background-size: 24px 24px;
  pointer-events: none;
  z-index: 0;
}

/* ── 顶部导航 ── */
.top-nav {
  position: relative;
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 40px;
  border-bottom: 1px solid var(--gray-100);
  background: rgba(255,255,255,0.9);
  backdrop-filter: blur(8px);
}

.nav-logo {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
  text-decoration: none;
}
.logo-icon {
  width: 26px;
  height: 26px;
  border-radius: var(--r-base);
  background: var(--geist-black);
  color: var(--geist-white);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.logo-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--geist-black);
  letter-spacing: var(--tracking-label);
}

.nav-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.nav-tip {
  font-size: 13px;
  color: var(--gray-500);
}
.nav-link {
  font-size: 13px;
  font-weight: 500;
  color: var(--geist-black);
  text-decoration: none;
  padding: 5px 12px;
  border-radius: var(--r-base);
  box-shadow: var(--shadow-ring);
  transition: all var(--duration-fast) var(--spring);
}
.nav-link:hover {
  background: var(--gray-50);
  box-shadow: var(--shadow-border);
  text-decoration: none;
  transform: translateY(-1px);
}

/* ── 主体 ── */
.main {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 24px 64px;
}

/* ── 注册卡片 ── */
.card {
  width: 100%;
  max-width: 400px;
  background: var(--geist-white);
  border-radius: var(--r-image);
  box-shadow: var(--shadow-card);
  padding: 40px 36px 32px;
  animation: card-in 0.5s var(--smooth) both;
}
@keyframes card-in {
  from { opacity: 0; transform: translateY(16px) scale(0.98); }
  to   { opacity: 1; transform: translateY(0)    scale(1); }
}

/* 标题区 */
.card-head {
  margin-bottom: 24px;
}
.title {
  font-size: 26px;
  font-weight: 600;
  color: var(--geist-black);
  margin: 0 0 10px;
  letter-spacing: var(--tracking-heading);
  line-height: 1.2;
}
.subtitle {
  font-size: 13.5px;
  color: var(--gray-500);
  margin: 0;
  line-height: 1.5;
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

/* 打字机区域 */
.typewriter {
  color: var(--geist-black);
  font-weight: 500;
  letter-spacing: var(--tracking-label);
}
.cursor {
  display: inline-block;
  width: 1.5px;
  height: 13px;
  background: var(--geist-black);
  margin-left: 1px;
  vertical-align: middle;
  animation: blink 1s step-end infinite;
}
@keyframes blink { 50% { opacity: 0; } }

.divider {
  height: 1px;
  background: var(--gray-100);
  margin-bottom: 24px;
}

/* 表单 */
.form {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.field-label {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--geist-black);
  letter-spacing: var(--tracking-label);
}

/* 覆盖 Ant Design 输入框 */
.field-input :deep(.ant-input),
.field-input :deep(.ant-input-affix-wrapper) {
  border-radius: var(--r-card) !important;
  border: none !important;
  box-shadow: var(--shadow-border) !important;
  font-size: 14px !important;
  font-family: inherit !important;
  color: var(--geist-black) !important;
  background: var(--geist-white) !important;
  height: 40px !important;
  transition: box-shadow var(--duration-fast) ease !important;
}
.field-input :deep(.ant-input-affix-wrapper) {
  height: 40px !important;
  display: flex !important;
  align-items: center !important;
}
.field-input :deep(.ant-input-affix-wrapper:hover),
.field-input :deep(.ant-input:hover) {
  box-shadow: rgba(0,0,0,0.12) 0px 0px 0px 1px !important;
}
.field-input :deep(.ant-input-affix-wrapper-focused),
.field-input :deep(.ant-input:focus) {
  box-shadow:
    rgba(0,0,0,0.1) 0px 0px 0px 1px,
    rgba(10,114,239,0.22) 0px 0px 0px 3px !important;
}
.field-input :deep(.ant-input::placeholder) {
  color: var(--gray-400) !important;
}

/* 提交按钮 */
.submit-btn {
  width: 100%;
  height: 40px;
  border-radius: var(--r-base);
  border: none;
  background: var(--geist-black);
  color: var(--geist-white);
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  letter-spacing: var(--tracking-label);
  cursor: pointer;
  margin-top: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  transition: all var(--duration-fast) var(--spring);
}
.submit-btn:hover:not(:disabled) {
  background: #1a1a1a;
  transform: translateY(-1px) scale(1.01);
  box-shadow: rgba(0,0,0,0.2) 0px 6px 16px;
}
.submit-btn:active:not(:disabled) {
  transform: scale(0.98);
  transition-duration: 0.08s;
}
.submit-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-text {
  display: flex;
  align-items: center;
  gap: 6px;
}
.btn-arrow {
  font-size: 15px;
  transition: transform var(--duration-fast) var(--spring);
}
.submit-btn:hover .btn-arrow { transform: translateX(3px); }

.btn-spinner {
  width: 15px;
  height: 15px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.65s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* 底部登录链接 */
.card-foot {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid var(--gray-100);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.foot-text {
  font-size: 13px;
  color: var(--gray-500);
}
.foot-link {
  font-size: 13px;
  font-weight: 500;
  color: var(--geist-black);
  text-decoration: none;
  transition: color var(--duration-fast) ease;
}
.foot-link:hover {
  color: var(--accent);
  text-decoration: underline;
  text-underline-offset: 3px;
}

/* 页面底部小字 */
.page-foot {
  margin-top: 32px;
  font-size: 12px;
  color: var(--gray-400);
  text-align: center;
  letter-spacing: 0.01em;
}

/* 响应式 */
@media (max-width: 480px) {
  .top-nav { padding: 16px 20px; }
  .card { padding: 28px 20px 24px; }
  .title { font-size: 22px; }
}
</style>
