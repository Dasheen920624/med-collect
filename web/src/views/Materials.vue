<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { Download, ExternalLink, FileText, Search, Sparkles, Upload } from '@lucide/vue'
import { api, list, original, send } from '../api'
import { date, materialStates, selection, size } from '../labels'
import Drawer from '../components/Drawer.vue'
import EmptyState from '../components/EmptyState.vue'
import Events from '../components/Events.vue'
import Pagination from '../components/Pagination.vue'
import StateBadge from '../components/StateBadge.vue'

const props = defineProps({ session: Object, detail: String, parseOnly: Boolean })
const emit = defineEmits(['navigate', 'notice'])
const table = ref({ rows: [], total: 0, page: 1, size: 20 })
const filters = ref({ query: '', status: '', format: '', classId: '', originalsOnly: false })
const groups = { SUBJECT: '学科病种', DOMAIN: '知识类别', MATERIAL: '资料类型' }
const formats = ['PDF', 'HTML', 'DOCX', 'TXT', 'CSV', 'XLS', 'XLSX', 'METADATA']
const classifications = ref([])
const selectedRows = ref([])
const classificationForm = ref(null)
const classificationError = ref('')
const categories = ref([])
const classes = ref([])
const chosen = ref({ typeCode: '', knowledgeClass: '' })
const item = ref(null)
const uploadForm = ref(null)
const notes = ref('')
const summary = ref('')
const preview = ref(false)
const error = ref('')
const panelError = ref('')
const uploadError = ref('')
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
    emit('notice', '资料分类已保存')
  } catch (e) { classificationError.value = e.message }
  finally { busy.value = false }
}

async function load(page = 1) {
  loading.value = true
  try {
    table.value = await list('/materials', { ...filters.value, status: props.parseOnly ? 'APPROVED' : filters.value.status, page, size: 20 })
    selectedRows.value = []
    error.value = ''
  } catch (e) { error.value = e.message }
  finally { loading.value = false }
}

