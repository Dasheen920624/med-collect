<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ExternalLink, FileText, Search, Send, Sparkles } from '@lucide/vue'
import { api, list, original, send } from '../api'
import { date, knowledgeStates, relations } from '../labels'
import Drawer from '../components/Drawer.vue'
import EmptyState from '../components/EmptyState.vue'
import BusinessRelation from '../components/BusinessRelation.vue'
import BusinessTargetPicker from '../components/BusinessTargetPicker.vue'
import BusinessSyncReceipt from '../components/BusinessSyncReceipt.vue'
import BusinessProperty from '../components/BusinessProperty.vue'
import PdfReader from '../components/PdfReader.vue'
import Events from '../components/Events.vue'
import KnowledgeDifferences from '../components/KnowledgeDifferences.vue'
import Pagination from '../components/Pagination.vue'
import StateBadge from '../components/StateBadge.vue'

const props = defineProps({ session: Object, detail: String, subjectId: String })
const emit = defineEmits(['navigate', 'notice'])
const table = ref({ rows: [], total: 0, page: 1, size: 10 })
const freshFilters = () => ({ query: '', status: '', human: '', sync: '', materialId: '', submission:'', publication:'' })
const filters = ref(freshFilters())
const pageSize = ref(10)
const materialChoices = ref([])
const materialQuery = ref('')
const choicesTotal = ref(0)
const content = ref(null)
const reading = ref('split')
const sourceText = ref('')
const sourceError = ref('')
const evidenceQuery=ref(''), evidenceMessage=ref(''), hit=ref(null), pdfReader=ref(null), marked=ref(null)
let evidenceRequest=0
async function locateEvidence(query=evidenceQuery.value) {
  const term=query.trim(), id=content.value?.material.id, seq=++evidenceRequest
  if (!id || !term) return
  evidenceQuery.value=term; evidenceMessage.value=''; hit.value=null
  if (reading.value==='knowledge') reading.value='split'
  try {
    if (content.value.material.formatCode==='PDF') {
      const result=await list('/materials/'+id+'/pdf-preview/locate',{q:term})
      if (seq!==evidenceRequest) return
      if (result.page) { pdfReader.value?.go(result.page); evidenceMessage.value='已定位到第 '+result.page+' 页' }
      else evidenceMessage.value='原件文字层中未找到该片段'
    } else {
      const at=sourceText.value.toLowerCase().indexOf(term.toLowerCase())
      if (at<0) evidenceMessage.value='原文中未找到该片段'
      else { hit.value={at,end:at+term.length}; await nextTick(); marked.value?.scrollIntoView({block:'center'}) }
    }
  } catch(e) { if(seq===evidenceRequest) evidenceMessage.value=e.message }
}
const siblings = ref(null)
const siblingPage = ref(1)
const classLabel = ref('')
const classOptions = ref([])
watch(() => [content.value?.material?.id, siblingPage.value], async ([materialId,page], _, onCleanup) => {
  let active = true
  onCleanup(() => { active = false })
  siblings.value = null
  if (!materialId) return
  try {
    const result = await list('/knowledge',{materialId,page,size:20})
    if (active) siblings.value = result
  } catch (e) { if (active) panelIssue.value = e.message }
})
watch(() => [content.value?.knowledge?.typeCode,content.value?.knowledge?.knowledgeClass], async ([type,code], _, onCleanup) => {
  let active = true
  onCleanup(() => { active = false })
  classLabel.value = ''
  classOptions.value = []
  if (!type) return
  try {
    const options = await api('/categories/'+type+'/classes')
    if (active) { classOptions.value = options; classLabel.value = options.find(c => c.code === code)?.name || '' }
  } catch { /* The stored business code remains visible when the directory is unavailable. */ }
})
const humanLabels = { DRAFT:'草稿', PENDING:'待审核', APPROVED:'已通过', RETURNED:'已退回', REFERENCE_ONLY:'仅供对照' }
const syncLabels = { READY:'审核通过待同步', UNSYNCED:'未同步', SYNCING:'同步中', SYNCED:'已写入 4.23', SKIPPED:'相同已跳过', SYNC_FAILED:'同步异常', REFERENCE_ONLY:'业务原有 · 仅对照' }
const outcomeLabels = { ...syncLabels, CREATE:'已新增', UPDATE:'已更新' }
const humanState = row => row.status in humanLabels ? row.status : 'APPROVED'
const syncState = row => row.syncPhase === 'SKIPPED' ? 'SKIPPED' : row.deliveredOperation || (row.status === 'APPROVED' ? 'READY' : row.status in syncLabels ? row.status : 'UNSYNCED')
const latestAdvice = computed(() => content.value?.events?.find(e => e.action === 'AI_REVIEW'))
let request = 0
let choiceRequest = 0
let choiceTimer
async function loadChoices() {
  const seq = ++choiceRequest
  try {
    const items = await list('/materials', { query: materialQuery.value, size: 50, subjectId: props.subjectId })
    if (seq === choiceRequest) { materialChoices.value = items.rows; choicesTotal.value = items.total }
  } catch (e) { if (seq === choiceRequest) issue.value = e.message }
}
watch(materialQuery, () => { clearTimeout(choiceTimer); choiceTimer = setTimeout(loadChoices,180) })
const categories = ref({})
const form = ref(null)
const note = ref('')
const issue = ref('')
const panelIssue = ref('')
const advice = ref(null)
const versions = ref(null)
const versionDetail = ref(null)
const busy = ref(false)
const loading = ref(false)
let poll

