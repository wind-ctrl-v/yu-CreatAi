<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import CIcon from './CIcon.vue'

const props = withDefaults(
  defineProps<{
    src?: string | null
    name?: string | null
    size?: number
    icon?: string
    square?: boolean
  }>(),
  { size: 32, square: false },
)

const failed = ref(false)
watch(() => props.src, () => (failed.value = false))

const showImg = computed(() => !!props.src && !failed.value)

const initial = computed(() => {
  const n = (props.name ?? '').trim()
  if (!n) return ''
  const ch = n[0]
  return /[a-zA-Z]/.test(ch) ? ch.toUpperCase() : ch
})

/* 由名字派生稳定的中性底色，避免随机彩色破坏极简风 */
const bgTone = computed(() => {
  const n = props.name ?? ''
  let sum = 0
  for (let i = 0; i < n.length; i++) sum += n.charCodeAt(i)
  const tones = ['var(--n-150)', 'var(--n-200)', 'var(--n-100)', 'var(--n-300)']
  return tones[sum % tones.length]
})
</script>

<template>
  <span
    class="c-avatar"
    :class="{ 'is-square': square }"
    :style="{
      width: `${size}px`,
      height: `${size}px`,
      fontSize: `${Math.max(10, Math.round(size * 0.42))}px`,
      background: showImg ? 'var(--n-100)' : bgTone,
    }"
  >
    <img v-if="showImg" :src="src!" :alt="name ?? 'avatar'" loading="lazy" @error="failed = true" />
    <CIcon v-else-if="icon || !initial" :name="icon || 'user'" :size="Math.round(size * 0.5)" />
    <span v-else class="c-avatar__txt">{{ initial }}</span>
  </span>
</template>

<style scoped>
.c-avatar {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  overflow: hidden;
  border-radius: var(--r-pill);
  color: var(--text-secondary);
  font-weight: 500;
  line-height: 1;
  user-select: none;
  box-shadow: inset 0 0 0 1px rgba(26, 26, 24, 0.06);
}

.c-avatar.is-square { border-radius: var(--r-sm); }

.c-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.c-avatar__txt { letter-spacing: 0; }
</style>
