<script setup>
import { ChevronLeft, ChevronRight } from '@lucide/vue'

defineProps({ page: Number, total: Number, size: Number })
const emit = defineEmits(['change'])
</script>

<template>
  <div v-if="total > 0" class="pagination">
    <span>共 {{ total }} 条</span>
    <div>
      <button class="icon-button" aria-label="上一页" :disabled="page <= 1" @click="emit('change', page - 1)"><ChevronLeft :size="18" /></button>
      <span>{{ page }} / {{ Math.max(1, Math.ceil(total / size)) }}</span>
      <button class="icon-button" aria-label="下一页" :disabled="page * size >= total" @click="emit('change', page + 1)"><ChevronRight :size="18" /></button>
    </div>
  </div>
</template>