const draft = computed(() => content.value?.knowledge)
const review = computed(() => content.value?.syncReview)
const editable = computed(() => ['DRAFT', 'RETURNED'].includes(draft.value?.status))
const changedFields = computed(() => editable.value && form.value && (form.value.title !== draft.value.title
  || form.value.knowledgeClass !== (draft.value.knowledgeClass || '') || form.value.summary !== (draft.value.summary || '') || JSON.stringify(form.value.fields) !== JSON.stringify(draft.value.fields)
  || Object.entries(form.value.relations).some(([k,v]) => JSON.stringify(v) !== JSON.stringify(draft.value.relations[k] || []))))
const otherFields = computed(() => draft.value?.schema.fields.filter(field => field.id !== draft.value.schema.summaryKey
  && !['laiyuan','references'].includes(field.id)) || [])
const canSync = computed(() => ['APPROVED', 'SYNC_FAILED'].includes(draft.value?.status)
  || (draft.value?.status === 'SYNCING' && draft.value.syncLease < Date.now()))
const canReopen = computed(() => canSync.value && draft.value?.status !== 'SYNCING'
  && !draft.value?.remoteId && !draft.value?.syncPhase)
const canCompare = computed(() => props.session.cdssConfigured && !draft.value?.remoteId && !draft.value?.syncPhase
  && ['PENDING', 'APPROVED', 'SYNC_FAILED', 'REFERENCE_ONLY'].includes(draft.value?.status))
const needsReview = computed(() => !review.value?.approved && !draft.value?.remoteId && !draft.value?.syncPhase)
const approveReady = computed(() => !props.session.cdssConfigured || ['CREATE','UPDATE','SKIP'].includes(review.value?.operation))
const hasVersionTarget = computed(() => draft.value?.remoteId || draft.value?.updateTargetId || review.value?.target)
const reviewMessages = {
  CREATE: '未找到匹配知识。审核通过后可新增到 4.23。',
  UPDATE: '这条知识由采集系统新增。人工审核通过后，沿用原业务 ID 更新并保存版本记录。',
  SKIP: '匹配内容一致。审核通过后可登记跳过。',
  CHOOSE: '找到多个匹配记录。请选择对应的业务知识，核对来源和差异。',
  REFERENCE_ONLY: '这条 4.23 知识缺少采集系统的新增回执。可查看业务原文与差异，稿件已归入「业务原有 · 仅对照」。',
}

async function load(page = 1) {
  const seq = ++request
  loading.value = true
  try {
    const result = await list('/knowledge', { ...filters.value, subjectId: props.subjectId, page, size: pageSize.value })
    if (seq === request) { table.value = result; issue.value = '' }
  } catch (e) { if (seq === request) issue.value = e.message }
  finally { if (seq === request) loading.value = false }
}

