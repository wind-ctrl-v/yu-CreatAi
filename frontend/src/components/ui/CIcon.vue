<script setup lang="ts">
import { computed } from 'vue'
import { ICONS } from './icons'

const props = withDefaults(
  defineProps<{
    name: string
    size?: number | string
    stroke?: number
    spin?: boolean
  }>(),
  { size: 16, stroke: 1.8, spin: false },
)

const paths = computed(() => ICONS[props.name] ?? [])
const px = computed(() => (typeof props.size === 'number' ? `${props.size}px` : props.size))
</script>

<template>
  <svg
    class="c-icon"
    :class="{ 'is-spin': spin }"
    :style="{ width: px, height: px }"
    viewBox="0 0 24 24"
    fill="none"
    :stroke-width="stroke"
    stroke="currentColor"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <path v-for="(d, i) in paths" :key="i" :d="d" />
  </svg>
</template>

<style scoped>
.c-icon {
  display: inline-block;
  flex: none;
  vertical-align: -0.15em;
  overflow: visible;
}

.is-spin {
  animation: spin 0.85s linear infinite;
}
</style>
