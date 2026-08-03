<script setup lang="ts">
import ConfirmHost from './components/ui/ConfirmHost.vue'
import ToastHost from './components/ui/ToastHost.vue'
</script>

<template>
  <router-view />
  <ToastHost />
  <ConfirmHost />
</template>
