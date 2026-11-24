<script setup>
import { ref } from 'vue'
import { list } from '../api'
import Pagination from './Pagination.vue'

const props = defineProps({ draftId: String, busy: Boolean })
const emit = defineEmits(['select'])
const name = ref('')
const matches = ref(null)
const error = ref('')
const loading = ref(false)
let request = 0
function resetResults() {
  request++
  matches.value = null
  loading.value = false
  error.value = ''
}

async function search(page = 1) {
  const seq = ++request
  error.value = ''
  if (!name.value.trim()) { matches.value = null; loading.value = false; return }
  loading.value = true
  try {
    const result = await list('/knowledge/' + props.draftId + '/sync-targets', { query:name.value.trim(), page })
    if (seq === request) matches.value = result
  } catch (e) {
    if (seq === request) { error.value = e.message; matches.value = null }
  } finally { if (seq === request) loading.value = false }
}
</script>
<template>
  <details class="raw-text">
    <summary>按名称选择业务知识</summary>
    <div class="form-grid inset">
      <p class="muted">可选择同类型、同子类的知识核对差异。只有带采集系统新增回执的知识支持版本更新。</p>
      <form class="actions" @submit.prevent="search()"><input v-model="name" aria-label="4.23 业务知识名称" maxlength="100" placeholder="输入 4.23 的业务知识名称" required
        @input="resetResults" />
        <button class="button secondary" :disabled="loading || busy">查询业务知识</button></form>
      <p v-if="error" class="message error" role="alert">{{ error }}</p>
      <ul v-if="matches?.rows.length" class="simple-list"><li v-for="item in matches.rows" :key="item.id">
        <span><a :href="item.url" target="_blank" rel="noopener">{{ item.title }}</a>
          <small class="subline">{{ item.collectorCreated ? '采集系统新增' : '业务原有 · 仅对照' }} · {{ item.knowledgeClass || '未分子类' }} · {{ item.doi || item.id }}</small></span>
        <button class="button secondary" :disabled="busy || loading" @click="emit('select',item.id)">选择并核对差异</button>
      </li></ul>
      <p v-else-if="matches" class="muted">本页没有符合该稿件类别的知识。</p>
      <Pagination v-if="matches && matches.total > matches.size" v-bind="matches" @change="search" />
    </div>
  </details>
</template>
