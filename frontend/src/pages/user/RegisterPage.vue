<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { register } from '../../api/user'
import CButton from '../../components/ui/CButton.vue'
import CField from '../../components/ui/CField.vue'
import CIcon from '../../components/ui/CIcon.vue'
import CInput from '../../components/ui/CInput.vue'
import { message } from '../../components/ui/toast'

const router = useRouter()

const form = reactive({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
})

const loading = ref(false)

const mismatch = computed(
  () => !!form.checkPassword && form.userPassword !== form.checkPassword,
)

/** 密码强度：0-3 */
const strength = computed(() => {
  const p = form.userPassword
  if (!p) return 0
  let score = 0
  if (p.length >= 8) score++
  if (/[A-Za-z]/.test(p) && /\d/.test(p)) score++
  if (/[^A-Za-z0-9]/.test(p) || p.length >= 12) score++
  return score
})

const strengthLabel = computed(() => ['', '较弱', '中等', '较强'][strength.value])

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

/* 打字机：展示创意场景 */
const cases = [
  '打造你的第一个落地页',
  '用 AI 生成完整 Vue 项目',
  '一句话，变成真实产品',
  '和 AI 一起开始创作',
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

const steps = [
  { icon: 'pencil', title: '描述需求', desc: '用一句话说清你想要的产品。' },
  { icon: 'sparkles', title: 'AI 生成', desc: '自动产出完整代码与实时预览。' },
  { icon: 'rocket', title: '发布上线', desc: '一键部署，或下载源码自行托管。' },
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
        <span class="auth__tip">已有账号？</span>
        <router-link to="/user/login" class="auth__link">
          直接登录
          <CIcon name="arrow-right" :size="13" />
        </router-link>
      </div>
    </header>

    <main class="auth__main">
      <section class="pane">
        <div class="card">
          <h1 class="card__title">创建账号</h1>
          <p class="card__sub">
            开始免费使用
            <span class="typer">{{ caseText }}<i class="typer__caret" /></span>
          </p>

          <form class="form" @submit.prevent="handleSubmit">
            <CField label="账号" hint="4 位以上，支持字母、数字与下划线" input-id="reg-account">
              <CInput
                id="reg-account"
                v-model="form.userAccount"
                size="lg"
                icon="user"
                placeholder="设置你的登录账号"
                autocomplete="username"
                clearable
              />
            </CField>

            <CField label="密码" input-id="reg-password">
              <CInput
                id="reg-password"
                v-model="form.userPassword"
                type="password"
                size="lg"
                icon="lock"
                placeholder="至少 8 位，建议字母加数字"
                autocomplete="new-password"
              />
              <div v-if="form.userPassword" class="strength">
                <span
                  v-for="i in 3"
                  :key="i"
                  class="strength__bar"
                  :class="{ 'is-on': i <= strength }"
                />
                <span class="strength__label">{{ strengthLabel }}</span>
              </div>
            </CField>

            <CField
              label="确认密码"
              :error="mismatch ? '两次输入的密码不一致' : undefined"
              input-id="reg-check"
            >
              <CInput
                id="reg-check"
                v-model="form.checkPassword"
                type="password"
                size="lg"
                icon="lock"
                placeholder="再次输入密码"
                autocomplete="new-password"
                :invalid="mismatch"
                @enter="handleSubmit"
              />
            </CField>

            <CButton
              variant="primary"
              size="lg"
              block
              icon-right="arrow-right"
              :loading="loading"
              :disabled="mismatch"
              type="submit"
            >
              创建账号
            </CButton>
          </form>

          <p class="card__foot">注册即表示同意服务条款与隐私政策</p>
        </div>
      </section>

      <aside class="pane pane--aside">
        <div class="aside surface-grid">
          <p class="eyebrow">How it works</p>
          <h2 class="aside__title">三步，从一句话到线上产品。</h2>
          <ol class="steps">
            <li v-for="(s, i) in steps" :key="s.title" class="step">
              <span class="step__num">{{ i + 1 }}</span>
              <span class="step__body">
                <span class="step__title">
                  <CIcon :name="s.icon" :size="14" />
                  {{ s.title }}
                </span>
                <span class="step__desc">{{ s.desc }}</span>
              </span>
            </li>
          </ol>
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
  padding: var(--sp-10) var(--sp-6);
}

.pane--aside {
  border-left: 1px solid var(--border-subtle);
  background: var(--bg-surface);
}

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
  margin-top: var(--sp-6);
}

.form :deep(.c-btn) { margin-top: var(--sp-2); }

.card__foot {
  margin-top: var(--sp-5);
  font-size: var(--fs-2xs);
  color: var(--text-faint);
  text-align: center;
}

/* 密码强度 */
.strength {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 8px;
}

.strength__bar {
  flex: 1;
  height: 3px;
  border-radius: var(--r-pill);
  background: var(--n-150);
  transition: background-color var(--t-base) var(--ease-out);
}

.strength__bar.is-on { background: var(--accent); }

.strength__label {
  font-size: var(--fs-2xs);
  color: var(--text-muted);
  min-width: 26px;
}

/* 右侧 */
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

.steps {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: var(--sp-6);
}

.step {
  display: flex;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--border-subtle);
}

.step:last-child { border-bottom: none; }

.step__num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  flex: none;
  border-radius: var(--r-pill);
  background: var(--accent);
  color: var(--accent-fg);
  font-size: var(--fs-2xs);
  font-weight: 600;
}

.step__body {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.step__title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  font-weight: 550;
  color: var(--text-strong);
}

.step__desc {
  font-size: var(--fs-xs);
  color: var(--text-muted);
  line-height: 1.6;
}

@media (max-width: 960px) {
  .auth__main { grid-template-columns: 1fr; }
  .pane--aside { display: none; }
  .pane { padding: var(--sp-8) var(--sp-4); }
  .auth__top { padding: 0 var(--sp-4); }
}
</style>
