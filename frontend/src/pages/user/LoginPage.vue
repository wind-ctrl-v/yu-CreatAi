<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login } from '../../api/user'
import CButton from '../../components/ui/CButton.vue'
import CField from '../../components/ui/CField.vue'
import CIcon from '../../components/ui/CIcon.vue'
import CInput from '../../components/ui/CInput.vue'
import { message } from '../../components/ui/toast'
import { useLoginUserStore } from '../../stores/loginUser'

const router = useRouter()
const route = useRoute()
const store = useLoginUserStore()

const form = reactive({ userAccount: '', userPassword: '' })
const loading = ref(false)

async function handleSubmit() {
  if (!form.userAccount || !form.userPassword) {
    message.warning('请输入账号和密码')
    return
  }
  loading.value = true
  const res = await login(form).catch(() => null)
  loading.value = false
  if (res && res.code === 0) {
    store.setLoginUser(res.data)
    message.success('登录成功')
    const redirect = (route.query.redirect as string) || '/'
    router.replace(redirect)
  }
}

/* 打字机：依次展示不同用户案例 */
const cases = [
  '极简个人作品集，黑底衬线',
  '花艺工作室预约页，奶油色',
  'SaaS 落地页，深色科技感',
  '在线课程页，含讲师介绍',
]
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
      timer = setTimeout(() => typeCase((idx + 1) % cases.length), 2600)
    }
  }
  tick()
}

const highlights = [
  { icon: 'sparkles', text: '自然语言直接生成完整项目' },
  { icon: 'monitor', text: '边生成边预览，随时可改' },
  { icon: 'rocket', text: '一键部署，立即分享上线' },
]

onMounted(() => typeCase(0))
onUnmounted(() => {
  if (timer) clearTimeout(timer)
})
</script>

<template>
  <div class="auth">
    <header class="auth__top">
      <router-link to="/" class="brand">
        <span class="brand__mark">
          <svg viewBox="0 0 16 16" width="13" height="13" aria-hidden="true">
            <path d="M8 1.5 14.5 14H1.5L8 1.5Z" fill="currentColor" />
          </svg>
        </span>
        <span class="brand__text">CreatAi</span>
      </router-link>
      <div class="auth__top-right">
        <span class="auth__tip">还没有账号？</span>
        <router-link to="/user/register" class="auth__link">
          免费注册
          <CIcon name="arrow-right" :size="13" />
        </router-link>
      </div>
    </header>

    <main class="auth__main">
      <!-- 左：表单 -->
      <section class="pane">
        <div class="card">
          <h1 class="card__title">欢迎回来</h1>
          <p class="card__sub">
            登录后继续创作
            <span class="typer">{{ caseText }}<i class="typer__caret" /></span>
          </p>

          <form class="form" @submit.prevent="handleSubmit">
            <CField label="账号" input-id="login-account">
              <CInput
                id="login-account"
                v-model="form.userAccount"
                size="lg"
                icon="user"
                placeholder="请输入你的账号"
                autocomplete="username"
                clearable
              />
            </CField>

            <CField label="密码" input-id="login-password">
              <CInput
                id="login-password"
                v-model="form.userPassword"
                type="password"
                size="lg"
                icon="lock"
                placeholder="请输入你的密码"
                autocomplete="current-password"
                @enter="handleSubmit"
              />
            </CField>

            <CButton
              variant="primary"
              size="lg"
              block
              icon-right="arrow-right"
              :loading="loading"
              type="submit"
            >
              登录
            </CButton>
          </form>

          <p class="card__foot">
            登录即表示同意服务条款与隐私政策
          </p>
        </div>
      </section>

      <!-- 右：说明 -->
      <aside class="pane pane--aside">
        <div class="aside surface-grid">
          <p class="eyebrow">Why CreatAi</p>
          <h2 class="aside__title">把想法，直接变成可运行的产品。</h2>
          <ul class="aside__list">
            <li v-for="h in highlights" :key="h.text">
              <span class="aside__icon"><CIcon :name="h.icon" :size="15" /></span>
              {{ h.text }}
            </li>
          </ul>
          <div class="aside__demo">
            <span class="aside__demo-bar"><i /><i /><i /></span>
            <div class="aside__demo-body">
              <span class="skeleton" style="width: 62%; height: 12px" />
              <span class="skeleton" style="width: 88%; height: 12px" />
              <span class="skeleton" style="width: 44%; height: 12px" />
              <span class="skeleton" style="width: 100%; height: 68px; margin-top: 6px" />
            </div>
          </div>
        </div>
      </aside>
    </main>
  </div>
</template>

<style scoped>
.auth {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-page);
}

.auth__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--header-h);
  padding: 0 var(--sp-6);
  border-bottom: 1px solid var(--border-subtle);
  background: var(--bg-surface);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
}

.brand__mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 25px;
  height: 25px;
  border-radius: var(--r-sm);
  background: var(--accent);
  color: var(--accent-fg);
}

.brand__text {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: var(--tracking-heading);
  color: var(--text-strong);
}

.auth__top-right {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--fs-sm);
}

.auth__tip { color: var(--text-muted); }

.auth__link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 500;
  color: var(--text-strong);
}

.auth__link:hover {
  text-decoration: underline;
  text-underline-offset: 3px;
}

/* ── 主体 ── */
.auth__main {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  max-width: var(--container-wide);
  width: 100%;
  margin: 0 auto;
}

.pane {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-12) var(--sp-6);
}

.pane--aside {
  border-left: 1px solid var(--border-subtle);
  background: var(--bg-surface);
}

/* ── 表单卡片 ── */
.card {
  width: 100%;
  max-width: 400px;
}

.card__title {
  font-size: var(--fs-h1);
  font-weight: 600;
  letter-spacing: var(--tracking-heading);
  color: var(--text-strong);
}

.card__sub {
  margin-top: 10px;
  font-size: var(--fs-sm);
  color: var(--text-muted);
  min-height: 22px;
}

.typer {
  color: var(--text-primary);
  font-weight: 450;
}

.typer__caret {
  display: inline-block;
  width: 1.5px;
  height: 13px;
  margin-left: 2px;
  background: var(--accent);
  vertical-align: -2px;
  animation: pulse-soft 1s steps(2) infinite;
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  margin-top: var(--sp-8);
}

.form :deep(.c-btn) { margin-top: var(--sp-2); }

.card__foot {
  margin-top: var(--sp-6);
  font-size: var(--fs-2xs);
  color: var(--text-faint);
  text-align: center;
}

/* ── 右侧说明 ── */
.aside {
  width: 100%;
  max-width: 430px;
  padding: var(--sp-8);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-2xl);
  background-color: var(--n-25);
}

.aside__title {
  margin-top: 10px;
  font-size: var(--fs-h3);
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: var(--tracking-heading);
  color: var(--text-strong);
}

.aside__list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: var(--sp-6);
}

.aside__list li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

.aside__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: var(--r-sm);
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  color: var(--text-primary);
}

.aside__demo {
  margin-top: var(--sp-6);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-lg);
  background: var(--bg-surface);
  overflow: hidden;
  box-shadow: var(--shadow-xs);
}

.aside__demo-bar {
  display: flex;
  gap: 5px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border-subtle);
  background: var(--n-25);
}

.aside__demo-bar i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--n-200);
}

.aside__demo-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
}

@media (max-width: 960px) {
  .auth__main { grid-template-columns: 1fr; }
  .pane--aside { display: none; }
  .pane { padding: var(--sp-10) var(--sp-4); }
  .auth__top { padding: 0 var(--sp-4); }
}
</style>
