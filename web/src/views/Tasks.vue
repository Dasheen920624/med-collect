<script setup>
import BusinessLibrary from '../components/BusinessLibrary.vue'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { History, Play, RefreshCw, Sparkles, Upload } from '@lucide/vue'
import { api, list, send } from '../api'
import { date, dateRange, modes, resultStates, runStates, scheduleText } from '../labels'
import Drawer from '../components/Drawer.vue'
import EmptyState from '../components/EmptyState.vue'
import UploadOriginal from '../components/UploadOriginal.vue'
import Events from '../components/Events.vue'
import Pagination from '../components/Pagination.vue'
import StateBadge from '../components/StateBadge.vue'

const props = defineProps({ session: Object, detail: String, subjectId: String, directory: Array })
const emit = defineEmits(['navigate', 'notice'])
const table = ref({ rows: [], total: 0, page: 1, size: 10 })
const initialFilters = () => ({ query: '', mode: '', sourceId: '', enabled: '', state: '', from: '', until: '' })
const filters = ref(initialFilters())
const pageSize = ref(10)
const uploadTask = ref(null)
let request = 0
const sources = ref([])
const selectedTask = ref(null)
const selectedRun = ref(null)
const selectedCandidates = ref([])
const history = ref(null)
const editing = ref(null)
const planner = ref(false)
const instruction = ref('')
const suggestions = ref([])
const error = ref('')
const drawerError = ref('')
const busy = ref(false)
const loading = ref(false)
let poll
const scheduleModes = { MANUAL: '手动执行', INTERVAL: '按间隔执行', ONCE: '单次定时', DAILY: '每日定时' }

const businessLibrary=ref(false)
const availableModes = computed(() => Object.fromEntries(
  Object.entries(modes).filter(([code]) => code !== 'BUSINESS' && (code !== 'WEB' || props.session.webSearch)),
))

function taskInput(mode = 'URL') {
  return { name: '', mode, queryText: '', sourceId: '', captureMode: 'SINGLE',
    resultLimit: 8, intervalMinutes: 0, enabled: true }
}

async function load(page = table.value.page) {
  const seq = ++request
  loading.value = true
  try {
    const { from, until, ...rest } = filters.value
    const result = await list('/tasks', { ...rest, ...dateRange(from, until), subjectId: props.subjectId, page, size: pageSize.value })
    if (seq === request) { table.value = result; error.value = '' }
  } catch (e) { if (seq === request) error.value = e.message }
  finally { if (seq === request) loading.value = false }
}

async function edit(task, quick = '') {
  try {
    const detail = task ? await api('/tasks/' + task.id) : null
    const entry = detail?.task || taskInput(quick || 'URL')
    const urls = detail?.urls?.length ? detail.urls : entry.mode === 'URL' && entry.queryText ? [entry.queryText] : []
    const ids = detail?.sourceIds?.length ? detail.sourceIds : entry.sourceId ? [entry.sourceId] : []
    editing.value = { ...entry, quick, targetMode: 'address', sourceId: entry.sourceId || '', sourceIds: ids, targetUrls: urls.join('\n'),
      unlimited:task ? entry.resultLimit===0 : quick==='URL', resultLimit:entry.resultLimit || 10, materialTypeIds:detail?.materialTypeIds || [],
      scheduleMode: scheduleMode(entry), scheduleLocal: localDate(entry.scheduleAt), dailyTime: entry.dailyTime || '',
      selectResults: entry.selectResults ?? true }
    drawerError.value = ''
  } catch (e) { error.value = e.message }
}

