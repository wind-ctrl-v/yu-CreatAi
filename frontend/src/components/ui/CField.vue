<script setup lang="ts">
withDefaults(
  defineProps<{
    label?: string
    hint?: string
    error?: string
    required?: boolean
    inputId?: string
  }>(),
  { required: false },
)
</script>

<template>
  <div class="c-field" :class="{ 'has-error': !!error }">
    <label v-if="label" class="c-field__label" :for="inputId">
      {{ label }}
      <span v-if="required" class="c-field__req" aria-hidden="true">*</span>
    </label>
    <div class="c-field__control">
      <slot />
    </div>
    <p v-if="error" class="c-field__error">{{ error }}</p>
    <p v-else-if="hint" class="c-field__hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.c-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.c-field__label {
  display: flex;
  align-items: center;
  gap: 3px;
  font-size: var(--fs-xs);
  font-weight: 500;
  color: var(--text-secondary);
  letter-spacing: var(--tracking-tight);
}

.c-field__req { color: var(--danger); }

.c-field__control { display: flex; flex-direction: column; }

.c-field__error {
  font-size: var(--fs-2xs);
  color: var(--danger);
  line-height: 1.5;
}

.c-field__hint {
  font-size: var(--fs-2xs);
  color: var(--text-faint);
  line-height: 1.5;
}
</style>
