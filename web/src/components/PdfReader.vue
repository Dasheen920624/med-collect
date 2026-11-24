<script setup>
import { ref, watch } from 'vue'
import { ChevronLeft, ChevronRight, RotateCw } from '@lucide/vue'
import { api, original } from '../api'

const props = defineProps({ materialId: String, title: String })
const pages = ref(0)
const page = ref(1)
const input = ref(1)
const zoom = ref(100)
const error = ref('')
const revision = ref(0)
const loading = ref(true)
const viewport = ref(null)
watch(() => [props.materialId,revision.value], async ([id], _, onCleanup) => {
  let active = true
  onCleanup(() => { active = false })
  pages.value = 0
  error.value = ''
  loading.value = true
  try {
    const info = await api('/materials/'+id+'/pdf-preview')
    if (active) { pages.value=info.pages; if (!info.pages) throw Error('原件中没有可显示的页面') }
  } catch(e) { if (active) { error.value=e.message; loading.value=false } }
}, { immediate:true })
watch(() => props.materialId, () => { page.value=1; input.value=1; zoom.value=100 })
function go(value) {
  const next = Math.max(1,Math.min(pages.value,Math.trunc(Number(value)) || 1))
  input.value = next
  if (page.value === next) return
  error.value=''
  loading.value=true
  page.value=next
  viewport.value?.scrollTo(0,0)
}
defineExpose({go})
</script>
<template>
  <section class="pdf-reader" aria-label="PDF 原文阅读器">
    <div class="pdf-controls"><button class="icon-button" :disabled="page<=1" aria-label="上一页原文" @click="go(page-1)"><ChevronLeft :size="17" /></button>
      <form class="pdf-page" @submit.prevent="go(input)"><label>第<input v-model.number="input" type="number" :min="1" :max="pages || 1" aria-label="原文页码" @change="go(input)" /></label>
        <span>/ {{ pages || '—' }} 页</span></form>
      <button class="icon-button" :disabled="page>=pages" aria-label="下一页原文" @click="go(page+1)"><ChevronRight :size="17" /></button>
      <select v-model.number="zoom" aria-label="原文缩放"><option :value="100">适合宽度</option><option v-for="n in [125,150,200]" :key="n" :value="n">{{ n }}%</option></select>
      <a :href="original(materialId,true)" target="_blank" rel="noopener noreferrer">打开 PDF ↗</a>
    </div>
    <div ref="viewport" class="pdf-page-scroll" :aria-busy="loading">
      <p v-if="error" role="alert" class="message error">{{ error }} <button class="link" @click="revision++"><RotateCw :size="14" />重试</button></p>
      <p v-else-if="loading" class="pdf-loading" role="status">正在读取第 {{ page }} 页…</p>
      <img v-if="pages && !error" :key="materialId + '/' + page + '/' + revision" :style="{width:zoom+'%'}"
        :src="'/api/materials/'+materialId+'/pdf-preview/'+page" :alt="title+'，第 '+page+' 页原文'"
        @load="loading=false" @error="loading=false; error='页面预览暂不可用，可重试或打开 PDF 原件。'" />
    </div>
  </section>
</template>