function scheduleMode(task) { return task.scheduleMode || (task.intervalMinutes ? 'INTERVAL' : 'MANUAL') }
function localDate(value) {
  if (!value) return ''
  const time = new Date(value)
  return new Date(time.getTime() - time.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}
async function save() {
  busy.value = true
  drawerError.value = ''
  const form = editing.value
  try {
    const mode = form.mode === 'UPLOAD' ? 'MANUAL' : form.scheduleMode
    const urls = form.quick === 'URL' && form.targetMode === 'source' ? [] : form.targetUrls.split(/\r?\n/).map(u => u.trim()).filter(Boolean)
    const selectedSources = form.quick === 'URL' && form.targetMode === 'address' ? [] : form.sourceIds
    const generatedName = form.queryText || urls[0] || sources.value.find(s => selectedSources.includes(s.id))?.name || modes[form.mode]
    const saved = await send(form.id ? '/tasks/' + form.id : '/tasks', {
      ...form, resultLimit:form.mode==='URL' && form.unlimited ? 0 : form.resultLimit,
      name: form.name || Array.from(modes[form.mode] + '：' + generatedName).slice(0,120).join(''), intervalMinutes: mode === 'INTERVAL' ? form.intervalMinutes : 0, scheduleMode: mode,
      scheduleAt: mode === 'ONCE' && form.scheduleLocal ? new Date(form.scheduleLocal).getTime() : null,
      urls: form.mode === 'URL' ? urls : [],
      sourceIds: form.mode === 'EUROPE_PMC' ? [] : form.mode === 'UPLOAD'
        ? (form.sourceId ? [form.sourceId] : []) : selectedSources,
      selectResults: form.mode === 'WEB' && form.selectResults,
    }, form.id ? 'PUT' : 'POST')
    editing.value = null
    emit('notice', '采集任务已保存')
    await load(1)
    if (form.quick && mode === 'MANUAL' && !form.id) {
      await run(saved)
    } else if (selectedTask.value?.task.id === form.id) await openTask(form.id)
  } catch (e) { drawerError.value = e.message }
  finally { busy.value = false }
}

async function openTask(id) {
  try {
    selectedTask.value = await api('/tasks/' + id)
    error.value = ''
  } catch (e) { error.value = e.message }
}

async function openRun(id) {
  try {
    if (selectedRun.value?.run.id !== id) selectedCandidates.value = []
    selectedRun.value = await api('/runs/' + id)
    error.value = ''
  } catch (e) { error.value = e.message }
}

async function captureSelected() {
  busy.value = true
  try {
    const result = await send('/runs/' + selectedRun.value.run.id + '/capture', { resultIds: selectedCandidates.value })
    await openRun(result.id)
    await load()
    emit('notice', '已提交所选搜索结果')
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}

async function showHistory(page = 1) {
  try {
    history.value = await list('/runs', { page, size: 20 })
    error.value = ''
  } catch (e) { error.value = e.message }
}

function working(task) { return task.latestRun?.status === 'RUNNING' || task.leaseUntil > Date.now() }
function taskActions(task, latest = task.latestRun) {
  if (working(task) || latest?.status === 'RUNNING') return {}
  const completed = latest?.status === 'SUCCEEDED'
  const retry = latest?.status === 'FAILED' || latest?.status === 'PARTIAL'
  const upload = task.mode === 'UPLOAD'
  const imported = task.mode === 'BUSINESS'
  const edit = !imported && !(upload && completed)
  let label = '执行'
  if (upload) label = completed ? '追加文件' : '上传文件'
  else if (retry) label = '重试采集'
  else if (task.selectResults) label = completed ? '重新搜索' : '搜索'
  else if (imported) label = completed ? '重新导入' : '导入'
  else if (completed) label = '再次执行'
  return { edit, label, inList: task.enabled && !completed }
}
const detailActions = computed(() => selectedTask.value ? taskActions(selectedTask.value.task, selectedTask.value.runs[0]) : {})
const resultLabel = entry => entry.archivedAt ? 'ARCHIVED' : entry.status
const detailResultStates = { ...resultStates, ARCHIVED: '原文已移除' }
function taskName(id) { return table.value.rows.find(task => task.id === id)?.name || '查看执行详情' }

async function run(task) {
  if (task.mode === 'UPLOAD') {
    uploadTask.value = task.id
    return
  }
  busy.value = true
  try {
    const result = await send('/tasks/' + task.id + '/runs')
    emit('notice', '已发起采集，可查看执行详情')
    await openRun(result.id)
    await load()
  } catch (e) { error.value = e.message; emit('notice',e.message) }
  finally { busy.value = false }
}

function openPlanner() {
  drawerError.value = ''
  instruction.value = ''
  suggestions.value = []
  planner.value = true
}

async function suggest() {
  drawerError.value = ''
  busy.value = true
  try {
    suggestions.value = (await send('/plans/suggest', { instruction: instruction.value }))
      .map(row => ({ ...taskInput(row.mode), ...row, selected: true, saved: false }))
  } catch (e) { drawerError.value = e.message }
  finally { busy.value = false }
}

async function savePlan() {
  busy.value = true
  drawerError.value = ''
  try {
    for (const suggestion of suggestions.value.filter(row => row.selected && !row.saved)) {
      const { selected, saved, ...task } = suggestion
      await send('/tasks', task)
      suggestion.saved = true
    }
    planner.value = false
    emit('notice', '选中的采集任务已保存')
  } catch (e) { drawerError.value = e.message }
  finally {
    busy.value = false
    await load(1)
  }
}

async function applyDetail(detail) {
  if (detail === 'business') businessLibrary.value=true
  else if (detail === 'new' && props.session.admin) await edit(null,'URL')
  else if (detail === 'history') await showHistory()
  else if (detail?.startsWith('run:')) await openRun(detail.slice(4))
  else if (detail?.startsWith('source:')) {
    filters.value.sourceId = detail.slice(7)
    await load(1)
  }
  else if (detail) await openTask(detail)
}

onMounted(async () => {
  await Promise.all([load(), api('/sources').then(result => { sources.value = result }).catch(e => { error.value = e.message })])
  await applyDetail(props.detail)
  poll = setInterval(async () => {
    if (selectedRun.value?.run.status === 'RUNNING') {
      await openRun(selectedRun.value.run.id)
    }
    if (table.value.rows.some(working) || selectedRun.value?.run.status === 'RUNNING') await load()
    if (selectedTask.value?.runs.some(run => run.status === 'RUNNING')) await openTask(selectedTask.value.task.id)
  }, 2800)
})
onUnmounted(() => clearInterval(poll))
watch(() => props.detail, async detail => {
  selectedTask.value = null
  selectedRun.value = null
  history.value = null
  editing.value = null
  filters.value = initialFilters()
  if (detail) await applyDetail(detail)
  else await load(1)
})
watch(() => props.subjectId, () => load(1))

function resultText(r) {
  if (!r) return '—'
  const counts = [[r.candidateCount,'条搜索结果'],[r.newCount,'份原文'],[r.metadataCount,'条来源记录'],[r.duplicateCount,'份重复'],[r.failureCount,'份失败']]
  return counts.filter(([n]) => n > 0).map(([n,name]) => n + ' ' + name).join(' · ') || '0 份原文'
}
</script>

<template>
  <section class="page-head">
    <h1>知识采集管理</h1>
    <div v-if="session.admin" class="actions">
        <button class="button secondary" @click="edit(null,'URL')">指定网址下载</button>
      <button class="button secondary" @click="uploadTask = ''">手工上传</button>
      <button class="button primary" :disabled="!session.webSearch" :title="session.webSearch ? '' : '请先配置全网搜索服务'" @click="edit(null,'WEB')">全网搜索</button>
    </div>
  </section>
  <p v-if="error" class="message error" role="alert">{{ error }}</p>
  <section class="panel">
    <div class="panel-head"><h2>采集任务列表</h2><div class="actions">
      <button class="link" @click="showHistory()"><History :size="15" /> 执行记录</button>
      <button v-if="session.admin" class="link" @click="edit(null,'EUROPE_PMC')">文献检索</button>
      <button v-if="session.cdssConfigured" class="link" @click="businessLibrary=true">从 4.23 导入文献</button>
      <button v-if="session.admin" class="link" @click="openPlanner"><Sparkles :size="15" /> 智能规划</button>
      <button class="icon-button" aria-label="刷新任务" :disabled="loading" @click="load()"><RefreshCw :size="15" /></button></div></div>
    <form class="filters" @submit.prevent="load(1)">
      <label class="filter-field">任务状态<select v-model="filters.state"><option value="">全部状态</option>
        <option value="WAITING">待执行</option><option v-for="(name,k) in runStates" :key="k" :value="k">{{ name }}</option></select></label>
      <label class="filter-field">采集方式<select v-model="filters.mode"><option value="">全部方式</option>
        <option v-for="(name,k) in modes" :key="k" :value="k">{{ name }}</option></select></label>
      <label class="filter-field">创建时间<input v-model="filters.from" aria-label="创建开始日期" type="date" :max="filters.until" /></label>
      <span>至</span><input v-model="filters.until" aria-label="创建结束日期" type="date" :min="filters.from" />
      <button class="button primary" :disabled="loading">查询</button>
      <button type="button" class="button secondary" @click="filters = initialFilters(); load(1)">重置</button>
      <details class="extra-filters"><summary>更多筛选</summary><div class="actions">
        <input v-model.trim="filters.query" aria-label="搜索任务" placeholder="搜索任务或采集内容" maxlength="400" />
        <select v-model="filters.sourceId" aria-label="资料来源"><option value="">全部资料来源</option>
          <option v-for="s in sources" :key="s.id" :value="s.id">{{ s.name }}</option></select>
        <select v-model="filters.enabled" aria-label="计划状态"><option value="">全部计划状态</option><option value="true">已启用</option><option value="false">已暂停</option></select>
      </div></details>
    </form>
    <div v-if="table.rows.length" class="table-wrap">
      <table class="task-list"><thead><tr><th>序号</th><th>任务标题</th><th>创建者</th><th>采集方式</th><th>创建时间</th><th>执行计划</th><th>采集结果</th><th>状态</th><th class="actions-cell">操作</th></tr></thead>
        <tbody><tr v-for="(task,i) in table.rows" :key="task.id">
          <td>{{ (table.page - 1) * table.size + i + 1 }}</td><td><button class="link" @click="openTask(task.id)">{{ task.name }}</button></td>
          <td>{{ task.createdBy || '未记录' }}</td><td>{{ modes[task.mode] }}</td><td>{{ date(task.createdAt,true) }}</td>
          <td>{{ scheduleText(task) }}<small v-if="!task.enabled" class="subline">计划已暂停</small></td>
          <td><button v-if="task.latestRun" class="link" @click="openRun(task.latestRun.id)">{{ resultText(task.latestRun) }}</button><span v-else>—</span></td>
          <td><StateBadge :state="task.latestRun?.status || 'WAITING'" :labels="{...runStates,WAITING:'待执行'}" /></td>
          <td class="actions-cell"><div class="table-actions"><button class="link" @click="openTask(task.id)">详情</button>
            <button v-if="session.admin && taskActions(task).edit" class="link" :disabled="busy" @click="edit(task)">编辑</button>
            <button v-if="session.admin && taskActions(task).inList" class="link" :disabled="busy" @click="run(task)">{{ taskActions(task).label }}</button>
          </div></td>
        </tr></tbody>
      </table>
    </div>
    <EmptyState v-else :title="loading ? '正在读取任务' : '暂无匹配任务'" />
    <Pagination v-bind="table" resizable @change="load" @resize="pageSize = $event; load(1)" />
  </section>
  <UploadOriginal v-if="uploadTask !== null" :task-id="uploadTask" :directory="directory" @notice="emit('notice',$event)" @close="uploadTask = null"
    @complete="uploadTask = null; load(1); openTask($event.taskId)" />

  <BusinessLibrary v-if="businessLibrary" :admin="session.admin" :directory="props.directory" @close="businessLibrary=false"
    @collected="run=>{businessLibrary=false;load();openRun(run.id)}" />
  <Drawer v-if="history" title="执行记录" wide @close="history = null">
    <div v-if="history.rows.length" class="table-wrap">
      <table><thead><tr><th>任务</th><th>执行时间</th><th>入库 / 重复 / 失败</th><th>状态</th></tr></thead>
        <tbody><tr v-for="entry in history.rows" :key="entry.id">
          <td><button class="link" @click="openRun(entry.id)">{{ taskName(entry.taskId) }}</button></td><td>{{ date(entry.startedAt) }}</td>
          <td>{{ entry.newCount }} / {{ entry.duplicateCount }} / {{ entry.failureCount }}</td>
          <td><StateBadge :state="entry.status" :labels="runStates" /></td>
        </tr></tbody></table>
    </div>
    <EmptyState v-else title="暂无执行记录" />
    <Pagination v-bind="history" @change="showHistory" />
  </Drawer>

  <Drawer v-if="selectedTask" :title="selectedTask.task.name" subtitle="采集任务详情" wide @close="selectedTask = null">
    <dl class="metadata">
      <div><dt>采集方式</dt><dd>{{ modes[selectedTask.task.mode] }}</dd></div>
      <div v-if="selectedTask.task.mode === 'BUSINESS'"><dt>采集范围</dt><dd>4.23 知识管理 · {{ selectedTask.task.queryText.endsWith('/metadata') ? '来源和摘要' : '选定的文献附件' }}</dd></div>
      <div v-else><dt>采集范围</dt><dd>{{ [...(selectedTask.sourceIds || []).map(id => sources.find(item => item.id === id)?.name),
        ...(selectedTask.urls || []), selectedTask.task.queryText].filter(Boolean).filter((item, i, all) => all.indexOf(item) === i).join(' · ') ||
        sources.find(item => item.id === selectedTask.task.sourceId)?.url || '—' }}</dd></div>
      <div><dt>执行计划</dt><dd>{{ scheduleText(selectedTask.task) }}</dd></div>
      <div><dt>创建信息</dt><dd>{{ selectedTask.task.createdBy }} · {{ date(selectedTask.task.createdAt, true) }}</dd></div>
      <div><dt>下次执行</dt><dd>{{ date(selectedTask.task.nextRunAt, true) }}</dd></div>
    </dl>
    <section class="form-section">
      <h3>原文获取记录</h3>
      <p v-if="!selectedTask.runs.length" class="muted">暂无执行记录</p>
      <ul v-else class="simple-list"><li v-for="entry in selectedTask.runs" :key="entry.id">
        <button class="link" @click="openRun(entry.id)">{{ date(entry.startedAt, true) }}</button>
        <span>新增 {{ entry.newCount }} · 重复 {{ entry.duplicateCount }} · 失败 {{ entry.failureCount }}</span>
        <StateBadge :state="entry.status" :labels="runStates" />
      </li></ul>
    </section>
    <section v-if="selectedTask.latestResults?.length" class="form-section">
      <div class="toolbar"><h3>最近一次采集结果</h3><button class="link" @click="openRun(selectedTask.runs[0].id)">查看全部结果</button></div>
      <ul class="simple-list"><li v-for="entry in selectedTask.latestResults" :key="entry.id">
        <button v-if="entry.materialId" class="link" @click="emit('navigate','materials',entry.materialId)">{{ entry.title }}</button>
        <a v-else-if="entry.url" :href="entry.url" target="_blank" rel="noopener noreferrer">{{ entry.title || '查看来源' }}</a>
        <span v-else>{{ entry.title }}</span>
        <StateBadge :state="resultLabel(entry)" :labels="detailResultStates" />
      </li></ul>
    </section>
    <details class="raw-text"><summary>操作记录</summary><Events :events="selectedTask.events" /></details>
    <template v-if="session.admin && detailActions.label" #footer>
      <button v-if="detailActions.edit" class="button secondary" :disabled="busy" @click="edit(selectedTask.task)">编辑任务</button>
      <button class="button primary" :disabled="busy" @click="run(selectedTask.task)">
        <Upload v-if="selectedTask.task.mode === 'UPLOAD'" :size="17" /><Play v-else :size="17" />
        {{ detailActions.label }}
      </button>
    </template>
  </Drawer>

  <Drawer v-if="editing" :title="editing.id ? '编辑采集任务' : modes[editing.quick] || '新建采集任务'" :busy="busy" @close="editing = null">
    <form id="task-form" class="form-grid" @submit.prevent="save">
      <div v-if="editing.quick==='WEB'" class="form-grid"><strong>搜索方式</strong><div class="tab-buttons">
        <button type="button" aria-pressed="true">简单搜索</button><button type="button" @click="editing=null;openPlanner()">详细规划</button></div></div>
      <label>任务名称<input v-model.trim="editing.name" :required="!editing.quick" maxlength="120" placeholder="例如 成人高血压管理指南" /></label>
      <label v-if="!editing.quick">采集方式<select v-model="editing.mode"><option v-for="(name, code) in availableModes" :key="code" :value="code">{{ name }}</option></select></label>
      <label v-if="editing.mode === 'UPLOAD'">资料来源<select v-model="editing.sourceId">
        <option value="">直接指定</option><option v-for="item in sources.filter(item => item.enabled || item.id === editing.sourceId)"
          :key="item.id" :value="item.id">{{ item.name }}{{ item.enabled ? '' : '（已停用）' }}</option></select></label>
      <fieldset v-if="editing.quick === 'URL'" class="form-choice"><legend>采集方式</legend><div class="choice-row">
        <label class="check"><input type="radio" value="address" v-model="editing.targetMode" />输入网址</label>
        <label class="check"><input type="radio" value="source" v-model="editing.targetMode" />选择已配置来源</label>
      </div></fieldset>
      <label v-if="(editing.mode === 'URL' && (editing.quick !== 'URL' || editing.targetMode === 'source')) || editing.mode === 'WEB'">资料来源（可多选）<select v-model="editing.sourceIds" multiple :required="editing.quick === 'URL' && editing.targetMode === 'source'">
        <option v-for="item in sources.filter(item => item.enabled || editing.sourceIds.includes(item.id))"
          :key="item.id" :value="item.id">{{ item.name }}{{ item.accessState === 'PENDING' ? '（待授权）' : '' }}</option>
      </select></label>
      <label v-if="editing.mode === 'URL' && (editing.quick !== 'URL' || editing.targetMode === 'address')">资料地址（每行一个）<textarea v-model="editing.targetUrls" rows="3" :required="editing.quick === 'URL' || !editing.sourceIds.length" placeholder="https://…" /></label>
      <label v-if="editing.mode === 'EUROPE_PMC' || editing.mode === 'WEB'">检索关键词<input v-model.trim="editing.queryText" required maxlength="1000" placeholder="例如 hypertension guideline" /></label>
      <a v-if="editing.mode==='URL' && editing.targetMode==='address' && /^https?:\/\/[^\s]+$/i.test(editing.targetUrls.trim())" :href="editing.targetUrls.trim()" target="_blank" rel="noopener noreferrer" class="link">预览 ↗</a>
      <details v-if="['URL','WEB'].includes(editing.mode)" class="raw-text"><summary>资料类型 · {{ editing.materialTypeIds.length ? editing.materialTypeIds.map(id=>directory.find(n=>n.id===id)?.name).filter(Boolean).join('、') : '不限' }}</summary>
        <div class="choice-row"><label v-for="n in directory.filter(n=>n.groupCode==='MATERIAL' && n.enabled)" :key="n.id" class="check"><input v-model="editing.materialTypeIds" type="checkbox" :value="n.id"/>{{ n.name }}</label></div></details>
      <div v-if="editing.mode === 'URL'" class="choice-row">
        <label class="check"><input v-model="editing.captureMode" type="radio" value="SINGLE" /> 单一网页或文件</label>
        <label class="check"><input v-model="editing.captureMode" type="radio" value="LINKS" /> 栏目内链接</label>
      </div>
      <label v-if="editing.mode === 'WEB'" class="check"><input v-model="editing.selectResults" type="checkbox" /> 先搜索，再人工选择结果采集</label>
      <div v-if="editing.mode !== 'UPLOAD'" class="form-grid">
        <label>执行计划<select v-model="editing.scheduleMode">
          <option v-for="(name, mode) in scheduleModes" :key="mode" :value="mode">{{ mode === 'MANUAL' && editing.quick ? '立即执行' : name }}</option>
        </select></label>
        <label v-if="editing.scheduleMode === 'INTERVAL'">间隔（分钟）<input v-model.number="editing.intervalMinutes" type="number" min="1" max="525600" required /></label>
        <label v-if="editing.scheduleMode === 'ONCE'">执行时间<input v-model="editing.scheduleLocal" type="datetime-local" required /></label>
        <label v-if="editing.scheduleMode === 'DAILY'">每天执行时间（北京时间）<input v-model="editing.dailyTime" type="time" required /></label>
        <label>采集数量<span v-if="editing.mode==='URL'" class="check"><input v-model="editing.unlimited" type="checkbox"/>不限</span>
          <input v-if="editing.mode!=='URL' || !editing.unlimited" v-model.number="editing.resultLimit" type="number" list="capture-limits" min="1" max="100" required />
          <datalist id="capture-limits"><option v-for="n in [5,10,20,50,100]" :key="n" :value="n" /></datalist>
          <small v-if="editing.mode==='URL' && editing.unlimited">处理所选地址或当前栏目中的全部站内资料链接。</small></label>
        <label v-if="editing.scheduleMode !== 'MANUAL'" class="check"><input v-model="editing.enabled" type="checkbox" /> 启用定时计划</label>
      </div>
      <p v-if="drawerError" class="message error" role="alert">{{ drawerError }}</p>
    </form>
    <template #footer>
      <button class="button secondary" :disabled="busy" @click="editing = null">取消</button>
      <button class="button primary" form="task-form" :disabled="busy">{{ editing.quick && editing.scheduleMode === 'MANUAL' ? (editing.mode === 'WEB' ? '开始搜索' : '立即采集') : '保存任务' }}</button>
    </template>
  </Drawer>

  <Drawer v-if="selectedRun" :title="selectedRun.task.name" subtitle="采集执行详情" wide @close="selectedRun = null">
    <dl class="metadata">
      <div><dt>状态</dt><dd><StateBadge :state="selectedRun.run.status" :labels="runStates" /></dd></div>
      <div><dt>执行人</dt><dd>{{ selectedRun.run.actor }}</dd></div>
      <div><dt>开始</dt><dd>{{ date(selectedRun.run.startedAt, true) }}</dd></div>
      <div><dt>结束</dt><dd>{{ date(selectedRun.run.finishedAt, true) }}</dd></div>
      <div><dt>处理结果</dt><dd>原文 {{ selectedRun.run.newCount }} · 来源记录 {{ selectedRun.run.metadataCount }} · 重复 {{ selectedRun.run.duplicateCount }} · 失败 {{ selectedRun.run.failureCount }}</dd></div>
      <div v-if="selectedRun.run.parentRunId"><dt>关联搜索</dt><dd><button class="link" @click="openRun(selectedRun.run.parentRunId)">查看搜索结果</button></dd></div>
    </dl>
    <p v-if="selectedRun.run.note" class="message">{{ selectedRun.run.note }}</p>
    <section class="form-section">
      <h3>资料明细</h3>
      <div v-if="selectedRun.results.length" class="table-wrap">
        <table><thead><tr><th>资料或来源</th><th>结果</th><th>说明</th></tr></thead>
          <tbody><tr v-for="entry in selectedRun.results" :key="entry.id">
            <td><input v-if="session.admin && entry.status === 'CANDIDATE'" v-model="selectedCandidates" type="checkbox"
              :value="entry.id" :aria-label="'选择 ' + (entry.title || entry.url)" />
              <button v-if="entry.materialId" class="link" @click="emit('navigate', 'materials', entry.materialId)">{{ entry.title || '查看原文' }}</button>
              <a v-else-if="entry.url" :href="entry.url" target="_blank" rel="noopener noreferrer" class="table-url">{{ entry.title || entry.url }}</a>
              <span v-else>{{ entry.title || '—' }}</span></td>
            <td><StateBadge :state="resultLabel(entry)" :labels="detailResultStates" /></td><td>{{ entry.note || '—' }}</td>
          </tr></tbody></table>
      </div>
      <p v-else class="muted">{{ selectedRun.run.status === 'RUNNING' ? '等待采集结果…' : '暂无资料明细' }}</p>
    </section>
    <template v-if="session.admin && selectedRun.run.status !== 'RUNNING' && selectedRun.results.some(entry => entry.status === 'CANDIDATE')" #footer>
      <button class="button primary" :disabled="busy || !selectedCandidates.length" @click="captureSelected">采集所选结果</button>
    </template>
  </Drawer>

  <Drawer v-if="planner" title="详细规划" wide :busy="busy" @close="planner = false">
    <form class="form-grid" @submit.prevent="suggest">
      <label>采集需求<textarea v-model.trim="instruction" required rows="4" maxlength="3000"
        placeholder="例如 收集成人高血压诊疗指南与近五年的系统综述"></textarea></label>
      <button class="button secondary fit" :disabled="busy"><Sparkles :size="17" /> {{ busy && !suggestions.length ? '正在拟定…' : '生成任务建议' }}</button>
    </form>
    <section v-if="suggestions.length" class="form-section"><h3>任务建议</h3>
      <div v-for="(row, index) in suggestions" :key="index" class="inline-card form-grid">
        <label class="check"><input v-model="row.selected" type="checkbox" :disabled="row.saved" /> {{ row.saved ? '任务已保存' : '保存此任务' }}</label>
        <label>名称<input v-model.trim="row.name" maxlength="120" :disabled="row.saved" /></label>
        <label>{{ row.mode === 'URL' ? '资料网址' : '检索关键词' }}<input v-model.trim="row.queryText" maxlength="1000" :disabled="row.saved" /></label>
      </div>
    </section>
    <p v-if="drawerError" class="message error" role="alert">{{ drawerError }}</p>
    <template #footer>
      <button class="button secondary" :disabled="busy" @click="planner = false">关闭</button>
      <button class="button primary" :disabled="busy || !suggestions.some(row => row.selected && !row.saved)" @click="savePlan">保存选中任务</button>
    </template>
  </Drawer>
</template>