function setDraft(data, resetNote) {
  if (draft.value?.materialId !== data.knowledge.materialId) siblingPage.value = 1
  if (draft.value?.id !== data.knowledge.id) {
    versions.value = null
    versionDetail.value = null
    reading.value = 'split'
  }
  if (draft.value?.id !== data.knowledge.id || draft.value?.revision !== data.knowledge.revision) advice.value = null
  content.value = data
  const knowledge = data.knowledge
  form.value = {
    title: knowledge.title, knowledgeClass:knowledge.knowledgeClass || '', summary: knowledge.summary || '', fields: { ...knowledge.fields },
    relations: Object.fromEntries(Object.entries(knowledge.relations).map(([key, values]) => [key, [...values]])),
  }
  if (resetNote) note.value = ''
}

watch(() => content.value?.material?.id, async (id, _, onCleanup) => {
  let active = true
  onCleanup(() => { active = false })
  sourceText.value = ''
  sourceError.value = ''
  ++evidenceRequest; evidenceQuery.value=''; evidenceMessage.value=''; hit.value=null
  if (!id || content.value.material.formatCode === 'PDF') return
  try {
    const data = await api('/materials/' + id)
    if (active) sourceText.value = data.material.rawText || data.material.sourceAbstract || '此格式请下载原件查看。'
  } catch (e) { if (active) sourceError.value = e.message }
})

async function preReview() {
  busy.value = true
  panelIssue.value = ''
  try {
    advice.value = await send('/knowledge/' + draft.value.id + '/pre-review', { revision: draft.value.revision })
  } catch (e) { panelIssue.value = e.message }
  finally { busy.value = false }
}

async function openDraft(id) {
  try {
    setDraft(await api('/knowledge/' + id), true)
    panelIssue.value = ''
  } catch (e) { issue.value = e.message }
}

async function compare(targetId, manual = false) {
  busy.value = true
  panelIssue.value = ''
  const { id, revision } = draft.value
  try {
    await send('/knowledge/' + id + '/sync-review', { revision, targetId, manual })
    setDraft(await api('/knowledge/' + id), false)
    await load(table.value.page)
  } catch (e) { panelIssue.value = e.message }
  finally { busy.value = false }
}

async function revise() {
  busy.value = true
  panelIssue.value = ''
  try {
    const result = await send('/knowledge/' + draft.value.id + '/revisions', { revision: draft.value.revision })
    await openDraft(result.id)
    await load(table.value.page)
    emit('notice', '已打开新版本草稿')
  } catch (e) { panelIssue.value = e.message }
  finally { busy.value = false }
}

async function loadVersions(page = 1) {
  busy.value = true
  panelIssue.value = ''
  try {
    versions.value = await list('/knowledge/' + draft.value.id + '/sync-versions', { page, size: 10 })
    versionDetail.value = null
  } catch (e) { panelIssue.value = e.message }
  finally { busy.value = false }
}

async function openVersion(version) {
  busy.value = true
  panelIssue.value = ''
  try {
    const detail = await api('/knowledge/' + draft.value.id + '/sync-versions/' + version.id)
    versionDetail.value = { ...detail, versionNo: version.versionNo }
  } catch (e) { panelIssue.value = e.message }
  finally { busy.value = false }
}

function snapshotText(snapshot) {
  let layout = snapshot.content
  if (typeof layout === 'string') {
    try { layout = JSON.parse(layout) } catch { /* Older records can store plain text. */ }
  }
  return JSON.stringify({ ...snapshot, content: layout }, null, 2)
}

async function persist() {
  const data = await send('/knowledge/' + draft.value.id, { ...form.value, revision:draft.value.revision }, 'PUT')
  return data.revision
}

async function save(submit) {
  if (submit && !form.value.summary.trim()) {
    panelIssue.value = '请填写摘要后再提交审核'
    return
  }
  busy.value = true
  panelIssue.value = ''
  const id = draft.value.id
  try {
    const revision = await persist()
    if (submit) await send('/knowledge/' + id + '/submit', { revision })
    await openDraft(id)
    await load(table.value.page)
    emit('notice', submit ? '知识已提交人工审核' : '草稿已保存')
  } catch (e) {
    panelIssue.value = e.message
    // Saving the fields can succeed before submitting. Retain the current version for retry.
    try {
      const current = await api('/knowledge/' + id)
      content.value = current
    } catch { /* Keep the existing form available. */ }
  } finally { busy.value = false }
}

