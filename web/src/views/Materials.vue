<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { Download, ExternalLink, FileText, Search, Sparkles, Upload } from '@lucide/vue'
import { api, list, original, send } from '../api'
import { date, dateRange, materialStates, modes, selection, size } from '../labels'
import PdfReader from '../components/PdfReader.vue'
import DirectoryTree from '../components/DirectoryTree.vue'
import UploadOriginal from '../components/UploadOriginal.vue'
import Drawer from '../components/Drawer.vue'
import EmptyState from '../components/EmptyState.vue'
import Events from '../components/Events.vue'
import Pagination from '../components/Pagination.vue'
import StateBadge from '../components/StateBadge.vue'

const props = defineProps({ session: Object, detail: String, parseOnly: Boolean, directory: Array, subjectId: String })
const emit = defineEmits(['navigate', 'notice', 'scope', 'directory-changed'])
const table = ref({ rows: [], total: 0, page: 1, size: 10 })
const freshFilters = () => ({ query: '', status: '', format: '', classId: '', originalsOnly: false,
  domainId: '', materialTypeId: '', acquireMode: '', parsed: '', from: '', until: '' })
const filters = ref(freshFilters())
const pageSize = ref(10)
const parseFocus = ref(false)
let request = 0
const groups = { SUBJECT: '学科病种', DOMAIN: '知识类别', MATERIAL: '资料类型' }
const formats = ['PDF', 'HTML', 'DOCX', 'TXT', 'CSV', 'XLS', 'XLSX', 'METADATA']
const classifications = computed(() => props.directory || [])
const groupNodes = key => classifications.value.filter(n => n.groupCode === key)
const namesOf = (row, group) => groupNodes(group).filter(n => row.classIds.includes(n.id)).map(n => n.name).join('、') || '—'
const selectedRows = ref([])
const classificationForm = ref(null)
const classificationError = ref('')
const categories = ref([])
const classes = ref([])
const chosen = ref({ typeCode: '', knowledgeClass: '' })
const queue = ref([])
const canAdd = computed(() => category.value && (!category.value.classRequired || chosen.value.knowledgeClass))
function requestId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 15) | 64
  bytes[8] = (bytes[8] & 63) | 128
  const hex = Array.from(bytes,b => b.toString(16).padStart(2,'0')).join('')
  return [hex.slice(0,8),hex.slice(8,12),hex.slice(12,16),hex.slice(16,20),hex.slice(20)].join('-')
}
function addTemplate() {
  if (!canAdd.value) return
  const { typeCode, knowledgeClass } = chosen.value
  if (!queue.value.some(row => row.typeCode === typeCode && row.knowledgeClass === knowledgeClass)) {
    const sub = classes.value.find(c => c.code === knowledgeClass)?.name
    queue.value.push({ typeCode, knowledgeClass, label:[category.value.name,sub].filter(Boolean).join(' / '),
      requestId:requestId(), id:'', error:'', useAi:null })
  }
  chosen.value = { typeCode:'', knowledgeClass:'' }
  classes.value = []
}
const item = ref(null)
const uploadForm = ref(null)
const notes = ref('')
const summary = ref('')
const preview = ref(false)
const showExtract = ref(false)
const error = ref('')
const panelError = ref('')
const loading = ref(false)
const busy = ref(false)
const parseMode = ref('')
const category = computed(() => categories.value.find(entry => entry.code === chosen.value.typeCode))
const currentClassOptions = computed(() => classifications.value.filter(node => node.groupCode === classificationForm.value?.groupCode
  && node.enabled && (!node.parentId || classifications.value.some(parent => parent.id === node.parentId && parent.enabled))))

function classPath(node) {
  const parent = classifications.value.find(entry => entry.id === node.parentId)
  return parent ? parent.name + ' / ' + node.name : node.name
}

