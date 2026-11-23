<script setup>
import { ref } from 'vue'
import { ExternalLink } from '@lucide/vue'
import { api } from '../api'
const props = defineProps({ draft: Object })
const result = ref(null)
const busy = ref(false)
const error = ref('')
async function verify() {
  busy.value = true; error.value = ''; result.value = null
  try { result.value = await api('/knowledge/' + props.draft.id + '/sync-receipt') }
  catch (e) { error.value = e.message }
  finally { busy.value = false }
}
</script>
<template>
  <section class="form-section form-grid">
    <div class="toolbar"><h3>4.23 知识管理回执</h3>
      <a class="link" :href="draft.remoteUrl" target="_blank" rel="noopener noreferrer">打开业务知识 <ExternalLink :size="14"/></a></div>
    <p class="hash">业务 ID {{ draft.remoteId }}</p>
    <button class="button secondary" :disabled="busy" @click="verify">{{ busy ? '正在回读业务记录…' : '核验 4.23 当前内容' }}</button>
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <dl v-if="result" class="metadata">
      <div><dt>业务类别 {{ result.type }}{{ result.childType ? ' / '+result.childType : '' }}{{ result.knowledgeClass ? ' › '+result.knowledgeClass : '' }}</dt><dd>{{ result.categoryMatches ? '一致' : '有差异' }}</dd></div>
      <div><dt>标题</dt><dd>{{ result.titleMatches ? '一致' : '有差异' }}</dd></div>
      <div><dt>已审摘要</dt><dd>{{ result.summaryMatches ? '一致' : '有差异' }}</dd></div>
      <div v-if="result.pdfMatches !== null"><dt>PDF 原件 SHA-256</dt><dd>{{ result.pdfMatches ? '一致' : '有差异或缺少附件' }}</dd></div>
    </dl>
  </section>
</template>