async function act(action, approved) {
  const needsNote = action === 'reopen' || action === 'sync-reset' || (action === 'decision' && !approved)
  if (needsNote && !note.value.trim()) {
    panelIssue.value = '请填写审核意见或复核结果'
    return
  }
  busy.value = true
  panelIssue.value = ''
  const id = draft.value.id
  try {
    await send('/knowledge/' + id + '/' + action, {
      revision: draft.value.revision, approved, note: note.value, reviewId: review.value?.id,
    })
    await openDraft(id)
    await load(table.value.page)
    const message = action === 'sync' ? '已发起同步，请查看同步记录'
      : action === 'sync-reset' ? '复核结果已登记，可重新发起同步'
      : approved ? '人工审核通过' : '知识已退回'
    emit('notice', message)
  } catch (e) { panelIssue.value = e.message }
  finally { busy.value = false }
}

async function applyDetail(value) {
  filters.value = freshFilters()
  if (value === 'pending' || value === 'drafts') {
    filters.value.human = value === 'pending' ? 'PENDING' : 'EDITABLE'
    await load()
  } else if (['synced','ready','failed','skipped','reference'].includes(value)) {
    filters.value.sync = { synced:'SYNCED', ready:'READY', failed:'SYNC_FAILED', skipped:'SKIPPED', reference:'REFERENCE_ONLY' }[value]
    await load()
  } else if (value?.startsWith('material:')) {
    filters.value.materialId = value.slice(9)
    filters.value.status = ''
    await load()
  } else if (value) await Promise.all([load(),openDraft(value)])
  else await load()
}

onMounted(async () => {
  try {
    categories.value = Object.fromEntries((await api('/categories')).map(item => [item.code, item.name]))
  } catch (e) { issue.value = e.message }
  await Promise.all([loadChoices(),applyDetail(props.detail)])
  poll = setInterval(async () => {
    const id = draft.value?.id
    if (busy.value || draft.value?.status !== 'SYNCING') return
    try {
      const data = await api('/knowledge/' + id)
      if (draft.value?.id === id) setDraft(data, false)
      if (data.knowledge.status !== 'SYNCING') await load(table.value.page)
    } catch (e) { panelIssue.value = e.message }
  }, 2400)
})
onUnmounted(() => { clearInterval(poll); clearTimeout(choiceTimer) })
watch(() => props.subjectId, () => { filters.value.materialId = ''; load(); loadChoices() })
watch(() => props.detail, applyDetail)
</script>