function editClassifications(materialIds, current = []) {
  classificationForm.value = { materialIds, groupCode: 'SUBJECT', classIds: [], current }
  classificationError.value = ''
  chooseClassGroup()
}

function chooseClassGroup() {
  const form = classificationForm.value
  form.classIds = form.current.filter(id => currentClassOptions.value.some(node => node.id === id))
}

async function saveClassifications() {
  busy.value = true
  classificationError.value = ''
  try {
    const { groupCode, materialIds, classIds } = classificationForm.value
    await send('/materials/classifications', { groupCode, materialIds, classIds }, 'PUT')
    classificationForm.value = null
    if (item.value && materialIds.includes(item.value.material.id)) await openMaterial(item.value.material.id)
    await load(table.value.page)
    emit('directory-changed')
    emit('notice', '资料分类已保存')
  } catch (e) { classificationError.value = e.message }
  finally { busy.value = false }
}

async function load(page = 1) {
  const seq = ++request
  loading.value = true
  try {
    const { from, until, ...rest } = filters.value
    const result = await list('/materials', { ...rest, ...dateRange(from,until), subjectId: props.subjectId,
      originalsOnly: props.parseOnly || rest.originalsOnly, page, size: pageSize.value })
    if (seq === request) { table.value = result; selectedRows.value = []; error.value = '' }
  } catch (e) { if (seq === request) error.value = e.message }
  finally { if (seq === request) loading.value = false }
}

async function openMaterial(id, purpose = '') {
  try {
    item.value = await api('/materials/' + id)
    summary.value = item.value.material.sourceAbstract || ''
    notes.value = ''
    queue.value = []
    parseFocus.value = purpose === 'parse' && item.value.material.status === 'APPROVED'
    preview.value = purpose === 'preview' && item.value.material.formatCode === 'PDF'
    showExtract.value = purpose === 'preview' && !preview.value
    chosen.value = { typeCode: '', knowledgeClass: '' }
    classes.value = []
    panelError.value = ''
  } catch (e) { error.value = e.message }
}

async function chooseType() {
  const code = chosen.value.typeCode
  chosen.value.knowledgeClass = ''
  classes.value = []
  panelError.value = ''
  if (!code) return
  try {
    const options = await api('/categories/' + code + '/classes')
    if (chosen.value.typeCode === code) classes.value = options
  } catch (e) { panelError.value = e.message }
}

function openUpload(taskId = '') { uploadForm.value = { taskId } }

async function uploaded(result) {
  uploadForm.value = null
  await load()
  if (result.receipts.length === 1) await openMaterial(result.receipts[0].materialId)
}

async function review(approved) {
  if (!approved && !notes.value.trim()) {
    panelError.value = '退回时请填写审核意见'
    return
  }
  busy.value = true
  panelError.value = ''
  try {
    const { id, revision } = item.value.material
    await send('/materials/' + id + '/decision', {
      approved, revision, note: notes.value, sourceAbstract: summary.value,
    })
    await openMaterial(id, props.parseOnly && approved ? 'parse' : '')
    await load(table.value.page)
    emit('notice', approved ? '原文审核通过，可以解析知识' : '原文已退回')
  } catch (e) { panelError.value = e.message }
  finally { busy.value = false }
}

async function draft(useAi) {
  busy.value = true
  parseMode.value = useAi ? 'AI' : 'manual'
  panelError.value = ''
  try {
    addTemplate()
    if (!queue.value.length) return
    const materialId = item.value.material.id
    for (const row of queue.value.filter(r => !r.id)) {
      row.error = ''
      row.useAi ??= useAi
      try {
        const result = await send('/materials/' + materialId + '/drafts', {
          typeCode:row.typeCode, knowledgeClass:row.knowledgeClass, useAi:row.useAi, requestId:row.requestId,
        })
        row.id = result.id
      } catch (e) { row.error = e.message }
    }
    if (queue.value.some(r => !r.id)) { panelError.value = '部分模板尚未完成，可核对错误后重试。'; await load(table.value.page); return }
    emit('notice', '已生成 ' + queue.value.length + ' 条知识草稿')
    emit('navigate', 'knowledge', queue.value.length === 1 ? queue.value[0].id : 'material:' + materialId)
  } catch (e) { panelError.value = e.message }
  finally {
    busy.value = false
    parseMode.value = ''
  }
}

