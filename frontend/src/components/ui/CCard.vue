<script setup lang="ts">
withDefaults(
  defineProps<{
    padding?: boolean
    hoverable?: boolean
    radius?: 'md' | 'lg' | 'xl' | '2xl'
    flush?: boolean
  }>(),
  { padding: true, hoverable: false, radius: 'xl', flush: false },
)
</script>

<template>
  <div class="c-card" :class="[`r-${radius}`, { 'is-hoverable': hoverable, 'is-flush': flush }]">
    <header v-if="$slots.header" class="c-card__head">
      <slot name="header" />
    </header>
    <div class="c-card__body" :class="{ 'has-pad': padding }">
      <slot />
    </div>
    <footer v-if="$slots.footer" class="c-card__foot">
      <slot name="footer" />
    </footer>
  </div>
</template>

<style scoped>
.c-card {
  display: flex;
  flex-direction: column;
  background: var(--bg-surface);
  border: 1px solid var(--border-subtle);
  box-shadow: var(--shadow-xs);
  transition:
    border-color var(--t-base) var(--ease-out),
    box-shadow var(--t-base) var(--ease-out),
    transform var(--t-base) var(--ease-out);
  overflow: hidden;
}

.r-md { border-radius: var(--r-md); }
.r-lg { border-radius: var(--r-lg); }
.r-xl { border-radius: var(--r-xl); }
.r-2xl { border-radius: var(--r-2xl); }

.is-flush { border: none; box-shadow: none; background: transparent; }

.is-hoverable:hover {
  border-color: var(--border-strong);
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}

.c-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 15px 20px;
  border-bottom: 1px solid var(--border-subtle);
}

.c-card__body.has-pad { padding: 20px; }

.c-card__foot {
  padding: 13px 20px;
  border-top: 1px solid var(--border-subtle);
  background: var(--n-25);
}
</style>