async function openMaterial(id) {
  try {
    item.value = await api('/materials/' + id)
    summary.value = item.value.material.sourceAbstract || ''
    notes.value = ''
    preview.value = false
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

function openUpload(taskId = '') {
  uploadForm.value = { files: [], title: '', sourceUrl: '', taskId }
  uploadError.value = ''
}

async function upload() {
  const form = uploadForm.value
  if (!form.files.length) {
    uploadError.value = '请选择原件文件'
    return
  }
  if (form.files.length > 20) {
    uploadError.value = '一次最多上传 20 份原件'
    return
  }
  busy.value = true
  uploadError.value = ''
  const receipts = []
  const failures = []
  try {
    for (const file of form.files) {
      const data = new FormData()
      data.append('file', file)
      if (form.files.length === 1 && form.title) data.append('title', form.title)
      if (form.sourceUrl) data.append('sourceUrl', form.sourceUrl)
      if (form.taskId) data.append('taskId', form.taskId)
      try { receipts.push(await send('/materials/upload', data)) }
      catch (e) { failures.push({ file, error: e.message }) }
    }
    if (receipts.length) {
      await load()
      const duplicates = receipts.filter(receipt => receipt.duplicate).length
      emit('notice', '新增 ' + (receipts.length - duplicates) + ' 份，重复 ' + duplicates + ' 份')
    }
    if (failures.length) {
      form.files = failures.map(entry => entry.file)
      uploadError.value = failures.length + ' 份未上传。' + failures[0].file.name + ' ' + failures[0].error
    } else {
      uploadForm.value = null
      if (receipts.length === 1) await openMaterial(receipts[0].materialId)
    }
  }
  finally { busy.value = false }
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
    await openMaterial(id)
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
    const knowledge = await send('/materials/' + item.value.material.id + '/drafts', { ...chosen.value, useAi })
    emit('notice', useAi ? '模型解析完成，草稿待核对' : '知识草稿已创建')
    emit('navigate', 'knowledge', knowledge.id)
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
  await Promise.all([load(), api('/categories').then(value => { categories.value = value }).catch(e => { error.value = e.message }),
    api('/classifications').then(value => { classifications.value = value }).catch(e => { error.value = e.message })])
  await applyDetail(props.detail)
})
watch(() => props.detail, applyDetail)
</script>

<template>
  <section class="page-head">
    <div><h1>{{ parseOnly ? '静态知识解析' : '文献原文管理' }}</h1>
      <p>{{ parseOnly ? '从已审核的原文按业务模板生成知识草稿。' : '查看原件、分类、来源和执行记录。' }}</p></div>
    <div v-if="!parseOnly" class="actions">
      <button class="button secondary" :disabled="!selectedRows.length" @click="editClassifications(selectedRows)">批量分类</button>
      <button v-if="session.admin" class="button primary" @click="openUpload()"><Upload :size="18" /> 手工上传</button>
    </div>
  </section>
  <p v-if="error" class="message error" role="alert">{{ error }}</p>
  <section class="panel">
    <div class="panel-head"><h2>{{ parseOnly ? '已审核原文' : '原文及检索记录' }}</h2></div>
    <form class="filters" @submit.prevent="load()">
      <label class="search"><Search :size="17" /><input v-model.trim="filters.query" maxlength="400" aria-label="检索原文" placeholder="搜索标题、来源或 DOI" /></label>
      <select v-if="!parseOnly" v-model="filters.status" aria-label="原文状态" @change="load()">
        <option value="">全部状态</option><option v-for="state in selection(materialStates)" :key="state.value" :value="state.value">{{ state.label }}</option>
      </select>
      <select v-model="filters.format" aria-label="文件格式" @change="load()">
        <option value="">全部格式</option><option v-for="format in formats" :key="format" :value="format">{{ format === 'METADATA' ? '仅来源和摘要' : format }}</option>
      </select>
      <select v-model="filters.classId" aria-label="资料分类" @change="load()">
        <option value="">全部分类</option><option value="unclassified">未分类</option>
        <optgroup v-for="(label, key) in groups" :key="key" :label="label">
          <option v-for="node in classifications.filter(entry => entry.groupCode === key)" :key="node.id" :value="node.id">{{ classPath(node) }}</option>
        </optgroup>
      </select>
      <label class="check"><input v-model="filters.originalsOnly" type="checkbox" @change="load()" /> 仅含原件</label>
      <button class="button secondary" :disabled="loading">查询</button>
    </form>
    <div v-if="table.rows.length" class="table-wrap">
      <table><thead><tr><th v-if="!parseOnly">选择</th><th>资料标题</th><th>格式</th><th>来源</th><th>采集时间</th><th>审核状态</th><th>操作</th></tr></thead>
        <tbody><tr v-for="row in table.rows" :key="row.id">
          <td v-if="!parseOnly"><input v-model="selectedRows" type="checkbox" :value="row.id" :aria-label="'选择 ' + row.title" /></td>
          <td><button class="link" @click="openMaterial(row.id)">{{ row.title }}</button><small class="subline truncate">{{ row.fileName }}</small></td>
          <td><span class="file-kind">{{ row.formatCode === 'METADATA' ? '仅来源和摘要' : row.formatCode }}</span></td>
          <td><a v-if="row.sourceUrl" class="table-url" :href="row.sourceUrl" target="_blank" rel="noopener noreferrer">{{ row.sourceName || '原始来源' }}</a><span v-else>{{ row.sourceName || '—' }}</span></td>
          <td>{{ date(row.capturedAt) }}</td><td><StateBadge :state="row.status" :labels="materialStates" /></td>
          <td><button class="link" @click="openMaterial(row.id)">{{ parseOnly ? '解析' : '查看' }}</button></td>
        </tr></tbody>
      </table>
    </div>
    <EmptyState v-else :title="loading ? '正在查询原文' : '暂无匹配原文'" detail="执行采集任务或上传原件后，可在这里查看。" />
    <Pagination :page="table.page" :size="table.size" :total="table.total" @change="load" />
  </section>

  <Drawer v-if="uploadForm" title="上传文献原文" :busy="busy" @close="uploadForm = null">
    <form id="upload-form" class="form-grid" @submit.prevent="upload">
      <label class="file-picker">
        <Upload :size="26" /><strong>{{ uploadForm.files.length ? '已选择 ' + uploadForm.files.length + ' 份原件' : '选择原件文件' }}</strong>
        <span>PDF、DOCX、TXT、CSV、XLS、XLSX、HTML，每份最多 20 MB</span>
        <input type="file" multiple accept=".pdf,.docx,.txt,.csv,.xls,.xlsx,.htm,.html" @change="uploadForm.files = Array.from($event.target.files)" />
      </label>
      <label v-if="uploadForm.files.length <= 1">标题<input v-model.trim="uploadForm.title" maxlength="400" placeholder="留空时使用原件中的标题或文件名" /></label>
      <label>来源网址<input v-model.trim="uploadForm.sourceUrl" type="url" maxlength="1000" placeholder="原文下载页或发布机构地址" /></label>
      <p v-if="uploadError" role="alert" class="message error">{{ uploadError }}</p>
    </form>
    <template #footer>
      <button class="button secondary" :disabled="busy" @click="uploadForm = null">取消</button>
      <button class="button primary" form="upload-form" :disabled="busy">{{ busy ? '正在上传…' : '上传原件' }}</button>
    </template>
  </Drawer>

  <Drawer v-if="item" :title="item.material.title" subtitle="原文详情" wide :busy="busy" @close="item = null">
    <div class="toolbar">
      <StateBadge :state="item.material.status" :labels="materialStates" />
      <div class="actions">
        <a v-if="item.material.sourceUrl" class="button secondary" :href="item.material.sourceUrl" target="_blank" rel="noopener noreferrer"><ExternalLink :size="16" /> 原始来源</a>
        <button v-if="item.material.formatCode === 'PDF'" class="button secondary" @click="preview = !preview"><FileText :size="16" /> {{ preview ? '收起 PDF' : '预览 PDF' }}</button>
        <a class="button secondary" :href="original(item.material.id)"><Download :size="16" />
          {{ item.material.formatCode === 'METADATA' ? '下载来源记录' : '下载原件' }}</a>
      </div>
    </div>
    <iframe v-if="preview" class="pdf-preview" :src="original(item.material.id, true)" title="PDF 原文"></iframe>
    <dl class="metadata">
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
    <section class="form-section">
      <h3>原文摘要</h3>
      <p v-if="item.material.status === 'APPROVED'" class="reading-text">{{ item.material.sourceAbstract || '暂无原文摘要' }}</p>
      <textarea v-else v-model="summary" aria-label="原文摘要" rows="5" maxlength="12000" placeholder="可根据已核验的原件补充摘要"></textarea>
      <details v-if="item.material.rawText" class="raw-text"><summary>查看提取文本</summary><pre>{{ item.material.rawText }}</pre></details>
    </section>
    <section v-if="item.knowledge.total" class="form-section">
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
      <h3><Sparkles :size="18" /> 整理知识</h3>
      <div class="two-fields">
        <label>知识类型<select v-model="chosen.typeCode" @change="chooseType">
          <option value="">选择 4.23 知识类型</option><option v-for="entry in categories" :key="entry.code" :value="entry.code">{{ entry.name }}</option>
        </select></label>
        <label v-if="classes.length || category?.classRequired">知识子类<select v-model="chosen.knowledgeClass">
          <option value="">选择知识子类</option><option v-for="entry in classes" :key="entry.code" :value="entry.code">{{ entry.name }}（{{ entry.code }}）</option>
        </select></label>
      </div>
      <div class="actions">
        <button class="button secondary" :disabled="busy || !category || (category.classRequired && !chosen.knowledgeClass)" @click="draft(false)">手工建稿</button>
        <button class="button primary" :disabled="busy || !category || (category.classRequired && !chosen.knowledgeClass)" @click="draft(true)">
          <Sparkles :size="17" /> {{ parseMode === 'AI' ? '正在解析…' : '模型解析' }}
        </button>
        <button class="link" @click="emit('navigate', 'knowledge', 'material:' + item.material.id)">查看此原文的知识</button>
      </div>
    </section>
    <p v-if="panelError" class="message error" role="alert">{{ panelError }}</p>
    <section v-if="item.runs.length" class="form-section">
      <h3>采集任务执行</h3>
      <ul class="simple-list"><li v-for="entry in item.runs" :key="entry.id">
        <button class="link" @click="emit('navigate', 'tasks', 'run:' + entry.id)">{{ date(entry.startedAt, true) }}</button><span>{{ entry.actor }}</span>
      </li></ul>
    </section>
    <Events :events="item.events" />
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
