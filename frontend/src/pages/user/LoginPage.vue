<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { login } from '../../api/user'
import { useLoginUserStore } from '../../stores/loginUser'

const router = useRouter()
const route = useRoute()
const store = useLoginUserStore()

const form = reactive({
  userAccount: '',
  userPassword: '',
})

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
</script>

<template>
  <div class="auth-wrap">
    <div class="auth-card">
      <div class="brand">
        <span class="brand-mark">YU</span>
        <h1 class="brand-title">Code Father</h1>
        <p class="brand-sub">用一句话，让 AI 帮你生成完整网站</p>
      </div>

      <a-form layout="vertical" @submit.prevent="handleSubmit">
        <a-form-item label="账号">
          <a-input
            v-model:value="form.userAccount"
            size="large"
            placeholder="请输入账号"
            allow-clear
          />
        </a-form-item>
        <a-form-item label="密码">
          <a-input-password
            v-model:value="form.userPassword"
            size="large"
            placeholder="请输入密码"
            @keyup.enter="handleSubmit"
          />
        </a-form-item>
        <a-button
          type="primary"
          size="large"
          block
          :loading="loading"
          @click="handleSubmit"
        >
          登录
        </a-button>
      </a-form>

      <div class="auth-foot">
        还没有账号？
        <router-link to="/user/register">立即注册</router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: linear-gradient(135deg, #e8f1ff 0%, #f5f7fa 50%, #eef4ff 100%);
}

.auth-card {
  width: 100%;
  max-width: 400px;
  padding: 40px 36px;
  background: #fff;
  border-radius: 18px;
  box-shadow: 0 18px 50px rgba(22, 119, 255, 0.12);
}

.brand {
  text-align: center;
  margin-bottom: 28px;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 14px;
  background: linear-gradient(135deg, #1677ff, #4096ff);
  color: #fff;
  font-weight: 700;
  font-size: 20px;
  letter-spacing: 1px;
}

.brand-title {
  margin: 14px 0 4px;
  font-size: 26px;
  font-weight: 600;
  color: var(--text-primary);
}

.brand-sub {
  margin: 0;
  font-size: 14px;
  color: var(--text-secondary);
}

.auth-foot {
  margin-top: 18px;
  text-align: center;
  font-size: 14px;
  color: var(--text-secondary);
}
</style>
