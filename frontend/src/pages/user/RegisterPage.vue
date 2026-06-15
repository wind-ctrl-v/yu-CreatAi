<script setup lang="ts">
import { reactive, ref } from 'vue'
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

const benefits = [
  '✦ 免费使用 AI 生成网站应用',
  '✦ 无限次对话式迭代修改',
  '✦ 云端保存所有创作作品',
  '✦ 一键分享或部署你的应用',
]
</script>

<template>
  <div class="auth-wrap">
    <!-- 左侧：创意展示区 -->
    <div class="auth-left">
      <div class="glow glow-1" />
      <div class="glow glow-2" />
      <div class="glow glow-3" />

      <div class="left-content">
        <div class="left-logo">
          <span class="logo-mark">C</span>
          <span class="logo-name">CreatAi</span>
        </div>

        <h2 class="left-title">
          开启你的<br />
          <span class="left-grad">AI 创作之旅</span>
        </h2>
        <p class="left-desc">加入数千名创作者，用 AI 把你的想法变成真实产品</p>

        <div class="benefit-list">
          <div v-for="b in benefits" :key="b" class="benefit-item">{{ b }}</div>
        </div>

        <!-- 装饰性代码卡片 -->
        <div class="code-card">
          <div class="code-bar">
            <span class="dot red" /><span class="dot yellow" /><span class="dot green" />
            <span class="code-file">app.tsx</span>
          </div>
          <div class="code-body">
            <div class="code-line"><span class="kw">const</span> app = <span class="fn">CreatAi</span><span class="pu">(</span><span class="str">"咖啡馆官网"</span><span class="pu">)</span></div>
            <div class="code-line"><span class="kw">await</span> app.<span class="fn">generate</span><span class="pu">()</span>  <span class="cm">// ✓ 完成</span></div>
            <div class="code-line cm">// → 已生成 8 个组件，1200 行代码</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 右侧：注册表单 -->
    <div class="auth-right">
      <div class="auth-card">
        <div class="card-header">
          <h1 class="card-title">创建账号</h1>
          <p class="card-sub">注册即可免费开始创作</p>
        </div>

        <a-form layout="vertical" @submit.prevent="handleSubmit">
          <a-form-item label="账号">
            <a-input
              v-model:value="form.userAccount"
              size="large"
              placeholder="请输入账号（至少 4 位）"
              allow-clear
            />
          </a-form-item>
          <a-form-item label="密码">
            <a-input-password
              v-model:value="form.userPassword"
              size="large"
              placeholder="请输入密码（至少 8 位）"
            />
          </a-form-item>
          <a-form-item label="确认密码">
            <a-input-password
              v-model:value="form.checkPassword"
              size="large"
              placeholder="请再次输入密码"
              @keyup.enter="handleSubmit"
            />
          </a-form-item>
          <a-button
            type="primary"
            size="large"
            block
            :loading="loading"
            class="submit-btn"
            @click="handleSubmit"
          >
            免费注册
          </a-button>
        </a-form>

        <div class="auth-foot">
          已有账号？
          <router-link to="/user/login">去登录</router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-wrap {
  min-height: 100vh;
  display: flex;
}

/* ====== 左侧 ====== */
.auth-left {
  position: relative;
  flex: 1;
  min-width: 0;
  background: linear-gradient(145deg, #0d1a40 0%, #1a2060 40%, #1a1060 70%, #0d0630 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 60px 56px;
  overflow: hidden;
}

.glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
}
.glow-1 {
  width: 400px;
  height: 400px;
  background: rgba(79, 140, 255, 0.35);
  top: -80px;
  right: -60px;
}
.glow-2 {
  width: 320px;
  height: 320px;
  background: rgba(108, 71, 255, 0.4);
  bottom: 80px;
  left: -60px;
}
.glow-3 {
  width: 200px;
  height: 200px;
  background: rgba(176, 92, 247, 0.3);
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
}

.left-content {
  position: relative;
  z-index: 1;
  max-width: 460px;
  width: 100%;
}

.left-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 48px;
}
.logo-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 11px;
  background: var(--brand-gradient);
  color: #fff;
  font-weight: 800;
  font-size: 18px;
  box-shadow: 0 4px 16px rgba(108, 71, 255, 0.5);
}
.logo-name {
  font-size: 22px;
  font-weight: 700;
  color: #fff;
}

.left-title {
  font-size: 46px;
  font-weight: 900;
  color: #fff;
  line-height: 1.15;
  letter-spacing: -1px;
  margin: 0 0 16px;
}
.left-grad {
  background: linear-gradient(90deg, #4f8cff, #6c47ff, #b05cf7);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.left-desc {
  font-size: 15px;
  color: rgba(255, 255, 255, 0.55);
  margin: 0 0 36px;
  line-height: 1.6;
}

.benefit-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 40px;
}
.benefit-item {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.75);
  letter-spacing: 0.2px;
}

/* 装饰代码卡片 */
.code-card {
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 14px;
  overflow: hidden;
  backdrop-filter: blur(8px);
}
.code-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 14px;
  background: rgba(255, 255, 255, 0.05);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.dot.red { background: #ff5f57; }
.dot.yellow { background: #febc2e; }
.dot.green { background: #28c840; }
.code-file {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.4);
  margin-left: 6px;
  font-family: monospace;
}
.code-body {
  padding: 14px 18px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
  font-size: 13px;
  line-height: 2;
}
.code-line { color: rgba(255, 255, 255, 0.75); }
.kw { color: #b05cf7; }
.fn { color: #6c9eff; }
.pu { color: rgba(255, 255, 255, 0.5); }
.str { color: #7ec98a; }
.cm { color: rgba(255, 255, 255, 0.3); font-style: italic; }

/* ====== 右侧 ====== */
.auth-right {
  width: 480px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 40px;
  background: #f7f8fc;
}

.auth-card {
  width: 100%;
  max-width: 360px;
}

.card-header {
  margin-bottom: 32px;
}
.card-title {
  font-size: 28px;
  font-weight: 800;
  color: var(--text-primary);
  margin: 0 0 8px;
  letter-spacing: -0.5px;
}
.card-sub {
  font-size: 14px;
  color: var(--text-secondary);
  margin: 0;
}

.submit-btn {
  margin-top: 4px;
  height: 44px;
  border-radius: 10px;
  font-weight: 600;
  font-size: 15px;
  background: var(--brand-gradient);
  border: none;
  box-shadow: 0 4px 16px rgba(108, 71, 255, 0.35);
  transition: opacity 0.18s, transform 0.18s;
}
.submit-btn:hover {
  opacity: 0.88;
  transform: translateY(-1px);
}

.auth-foot {
  margin-top: 20px;
  text-align: center;
  font-size: 14px;
  color: var(--text-secondary);
}

@media (max-width: 768px) {
  .auth-left { display: none; }
  .auth-right { width: 100%; background: #fff; }
}
</style>