<template>
  <section class="page-head">
    <h1>知识审核管理</h1>
  </section>
  <p v-if="issue" class="message error" role="alert">{{ issue }}</p>
  <section class="panel">
    <form class="filters" @submit.prevent="load()">
      <label class="search"><Search :size="16" /><input v-model.trim="filters.query" maxlength="400" aria-label="搜索知识" placeholder="搜索知识名称或来源原文" /></label>
      <select v-model="filters.submission" aria-label="提交状态"><option value="">--请选择提交状态--</option>
        <option value="SUBMITTED">已提交</option><option value="UNSUBMITTED">未提交</option></select>
      <select v-model="filters.human" aria-label="审核状态"><option value="">--请选择审核状态--</option>
        <option value="REVIEWED">已审核</option><option value="UNREVIEWED">未审核</option>
        <option value="EDITABLE">待整理草稿或退回稿</option>
        <option v-for="(name,key) in humanLabels" :key="key" :value="key">{{ name }}</option></select>
      <select v-model="filters.publication" aria-label="发布状态"><option value="">--请选择发布状态--</option>
        <option value="PUBLISHED">已发布</option><option value="UNPUBLISHED">未发布</option><option value="UNKNOWN">待核验</option></select>
      <button class="button primary" :disabled="loading">查询</button>
      <button class="button secondary" type="button" @click="filters=freshFilters(); materialQuery=''; load()">重置</button>
      <details class="extra-filters" :open="!!(filters.sync || filters.materialId)"><summary>更多筛选</summary><div class="actions">
      <input v-if="choicesTotal > 50 || materialQuery" v-model.trim="materialQuery" aria-label="搜索原文选项" placeholder="搜索原文选项" />
      <select v-model="filters.materialId" aria-label="来源原文"><option value="">全部来源原文</option>
        <option v-if="filters.materialId && !materialChoices.some(m => m.id === filters.materialId)" :value="filters.materialId">当前来源原文</option>
        <option v-for="m in materialChoices" :key="m.id" :value="m.id">{{ m.title }}</option></select>
      <select v-model="filters.sync" aria-label="同步状态"><option value="">全部同步状态</option>
        <option v-for="(name,key) in syncLabels" :key="key" :value="key">{{ name }}</option></select>
      </div></details>
    </form>
    <div v-if="table.rows.length" class="table-wrap">
      <table><thead><tr><th>序号</th><th>知识名称 / 类别</th><th>来源原文</th><th>AI预审核</th><th>提交状态</th><th>审核状态</th><th>发布状态</th><th>同步状态</th><th>操作</th></tr></thead>
        <tbody><tr v-for="(row,i) in table.rows" :key="row.id">
          <td>{{ (table.page-1)*table.size+i+1 }}</td><td><button class="link record-title" @click="openDraft(row.id)">{{ row.title }}</button><small class="subline">{{ categories[row.typeCode] || row.typeCode }}</small></td>
          <td><button class="link record-title" @click="emit('navigate','materials',row.materialId)">{{ row.materialTitle }}</button></td>
          <td><button v-if="row.aiReviewedAt" class="link" @click="openDraft(row.id)">查看意见</button><span v-else>未预审</span></td>
          <td>{{ row.status === 'DRAFT' ? '未提交' : '已提交' }}</td>
          <td><StateBadge :state="humanState(row)" :labels="humanLabels" /><small v-if="row.reviewer" class="subline">{{ row.reviewer }} · {{ date(row.reviewedAt) }}</small></td>
          <td :title="row.remoteId ? '4.23 最近回读的发布状态' : ''">{{ !row.remoteId ? '未发布' : row.businessRelease==='1' ? '已发布' : row.businessRelease==='2' ? '已撤回' : row.businessRelease==='0' ? '未发布' : '待核验' }}</td>
          <td><StateBadge :state="syncState(row)" :labels="outcomeLabels" /></td>
          <td><button class="link" @click="openDraft(row.id)">查看知识</button></td>
        </tr></tbody></table></div>
    <EmptyState v-else :title="loading ? '正在查询知识' : '暂无匹配知识'" />
    <Pagination v-bind="table" resizable @change="load" @resize="pageSize = $event; load()" />
  </section>

  <Drawer v-if="draft" :title="draft.title" subtitle="知识审核管理" workbench :busy="busy" @close="content = null">
    <div class="reader-bar"><div class="tab-buttons" aria-label="阅读方式">
      <button v-for="(label,key) in {split:'对照查看', knowledge:'只看知识', source:'只看原文'}" :key="key" :aria-pressed="reading === key"
        @click="reading = key">{{ label }}</button></div>
      <div v-if="siblings?.total > 1" class="sibling-picker">
        <label class="filter-field">同一原文的知识<select :value="draft.id" aria-label="切换知识" :disabled="busy || changedFields" @change="openDraft($event.target.value)">
          <option v-if="!siblings.rows.some(k => k.id === draft.id)" :value="draft.id">{{ draft.title }}</option>
          <option v-for="k in siblings.rows" :key="k.id" :value="k.id">{{ categories[k.typeCode] }} · {{ k.title }}</option></select></label>
        <template v-if="siblings.total > siblings.size"><button class="link" :disabled="siblingPage <= 1" @click="siblingPage--">上一页</button>
          <button class="link" :disabled="siblingPage * siblings.size >= siblings.total" @click="siblingPage++">下一页</button></template>
        <small v-if="changedFields">保存草稿后可切换</small>
      </div></div>
    <div class="review-columns" :class="{single:reading !== 'split'}">
      <section v-show="reading !== 'knowledge'" class="source-column">
        <div class="reader-title"><h3>来源原文</h3><a v-if="content.material.formatCode !== 'PDF'" :href="original(content.material.id)" target="_blank" rel="noopener noreferrer">打开原件 ↗</a></div>
        <p class="original-title">{{ content.material.title }}</p>
        <form class="evidence-search" @submit.prevent="locateEvidence()"><input type="search" v-model="evidenceQuery" maxlength="300" placeholder="查找原文片段" aria-label="原文片段"/>
          <button class="button secondary" :disabled="!evidenceQuery.trim()">定位原文</button></form><small role="status">{{ evidenceMessage }}</small>
        <PdfReader v-if="content.material.formatCode === 'PDF'" ref="pdfReader" :material-id="content.material.id" :title="content.material.title" />
        <p v-else-if="sourceError" class="message error">{{ sourceError }}</p><pre v-else class="original-text"><template v-if="hit">{{ sourceText.slice(0,hit.at) }}<mark ref="marked">{{ sourceText.slice(hit.at,hit.end) }}</mark>{{ sourceText.slice(hit.end) }}</template><template v-else>{{ sourceText || '正在读取原文…' }}</template></pre>
      </section>
      <section v-show="reading !== 'source'" class="knowledge-column">
    <div class="toolbar">
      <span v-if="draft.syncPhase === 'SKIPPED'" class="message success">内容一致，已跳过</span>
      <StateBadge v-else :state="draft.status" :labels="knowledgeStates" />
      <strong>{{ draft.schema.category.name }}<span v-if="draft.knowledgeClass"> › {{ classLabel || draft.knowledgeClass }}</span></strong>
    </div>
    <BusinessSyncReceipt v-if="draft.remoteUrl && draft.status === 'SYNCED'" :key="draft.id" :draft="draft" />
    <details class="review-metadata"><summary>资料信息</summary>
    <dl class="metadata">
      <div><dt>知识目标</dt><dd>{{ draft.schema.category.name }}<span v-if="draft.knowledgeClass"> · {{ classLabel ? classLabel + '（' + draft.knowledgeClass + '）' : draft.knowledgeClass }}</span></dd></div>
      <div><dt>模板</dt><dd>{{ draft.schema.origin }} · 规则版本 {{ draft.schema.profileRevision || 0 }}</dd></div>
      <div><dt>来源原文</dt><dd>{{ content.material.title }}</dd></div>
      <div v-if="draft.modelName"><dt>解析模型</dt><dd><Sparkles :size="14" /> {{ draft.modelName }}</dd></div>
      <div v-if="draft.remoteId"><dt>业务主键</dt><dd class="hash">{{ draft.remoteId }}</dd></div>
      <div v-else-if="draft.updateTargetId"><dt>版本更新目标</dt><dd class="hash">{{ draft.updateTargetId }}</dd></div>
      <div v-if="draft.remoteUrl"><dt>4.23 预览</dt><dd><a :href="draft.remoteUrl" target="_blank" rel="noopener noreferrer">打开业务知识 <ExternalLink :size="14" /></a></dd></div>
    </dl>
    <button class="link" @click="emit('navigate','materials',content.material.id)"><FileText :size="16" /> 查看原文详情</button></details>
    <p v-if="draft.syncError" class="message error" role="alert">{{ draft.syncError }}</p>
    <p v-if="canSync && !session.cdssSync" class="message">服务端尚未启用 4.23 同步。</p>
    <form id="knowledge-form" class="form-section form-grid" @submit.prevent="save(false)">
      <h3>采集来源</h3>
      <dl class="metadata provenance-block">
        <div><dt>来源站点</dt><dd>{{ content.material.sourceName || '未登记' }}</dd></div>
        <div><dt>采集原件</dt><dd><a :href="original(content.material.id)" target="_blank" rel="noopener">{{ content.material.fileName }}</a></dd></div>
        <div v-if="content.material.sourceUrl"><dt>原始地址</dt><dd><a :href="content.material.sourceUrl" target="_blank" rel="noopener noreferrer">{{ content.material.sourceUrl }}</a></dd></div>
        <div v-if="content.material.fetchedUrl && content.material.fetchedUrl!==content.material.sourceUrl"><dt>实际获取地址</dt><dd><a :href="content.material.fetchedUrl" target="_blank" rel="noopener noreferrer">{{ content.material.fetchedUrl }}</a></dd></div>
      </dl>
      <h3>知识出处</h3>
      <p class="reading-text">{{ content.material.citation }}</p>
      <label v-if="editable || form.fields.laiyuan">出处补充<textarea v-model="form.fields.laiyuan" rows="2" maxlength="12000" :readonly="!editable"></textarea></label>
      <label v-if="content.material.formatCode!=='PDF' && (editable || form.fields.references)">参考文献<textarea v-model="form.fields.references" rows="2" maxlength="12000" :readonly="!editable"></textarea></label>
      <h3>知识正文</h3>
      <label v-if="editable && !draft.updateTargetId && classOptions.length">目标知识类别<select v-model="form.knowledgeClass">
        <option value="" disabled>请选择业务类别</option><option v-for="c in classOptions" :key="c.code" :value="c.code">{{ c.name }} · {{ c.code }}</option>
      </select></label>
      <label>标题<input v-model="form.title" required maxlength="100" :readonly="!editable" /></label>
      <label>摘要<textarea v-model="form.summary" rows="7" maxlength="12000" :readonly="!editable"></textarea></label>
      <p v-if="content.material.formatCode === 'PDF'" class="muted">同步时保留摘要，并绑定 PDF 原件。</p>
      <details v-else-if="otherFields.some(field => editable || draft.fields[field.id])" class="raw-text">
        <summary>模板字段</summary>
        <div class="form-grid inset">
          <label v-for="field in otherFields.filter(field => editable || draft.fields[field.id])" :key="field.id">
            {{ field.label }}{{ draft.schema.rules?.[field.id]?.required ? '（必填）' : '' }}
            <BusinessProperty v-if="field.dictionary" :dictionary="field.dictName || draft.schema.layout.properties.find(p=>p.id===field.id)?.dictName" :label="field.label" v-model="form.fields[field.id]" :editable="editable" />
            <input v-else-if="field.property" v-model="form.fields[field.id]" maxlength="12000" :readonly="!editable" />
            <textarea v-else v-model="form.fields[field.id]" rows="3" maxlength="12000" :readonly="!editable"></textarea>
          </label>
        </div>
      </details>
      <details v-if="draft.schema.category.relations.length" class="raw-text">
        <summary>关联业务字典</summary>
        <div class="form-grid inset">
          <BusinessRelation v-for="key in draft.schema.category.relations" :key="draft.id+'/'+key" :type="draft.typeCode" :relation="key"
            :label="relations[key] || key" v-model="form.relations[key]" :editable="editable" />
        </div>
      </details>
    </form>
    <section v-if="canCompare || review" class="form-section form-grid" aria-label="同步前查重与差异">
      <div class="toolbar">
        <h3>同步前查重与差异</h3>
        <button v-if="canCompare" class="button secondary" :disabled="busy" @click="compare()">
          <Search :size="16" /> {{ review ? '重新查重' : '检查重复与差异' }}
        </button>
      </div>
      <p v-if="canCompare && !review" class="muted">审核前核对 4.23 现有知识。</p>
      <BusinessTargetPicker v-if="canCompare && !draft.updateTargetId" :key="draft.id" :draft-id="draft.id" :busy="busy" @select="id => compare(id,true)" />
      <template v-if="review">
        <p v-if="draft.syncPhase !== 'SKIPPED' && draft.status !== 'SYNCED'">{{ review.operation==='REFERENCE_ONLY' && !review.target?.knowledgeClass ? '4.23 目标缺少有效类别，已归入只读对照。' : reviewMessages[review.operation] }}</p>
        <p v-if="review.mayResetBusinessReview && draft.status !== 'SYNCED'" class="message">
          4.23 可能按业务权限重置原记录的审核或发布状态，并停用该知识。
        </p>
        <p v-if="review.doi" class="muted">DOI {{ review.doi }}</p>
        <ul v-if="review.operation === 'CHOOSE'" class="simple-list">
          <li v-for="candidate in review.candidates" :key="candidate.id">
            <span><a :href="candidate.url" target="_blank" rel="noopener noreferrer">{{ candidate.title }}</a>
              <small class="subline">{{ candidate.collectorCreated ? '采集系统新增' : '业务原有 · 仅对照' }} · {{ candidate.basis }} · {{ candidate.knowledgeClass || '未分子类' }} · {{ candidate.id }}</small></span>
            <button class="button secondary" :disabled="busy || !canCompare" @click="compare(candidate.id)">选择并对比</button>
          </li>
        </ul>
        <p v-if="review.target" class="muted">{{ review.target.collectorCreated ? '采集系统新增' : '业务原有' }} · {{ review.target.basis }} ·
          <a :href="review.target.url" target="_blank" rel="noopener noreferrer">{{ review.target.title }}</a>
          · {{ review.target.id }}
        </p>
        <KnowledgeDifferences :differences="review.differences" />
      </template>
    </section>
    <section v-if="hasVersionTarget" class="form-section form-grid">
      <div class="toolbar">
        <h3>版本记录</h3>
        <div class="actions">
          <button class="button secondary" :disabled="busy" @click="loadVersions()">查看版本记录</button>
          <button v-if="content.canRevise" class="button secondary" :disabled="busy" @click="revise">发起新版本</button>
        </div>
      </div>
      <template v-if="versions">
        <ul v-if="versions.rows.length" class="simple-list">
          <li v-for="version in versions.rows" :key="version.id">
            <button class="link" :disabled="busy" @click="openVersion(version)">版本 {{ version.versionNo }}</button>
            <span>{{ version.operation === 'CREATE' ? '新增' : '更新' }} · 审核人 {{ version.reviewer }} · {{ date(version.syncedAt, true) }}</span>
          </li>
        </ul>
        <p v-else class="muted">暂无采集系统记录的版本。</p>
        <Pagination v-bind="versions" @change="loadVersions" />
      </template>
      <section v-if="versionDetail" class="form-grid inset">
        <h4>版本 {{ versionDetail.versionNo }}</h4>
        <KnowledgeDifferences :differences="versionDetail.differences" />
        <details v-if="versionDetail.before" class="raw-text"><summary>更新前快照</summary><pre>{{ snapshotText(versionDetail.before) }}</pre></details>
        <details v-if="versionDetail.after" class="raw-text"><summary>同步后快照</summary><pre>{{ snapshotText(versionDetail.after) }}</pre></details>
      </section>
    </section>
    <section v-if="draft.status === 'PENDING' || latestAdvice" class="form-section form-grid">
      <div class="toolbar">
        <h3>AI 预审核</h3>
        <button v-if="draft.status === 'PENDING'" class="button secondary" :disabled="busy" @click="preReview"><Sparkles :size="16" /> 生成核对提示</button>
      </div>
      <ul v-if="advice?.findings.length" class="simple-list">
        <li v-for="(finding, index) in advice.findings" :key="index">{{ finding }} <button v-if="advice.quotes?.[index]" class="link" @click="locateEvidence(advice.quotes[index])">定位依据</button></li>
      </ul>
      <p v-else-if="advice" class="muted">模型未返回核对提示。</p>
      <p v-else-if="latestAdvice" class="reading-text">{{ latestAdvice.note }}</p>
    </section>
    <div v-if="draft.status === 'PENDING' || canReopen || draft.syncPhase === 'POSTING'" class="form-section form-grid">
      <h3>{{ draft.syncPhase === 'POSTING' ? '业务记录复核' : '人工审核' }}</h3>
      <label>意见<textarea v-model.trim="note" rows="3" maxlength="1000" placeholder="填写审核意见、退回原因或复核结果"></textarea></label>
      <p v-if="draft.syncPhase === 'POSTING'" class="muted">若确认 4.23 未生成记录，可登记复核结果后重新发起。</p>
    </div>
    <p v-if="panelIssue" class="message error" role="alert">{{ panelIssue }}</p>
    <Events :events="content.events" />
      </section>
    </div>
    <template v-if="editable || draft.status === 'PENDING' || canSync" #footer>
      <template v-if="editable">
        <button class="button secondary" form="knowledge-form" :disabled="busy">保存草稿</button>
        <button class="button primary" :disabled="busy || !form.title.trim() || !form.summary.trim()" @click="save(true)"><Send :size="16" /> 保存并提交审核</button>
      </template>
      <template v-else-if="draft.status === 'PENDING'">
        <button class="button secondary" :disabled="busy" @click="act('decision', false)">退回修改</button>
        <button class="button primary" :disabled="busy || !approveReady" @click="act('decision', true)">人工审核通过</button>
      </template>
      <template v-else>
        <button v-if="canReopen" class="button secondary" :disabled="busy" @click="act('reopen')">退回修改</button>
        <button v-if="draft.status === 'SYNC_FAILED' && draft.syncPhase === 'POSTING'" class="button secondary" :disabled="busy" @click="act('sync-reset')">登记复核结果</button>
        <button class="button primary" :disabled="busy || !session.cdssSync || needsReview" @click="act('sync')">
          {{ draft.status !== 'APPROVED' ? '重试同步' : review?.operation === 'SKIP' ? '登记跳过' : '同步至 4.23' }}
        </button>
      </template>
    </template>
  </Drawer>
</template>
