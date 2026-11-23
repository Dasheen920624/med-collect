<script setup>
import { Inbox } from '@lucide/vue'
defineProps({ title: String, detail: String })
</script>

<template>
  <div class="empty">
    <div class="empty-icon"><Inbox :size="25" :stroke-width="1.65" /></div>
    <strong>{{ title }}</strong>
    <p v-if="detail">{{ detail }}</p>
    <slot />
  </div>
</template>
