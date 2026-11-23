<script setup>
import { computed } from 'vue'

const props = defineProps({ state: String, labels: Object })
const tone = computed(() => {
  if (['APPROVED', 'SUCCEEDED', 'CAPTURED', 'SYNCED', 'ACTIVE'].includes(props.state)) return 'green'
  if (['FAILED', 'RETURNED', 'SYNC_FAILED', 'PARTIAL'].includes(props.state)) return 'amber'
  if (['PENDING', 'RUNNING', 'SYNCING'].includes(props.state)) return 'blue'
  return 'gray'
})
</script>

<template><span class="badge" :class="tone">{{ labels?.[state] || state || '—' }}</span></template>
