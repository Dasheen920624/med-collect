<script setup>
import { computed, ref } from 'vue'
import { ExternalLink } from '@lucide/vue'
import { api } from '../api'
import { date } from '../labels'

const props = defineProps({ draftId: String, candidate: Object })
const detail = ref(null), error = ref(''), loading = ref(false)
const enabled = computed(() => ({'1':'启用','0':'停用'})[detail.value?.enabled] || '未返回')
const publication = computed(() => ({'1':'已发布','0':'未发布','2':'已撤回'})[detail.value?.publication] || '未返回')
const modified = computed(() => {
  const value = detail.value?.updatedAt
  return value && /^\d{13}$/.test(value) ? date(Number(value),true) : value
})
const sourceLink = computed(() => /^https?:\/\//i.test(detail.value?.source || ''))
async function load() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    detail.value = await api('/knowledge/' + props.draftId + '/business-records/' + encodeURIComponent(props.candidate.id))
  } catch (e) { error.value = e.message }
  finally { loading.value = false }
}
</script>

<template>
  <article class="business-reference">
    <a v-if="candidate.url" :href="candidate.url" target="_blank" rel="noopener noreferrer">{{ candidate.title || '查看 4.23 业务记录' }} <ExternalLink :size="13" aria-hidden="true" /></a>
    <strong v-else>{{ candidate.title || '4.23 业务记录' }}</strong>
    <small v-if="candidate.basis" class="subline">{{ candidate.basis }} · {{ candidate.collectorCreated ? '采集系统新增' : '业务原有' }}</small>
    <small class="subline business-id">业务 ID {{ candidate.id }}</small>
    <details class="raw-text" @toggle="e => { if (e.target.open && !detail) load() }">
      <summary>查看业务资料和停用位置</summary>
      <div class="form-grid inset">
        <p v-if="loading" class="muted" role="status">正在读取 4.23 资料</p>
        <p v-if="error" class="message error" role="alert">{{ error }} <button class="link" @click="load">重试</button></p>
        <template v-if="detail">
          <dl class="metadata">
            <div><dt>业务入口</dt><dd>{{ detail.category }}</dd></div>
            <div><dt>知识类别</dt><dd>{{ detail.categoryName || '未分类' }}<template v-if="detail.categoryName && detail.categoryName !== detail.record.knowledgeClass">（{{ detail.record.knowledgeClass }}）</template></dd></div>
            <div><dt>启用 / 发布</dt><dd>{{ enabled }} · {{ publication }}</dd></div>
            <div v-if="detail.record.doi"><dt>DOI</dt><dd>{{ detail.record.doi }}</dd></div>
            <div v-if="detail.version || modified"><dt>版本 / 修改时间</dt><dd>{{ [detail.version, modified].filter(Boolean).join(' · ') }}</dd></div>
            <div v-if="detail.source"><dt>业务来源</dt><dd><a v-if="sourceLink" :href="detail.source" :title="detail.source" target="_blank" rel="noopener noreferrer">查看原发布页</a><template v-else>{{ detail.source }}</template></dd></div>
          </dl>
          <p v-if="detail.summary" class="reading-text">{{ detail.summary }}</p>
          <ul v-if="detail.attachments.length" class="simple-list" aria-label="业务附件">
            <li v-for="(file,i) in detail.attachments" :key="i"><a v-if="file.url" :href="file.url" target="_blank" rel="noopener noreferrer">{{ file.name }}</a><span v-else>{{ file.name }}</span></li>
          </ul>
          <p class="muted">在 4.23 的「知识管理」进入上述业务入口，按标题找到同一条记录，在「是否启用」列处理。</p>
          <a :href="detail.managementUrl" target="_blank" rel="noopener noreferrer">打开 4.23 知识管理 <ExternalLink :size="13" aria-hidden="true" /></a>
        </template>
      </div>
    </details>
  </article>
</template>
