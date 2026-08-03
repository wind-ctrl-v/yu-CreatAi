<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getPlanCapabilities, updateMyProfile } from '../api/user'
import CAvatar from '../components/ui/CAvatar.vue'
import CButton from '../components/ui/CButton.vue'
import CField from '../components/ui/CField.vue'
import CInput from '../components/ui/CInput.vue'
import CTag from '../components/ui/CTag.vue'
import CTextarea from '../components/ui/CTextarea.vue'
import { message } from '../components/ui/toast'
import { useLoginUserStore } from '../stores/loginUser'
import { type PlanCapabilities } from '../types'

const router = useRouter()
const store = useLoginUserStore()

const user = computed(() => store.loginUser)
const plan = ref<PlanCapabilities | null>(null)
const editing = ref(false)
const saving = ref(false)

const editForm = ref({ userName: '', userProfile: '' })

const MODE_LABELS: Record<string, string> = {
  low_cost: '快速',
  standard: '标准',
  high_quality: '高质量',
}

const displayName = computed(() => user.value?.userName || user.value?.userAccount || '未命名用户')

const modeText = computed(
  () => plan.value?.availableModes.map((m) => MODE_LABELS[m] ?? m).join('、') ?? '',
)

const details = computed(() => [
  { label: '账号', value: user.value?.userAccount },
  { label: '昵称', value: user.value?.userName },
  { label: '注册时间', value: user.value?.createTime },
  { label: '可用生成模式', value: modeText.value },
])

function startEdit() {
  editForm.value.userName = user.value?.userName || ''
  editForm.value.userProfile = user.value?.userProfile || ''
  editing.value = true
}

async function saveEdit() {
  saving.value = true
  const res = await updateMyProfile({
    userName: editForm.value.userName,
    userProfile: editForm.value.userProfile,
  }).catch(() => null)
  saving.value = false
  if (res?.code === 0) {
    message.success('保存成功')
    await store.fetchLoginUser()
    editing.value = false
  }
}

onMounted(async () => {
  const res = await getPlanCapabilities().catch(() => null)
  if (res?.code === 0) plan.value = res.data
})
</script>

<template>
  <div class="page container">
    <header class="page-head">
      <div>
        <p class="eyebrow">Account</p>
        <h1 class="page-title">个人中心</h1>
        <p class="page-subtitle">管理你的资料与套餐信息。</p>
      </div>
      <CButton variant="secondary" icon="grid" @click="router.push('/apps')">我的作品</CButton>
    </header>

    <div class="layout">
      <!-- 资料卡 -->
      <section class="panel identity">
        <CAvatar :src="user?.userAvatar" :name="displayName" :size="64" />
        <h2 class="identity__name">{{ displayName }}</h2>
        <p class="identity__account">@{{ user?.userAccount }}</p>

        <div class="identity__tags">
          <CTag v-if="user?.userRole === 'admin'" tone="spark" icon="shield">管理员</CTag>
          <CTag v-if="plan" tone="neutral" icon="layers">{{ plan.planName }}</CTag>
        </div>

        <p class="identity__bio">{{ user?.userProfile || '还没有填写个人简介。' }}</p>

        <div class="identity__acts">
          <CButton v-if="!editing" variant="secondary" size="sm" icon="pencil" block @click="startEdit">
            编辑资料
          </CButton>
          <CButton
            v-if="user?.userRole === 'admin'"
            variant="ghost"
            size="sm"
            icon="settings"
            block
            @click="router.push('/admin')"
          >
            管理后台
          </CButton>
        </div>
      </section>

      <!-- 详情 / 编辑 -->
      <section class="panel detail">
        <header class="detail__head">
          <h3 class="detail__title">{{ editing ? '编辑资料' : '账号信息' }}</h3>
          <div v-if="editing" class="detail__acts">
            <CButton variant="ghost" size="sm" @click="editing = false">取消</CButton>
            <CButton variant="primary" size="sm" :loading="saving" @click="saveEdit">保存</CButton>
          </div>
        </header>

        <div v-if="editing" class="form">
          <CField label="昵称" input-id="profile-name">
            <CInput
              id="profile-name"
              v-model="editForm.userName"
              placeholder="怎么称呼你？"
              :maxlength="30"
            />
          </CField>
          <CField label="个人简介" input-id="profile-bio">
            <CTextarea
              id="profile-bio"
              v-model="editForm.userProfile"
              :rows="4"
              :maxlength="200"
              placeholder="介绍一下你自己…"
            />
          </CField>
        </div>

        <dl v-else class="rows">
          <div v-for="d in details" :key="d.label" class="row">
            <dt class="row__label">{{ d.label }}</dt>
            <dd class="row__value">{{ d.value || '—' }}</dd>
          </div>
        </dl>
      </section>
    </div>
  </div>
</template>

<style scoped>
.page {
  flex: 1;
  padding-top: var(--sp-10);
  padding-bottom: var(--sp-16);
}

.layout {
  display: grid;
  grid-template-columns: 300px 1fr;
  gap: var(--sp-4);
  align-items: start;
}

.panel {
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-xs);
}

/* ── 资料卡 ── */
.identity {
  padding: var(--sp-6);
  text-align: center;
}

.identity__name {
  margin-top: var(--sp-4);
  font-size: var(--fs-lg);
  font-weight: 600;
  color: var(--text-strong);
}

.identity__account {
  margin-top: 3px;
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

.identity__tags {
  display: flex;
  justify-content: center;
  gap: 6px;
  margin-top: var(--sp-4);
  flex-wrap: wrap;
}

.identity__bio {
  margin-top: var(--sp-4);
  padding-top: var(--sp-4);
  border-top: 1px solid var(--border-subtle);
  font-size: var(--fs-sm);
  line-height: 1.65;
  color: var(--text-secondary);
  text-align: left;
}

.identity__acts {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin-top: var(--sp-5);
}

/* ── 详情 ── */
.detail__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 15px 20px;
  border-bottom: 1px solid var(--border-subtle);
}

.detail__title {
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--text-strong);
}

.detail__acts { display: flex; gap: 7px; }

.form {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  padding: var(--sp-5) var(--sp-5) var(--sp-6);
}

.rows { margin: 0; }

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  padding: 14px 20px;
  border-bottom: 1px solid var(--border-subtle);
}

.row:last-child { border-bottom: none; }

.row__label {
  font-size: var(--fs-sm);
  color: var(--text-muted);
}

.row__value {
  margin: 0;
  font-size: var(--fs-sm);
  font-weight: 450;
  color: var(--text-primary);
  text-align: right;
  overflow-wrap: anywhere;
}

@media (max-width: 820px) {
  .layout { grid-template-columns: 1fr; }
}
</style>