async function applyDetail(detail) {
  if (detail === 'pending' || detail === 'approved') {
    filters.value.status = detail.toUpperCase()
    await load()
  } else if (detail === 'unparsed') {
    filters.value.parsed = 'false'
    await load()
  } else if (detail?.startsWith('upload') && props.session.admin) {
    openUpload(detail.split(':')[1])
  } else if (detail?.startsWith('class:')) {
    filters.value.classId = detail.slice(6)
    await load()
  } else if (detail === 'originals') {
    filters.value.originalsOnly = true
    await load()
  } else if (detail) await openMaterial(detail)
}

onMounted(async () => {
  await Promise.all([load(), api('/categories').then(value => { categories.value = value }).catch(e => { error.value = e.message })])
  await applyDetail(props.detail)
})
watch(() => props.detail, async detail => {
  item.value = null
  uploadForm.value = null
  classificationForm.value = null
  filters.value = freshFilters()
  if (detail) await applyDetail(detail)
  else await load()
})
watch(() => props.subjectId, () => { filters.value.classId = ''; load() })
</script>

<template>
  <section class="page-head">
    <h1>{{ parseOnly ? '静态知识解析' : '文献原文管理' }}</h1>
    <div v-if="!parseOnly" class="actions"><button class="button secondary" :disabled="!selectedRows.length" @click="editClassifications(selectedRows)">批量分类</button>
      <button v-if="session.admin" class="button primary" @click="openUpload()"><Upload :size="16" /> 手工上传</button></div>
  </section>
  <p v-if="error" class="message error" role="alert">{{ error }}</p>
  <div :class="{ 'directory-layout': !parseOnly }">
    <DirectoryTree v-if="!parseOnly" title="学科与疾病" all-label="全部原文" :nodes="groupNodes('SUBJECT')" :selected="subjectId" unassigned @select="emit('scope',$event)">
      <button class="link" @click="emit('navigate','classifications')">目录维护</button>
    </DirectoryTree>
    <section class="panel">
      <form class="filters" @submit.prevent="load()">
        <label class="search"><Search :size="15" /><input v-model.trim="filters.query" maxlength="400" aria-label="检索原文" placeholder="搜索资料名称、来源或 DOI" /></label>
        <select v-model="filters.materialTypeId" aria-label="资料类型"><option value="">全部资料类型</option><option v-for="n in groupNodes('MATERIAL')" :key="n.id" :value="n.id">{{ n.name }}</option></select>
        <select v-model="filters.domainId" aria-label="知识类别"><option value="">全部知识类别</option><option v-for="n in groupNodes('DOMAIN')" :key="n.id" :value="n.id">{{ classPath(n) }}</option></select>
        <select v-if="parseOnly" v-model="filters.parsed" aria-label="解析状态"><option value="">全部解析状态</option><option value="false">未解析</option><option value="true">已解析</option></select>
        <template v-else><select v-model="filters.format" aria-label="文件格式"><option value="">全部文件格式</option><option v-for="f in formats" :key="f">{{ f }}</option></select>
          <select v-model="filters.acquireMode" aria-label="获取方式"><option value="">全部获取方式</option><option v-for="(name,k) in modes" :key="k" :value="k">{{ name }}</option></select>
          <label class="filter-field">采集时间<input v-model="filters.from" aria-label="采集开始日期" type="date" :max="filters.until" /></label>
          <span>至</span><input v-model="filters.until" aria-label="采集结束日期" type="date" :min="filters.from" />
        </template>
        <select v-model="filters.status" aria-label="原文审核状态"><option value="">全部原文审核状态</option><option v-for="s in selection(materialStates)" :key="s.value" :value="s.value">{{ s.label }}</option></select>
        <label v-if="!parseOnly" class="check"><input v-model="filters.originalsOnly" type="checkbox" /> 仅含原件</label>
        <button class="button primary" :disabled="loading">查询</button><button type="button" class="button secondary" @click="filters = freshFilters(); load()">重置</button>
        <button v-if="filters.classId" type="button" class="link" @click="filters.classId = ''; load()">清除分类筛选</button>
      </form>
      <div v-if="table.rows.length" class="table-wrap">
        <table><thead><tr><th v-if="!parseOnly"><input type="checkbox" aria-label="选择当前页资料" :checked="selectedRows.length === table.rows.length"
          :indeterminate="selectedRows.length > 0 && selectedRows.length < table.rows.length"
          @change="selectedRows = $event.target.checked ? table.rows.map(r => r.id) : []" /></th>
          <th>序号</th><th>{{ parseOnly ? '原文资料' : '资料名称' }}</th>
          <template v-if="parseOnly"><th>解析类别</th><th>解析状态</th><th>生成知识</th><th>创建时间</th><th>最近生成</th></template>
          <template v-else><th>资料类型</th><th>知识类别</th><th>格式</th><th>获取方式</th><th>采集时间</th></template><th>操作</th></tr></thead>
          <tbody><tr v-for="(row,i) in table.rows" :key="row.id">
            <td v-if="!parseOnly"><input type="checkbox" v-model="selectedRows" :value="row.id" :aria-label="'选择 ' + row.title" /></td>
            <td>{{ (table.page-1)*table.size+i+1 }}</td>
            <td><button class="link record-title" @click="openMaterial(row.id)">{{ row.title }}</button>
              <small class="subline">{{ parseOnly ? row.formatCode : (row.sourceName || '') }}</small></td>
            <template v-if="parseOnly"><td>{{ namesOf(row,'DOMAIN') }}</td><td>{{ row.knowledgeCount ? '已解析' : '未解析' }}
              <small v-if="row.status !== 'APPROVED'" class="subline">{{ materialStates[row.status] }}原文</small></td>
              <td><button v-if="row.knowledgeCount" class="link" @click="emit('navigate','knowledge','material:' + row.id)">{{ row.knowledgeCount }} 条</button><span v-else>0 条</span></td>
              <td>{{ date(row.capturedAt) }}</td><td>{{ date(row.lastParsedAt) }}</td></template>
            <template v-else><td>{{ namesOf(row,'MATERIAL') }}</td><td>{{ namesOf(row,'DOMAIN') }}</td>
              <td>{{ row.formatCode === 'METADATA' ? '来源记录' : row.formatCode }}</td><td>{{ modes[row.acquireMode] || '—' }}</td><td>{{ date(row.capturedAt) }}</td></template>
            <td><div class="table-actions">
              <template v-if="!parseOnly"><button class="link" :aria-label="'查看原文：'+row.title" title="查看原文" @click="openMaterial(row.id,'preview')"><FileText :size="16" /></button>
                <a :href="original(row.id)" :aria-label="'下载原件：'+row.title" title="下载原件"><Download :size="16" /></a></template>
              <button v-if="row.status !== 'APPROVED'" class="link" @click="openMaterial(row.id)">审核原文</button>
              <button v-else class="link" @click="openMaterial(row.id,'parse')">AI解析</button>
              <button v-if="!parseOnly && row.knowledgeCount" class="link" @click="emit('navigate','knowledge','material:' + row.id)">解析结果</button>
            </div></td>
          </tr></tbody></table></div>
      <EmptyState v-else :title="loading ? '正在查询原文' : '暂无匹配原文'" />
      <Pagination v-bind="table" resizable @change="load" @resize="pageSize = $event; load()" />
    </section>
  </div>
  <UploadOriginal v-if="uploadForm" :task-id="uploadForm.taskId" @close="uploadForm = null" @notice="emit('notice',$event)" @complete="uploaded" />

  <Drawer v-if="item" :title="item.material.title" :subtitle="parseFocus ? 'AI解析 · 选择知识模板' : '原文详情'" wide :busy="busy" @close="item = null">
    <div class="toolbar">
      <StateBadge :state="item.material.status" :labels="materialStates" />
      <div class="actions">
        <a v-if="item.material.sourceUrl" class="button secondary" :href="item.material.sourceUrl" target="_blank" rel="noopener noreferrer"><ExternalLink :size="16" /> 原始来源</a>
        <button v-if="item.material.formatCode === 'PDF'" class="button secondary" @click="preview = !preview"><FileText :size="16" /> {{ preview ? '收起 PDF' : '预览 PDF' }}</button>
        <a class="button secondary" :href="original(item.material.id)"><Download :size="16" />
          {{ item.material.formatCode === 'METADATA' ? '下载来源记录' : '下载原件' }}</a>
      </div>
    </div>
    <PdfReader v-if="preview" :material-id="item.material.id" :title="item.material.title" />
    <dl v-if="!parseFocus" class="metadata">
      <div><dt>原件</dt><dd>{{ item.material.fileName }} · {{ size(item.material.sizeBytes) }}</dd></div>
      <div><dt>来源</dt><dd>{{ item.material.sourceName || '—' }}</dd></div>
      <div><dt>作者 / 机构</dt><dd>{{ [item.material.authors, item.material.publisher].filter(Boolean).join(' · ') || '—' }}</dd></div>
      <div><dt>发表日期</dt><dd>{{ item.material.published || '—' }}</dd></div>
      <div v-if="item.material.externalRef"><dt>DOI / 索引号</dt><dd>{{ item.material.externalRef }}</dd></div>
      <div><dt>采集时间</dt><dd>{{ date(item.material.capturedAt, true) }}</dd></div>
      <div v-if="item.material.fetchedUrl && item.material.fetchedUrl !== item.material.sourceUrl">
        <dt>实际获取地址</dt><dd><a :href="item.material.fetchedUrl" target="_blank" rel="noopener noreferrer">{{ item.material.fetchedUrl }}</a></dd></div>
      <div><dt>SHA-256</dt><dd class="hash">{{ item.material.sha256 }}</dd></div>
      <div><dt>资料分类</dt><dd>{{ classifications.filter(node => item.classificationIds.includes(node.id)).map(classPath).join('、') || '未分类' }}
        <button class="link" @click="editClassifications([item.material.id], item.classificationIds)">修改分类</button></dd></div>
    </dl>
    <section v-if="!parseFocus" class="form-section">
      <h3>原文摘要</h3>
      <p v-if="item.material.status === 'APPROVED'" class="reading-text">{{ item.material.sourceAbstract || '暂无原文摘要' }}</p>
      <textarea v-else v-model="summary" aria-label="原文摘要" rows="5" maxlength="12000" placeholder="可根据已核验的原件补充摘要"></textarea>
      <details v-if="item.material.rawText" class="raw-text" :open="showExtract"><summary>查看提取文本</summary><pre>{{ item.material.rawText }}</pre></details>
    </section>
    <section v-if="item.knowledge.total && !parseFocus" class="form-section">
      <h3>已生成的知识</h3>
      <ul class="simple-list"><li v-for="entry in item.knowledge.rows" :key="entry.id">
        <button class="link" @click="emit('navigate', 'knowledge', entry.id)">{{ entry.title }}</button><span>{{ date(entry.updatedAt) }}</span>
      </li></ul>
    </section>
    <section v-if="item.material.status !== 'APPROVED'" class="form-section form-grid">
      <h3>原文审核</h3>
      <label>审核意见<textarea v-model.trim="notes" rows="3" maxlength="1000" placeholder="退回时需填写原因"></textarea></label>
    </section>
    <section v-else class="form-section form-grid">
      <h3><Sparkles :size="18" /> 解析模板</h3>
      <div class="two-fields">
        <label>知识类型<select v-model="chosen.typeCode" :disabled="busy" @change="chooseType">
          <option value="">选择 4.23 知识类型</option><option v-for="entry in categories" :key="entry.code" :value="entry.code">{{ entry.name }}</option>
        </select></label>
        <label v-if="classes.length || category?.classRequired">知识子类<select v-model="chosen.knowledgeClass" :disabled="busy">
          <option value="">选择知识子类</option><option v-for="entry in classes" :key="entry.code" :value="entry.code">{{ entry.name }}（{{ entry.code }}）</option>
        </select></label>
      </div>
      <ul v-if="queue.length" class="simple-list"><li v-for="row in queue" :key="row.requestId">
        <strong>{{ row.label }}</strong><span>{{ row.error || (row.id ? '已生成' : '待生成') }}</span>
        <button v-if="row.id" class="link" :disabled="busy" @click="emit('navigate','knowledge',row.id)">查看知识</button>
        <button v-else class="link" :disabled="busy" @click="queue=queue.filter(r => r !== row)">移除</button>
      </li></ul>
      <div class="actions">
        <button class="button secondary" :disabled="busy || !canAdd" @click="addTemplate">加入解析列表</button>
        <button class="button secondary" :disabled="busy || (!canAdd && !queue.length) || queue.some(r => r.useAi === true)" @click="draft(false)">手工建稿</button>
        <button class="button primary" :disabled="busy || (!canAdd && !queue.length) || queue.some(r => r.useAi === false)" @click="draft(true)">
          <Sparkles :size="17" /> {{ parseMode === 'AI' ? '正在解析…' : '模型解析' }}
        </button>
        <button class="link" @click="emit('navigate', 'knowledge', 'material:' + item.material.id)">查看此原文的知识</button>
      </div>
    </section>
    <p v-if="panelError" class="message error" role="alert">{{ panelError }}</p>
    <section v-if="!parseFocus && item.runs.length" class="form-section">
      <h3>采集任务执行</h3>
      <ul class="simple-list"><li v-for="entry in item.runs" :key="entry.id">
        <button class="link" @click="emit('navigate', 'tasks', 'run:' + entry.id)">{{ date(entry.startedAt, true) }}</button><span>{{ entry.actor }}</span>
      </li></ul>
    </section>
    <Events v-if="!parseFocus" :events="item.events" />
    <template v-if="item.material.status !== 'APPROVED'" #footer>
      <button class="button secondary" :disabled="busy" @click="review(false)">退回原文</button>
      <button class="button primary" :disabled="busy" @click="review(true)">原文审核通过</button>
    </template>
  </Drawer>
  <Drawer v-if="classificationForm" :title="classificationForm.materialIds.length > 1 ? '批量分类' : '资料分类'"
    :busy="busy" @close="classificationForm = null">
    <form id="assign-classification" class="form-grid" @submit.prevent="saveClassifications">
      <label>分类维度<select v-model="classificationForm.groupCode" @change="chooseClassGroup">
        <option v-for="(label, key) in groups" :key="key" :value="key">{{ label }}</option>
      </select></label>
      <label>分类（未选择时清除此维度）<select v-model="classificationForm.classIds" multiple>
        <option v-for="node in currentClassOptions" :key="node.id" :value="node.id">{{ classPath(node) }}</option>
      </select></label>
      <p v-if="classificationError" class="message error" role="alert">{{ classificationError }}</p>
    </form>
    <template #footer>
      <button class="button primary" form="assign-classification" :disabled="busy">保存所选分类</button>
    </template>
  </Drawer>
</template>
