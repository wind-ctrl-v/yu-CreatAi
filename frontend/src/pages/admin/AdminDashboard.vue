<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  adminListAppByPage,
  adminListChatHistory,
  adminListFingerprintByPage,
  adminListUserByPage,
} from '../../api/admin'
import CIcon from '../../components/ui/CIcon.vue'

const router = useRouter()

const stats = ref({
  totalUsers: 0,
  totalApps: 0,
  totalChats: 0,
  totalFingerprints: 0,
})
const loading = ref(true)

const cards = [
  {
    key: 'users',
    label: '注册用户',
    icon: 'users',
    path: '/admin/users',
    getValue: () => stats.value.totalUsers,
  },
  {
    key: 'apps',
    label: '创建应用',
    icon: 'grid',
    path: '/admin/apps',
    getValue: () => stats.value.totalApps,
  },
  {
    key: 'chats',
    label: '对话次数',
    icon: 'message-square',
    path: '/admin/chats',
    getValue: () => stats.value.totalChats,
  },
  {
    key: 'fingerprints',
    label: '设计指纹',
    icon: 'fingerprint',
    path: '/admin/fingerprints',
    getValue: () => stats.value.totalFingerprints,
  },
]

const quickLinks = [
  {
    icon: 'users',
    label: '用户管理',
    desc: '查看、编辑用户信息及权限',
    path: '/admin/users',
  },
  {
    icon: 'grid',
    label: '应用管理',
    desc: '管理所有应用，支持删除与更新',
    path: '/admin/apps',
  },
  {
    icon: 'message-square',
    label: '对话历史',
    desc: '查看全平台用户的对话记录',
    path: '/admin/chats',
  },
  {
    icon: 'fingerprint',
    label: '设计指纹',
    desc: '审核 AI 提取的设计指纹，纳入知识库',
    path: '/admin/fingerprints',
  },
]

onMounted(async () => {
  try {
    const [usersRes, appsRes, chatsRes, fingerprintsRes] = await Promise.all([
      adminListUserByPage({ pageNum: 1, pageSize: 1 }),
      adminListAppByPage({ pageNum: 1, pageSize: 1 }),
      adminListChatHistory({ pageNum: 1, pageSize: 1 }),
      adminListFingerprintByPage({ pageNum: 1, pageSize: 1 }).catch(() => ({ code: -1 })),
    ])
    if (usersRes?.code === 0) stats.value.totalUsers = usersRes.data?.totalRow ?? 0
    if (appsRes?.code === 0) stats.value.totalApps = appsRes.data?.totalRow ?? 0
    if (chatsRes?.code === 0) stats.value.totalChats = chatsRes.data?.totalRow ?? 0
    if (fingerprintsRes?.code === 0)
      stats.value.totalFingerprints = (fingerprintsRes as any).data?.totalRow ?? 0
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="dash">
    <header class="page-head">
      <div>
        <p class="eyebrow">Overview</p>
        <h1 class="page-title">平台概览</h1>
        <p class="page-subtitle">下面是当前平台的整体运行数据。</p>
      </div>
    </header>

    <!-- 统计 -->
    <div class="stats">
      <button
        v-for="card in cards"
        :key="card.key"
        class="stat"
        type="button"
        @click="router.push(card.path)"
      >
        <span class="stat__top">
          <span class="stat__icon"><CIcon :name="card.icon" :size="16" /></span>
          <span class="stat__label">{{ card.label }}</span>
          <CIcon name="arrow-right" :size="14" class="stat__go" />
        </span>
        <span class="stat__value">
          <span v-if="loading" class="skeleton stat__skeleton" />
          <template v-else>{{ card.getValue().toLocaleString() }}</template>
        </span>
      </button>
    </div>

    <!-- 快捷入口 -->
    <section class="quick">
      <h2 class="quick__title">快捷管理</h2>
      <div class="quick__grid">
        <button
          v-for="q in quickLinks"
          :key="q.path"
          class="qcard"
          type="button"
          @click="router.push(q.path)"
        >
          <span class="qcard__icon"><CIcon :name="q.icon" :size="17" /></span>
          <span class="qcard__label">{{ q.label }}</span>
          <span class="qcard__desc">{{ q.desc }}</span>
          <CIcon name="arrow-right" :size="14" class="qcard__go" />
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.dash { max-width: 1080px; }

/* ── 统计卡 ── */
.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--sp-3);
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px 18px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-lg);
  background: var(--bg-surface);
  box-shadow: var(--shadow-xs);
  cursor: pointer;
  text-align: left;
  transition: all var(--t-base) var(--ease-out);
}

.stat:hover {
  border-color: var(--border-strong);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}

.stat__top {
  display: flex;
  align-items: center;
  gap: 9px;
}

.stat__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  flex: none;
  border-radius: var(--r-sm);
  background: var(--n-100);
  border: 1px solid var(--border-subtle);
  color: var(--text-primary);
}

.stat__label {
  font-size: var(--fs-xs);
  color: var(--text-muted);
}

.stat__go {
  margin-left: auto;
  color: var(--n-300);
  transition: color var(--t-fast) var(--ease-out);
}

.stat:hover .stat__go { color: var(--text-primary); }

.stat__value {
  font-size: 30px;
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: var(--tracking-heading);
  color: var(--text-strong);
  font-variant-numeric: tabular-nums;
}

.stat__skeleton {
  display: inline-block;
  width: 64px;
  height: 26px;
}

/* ── 快捷入口 ── */
.quick { margin-top: var(--sp-10); }

.quick__title {
  margin-bottom: var(--sp-4);
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--text-strong);
}

.quick__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--sp-3);
}

.qcard {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 18px 44px 18px 18px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--r-lg);
  background: var(--bg-surface);
  box-shadow: var(--shadow-xs);
  text-align: left;
  cursor: pointer;
  transition: all var(--t-base) var(--ease-out);
}

.qcard:hover {
  border-color: var(--border-strong);
  box-shadow: var(--shadow-md);
}

.qcard__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  margin-bottom: 6px;
  border-radius: var(--r-sm);
  background: var(--n-100);
  border: 1px solid var(--border-subtle);
  color: var(--text-primary);
}

.qcard__label {
  font-size: var(--fs-sm);
  font-weight: 550;
  color: var(--text-strong);
}

.qcard__desc {
  font-size: var(--fs-xs);
  line-height: 1.55;
  color: var(--text-muted);
}

.qcard__go {
  position: absolute;
  top: 20px;
  right: 18px;
  color: var(--n-300);
  transition: color var(--t-fast) var(--ease-out);
}

.qcard:hover .qcard__go { color: var(--text-primary); }

@media (max-width: 1000px) {
  .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 640px) {
  .stats,
  .quick__grid { grid-template-columns: 1fr; }
}
</style>
