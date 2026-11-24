<script setup>
import { computed, ref, watch } from 'vue'
const props = defineProps({ page: Number, total: Number, size: Number, resizable: Boolean })
const emit = defineEmits(['change','resize'])
const pages = computed(() => Math.max(1, Math.ceil(props.total / props.size)))
const jump = ref(1)
watch(() => props.page, p => { jump.value = p }, { immediate: true })
function go(page) {
  const target = Math.max(1, Math.min(pages.value, Math.trunc(Number(page)) || 1))
  jump.value = target
  if (target !== props.page) emit('change',target)
}
</script>
<template>
  <div v-if="total > 0" class="pagination">
    <span>共 <b>{{ total }}</b> 条　{{ (page - 1) * size + 1 }}–{{ Math.min(page * size,total) }} 条</span>
    <div>
      <label v-if="resizable" class="pager-field">每页<select :value="size" aria-label="每页条数" @change="emit('resize',Number($event.target.value))">
        <option v-for="n in [10,20,50,100]" :key="n" :value="n">{{ n }} 条</option></select></label>
      <button class="button secondary" :disabled="page <= 1" @click="go(page - 1)">上一页</button>
      <span class="current-page" aria-current="page">{{ page }}</span>
      <button class="button secondary" :disabled="page >= pages" @click="go(page + 1)">下一页</button>
      <span>{{ page }} / {{ pages }} 页</span>
      <form class="jump-form" @submit.prevent="go(jump)"><label class="pager-field">前往<input v-model.number="jump" type="number" min="1" :max="pages" aria-label="跳转页码" />页</label>
        <button class="button secondary">确定</button></form>
    </div>
  </div>
</template>
