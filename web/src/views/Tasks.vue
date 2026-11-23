<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { History, Play, Plus, RefreshCw, Sparkles, Upload } from '@lucide/vue'
import { api, list, send } from '../api'
import { date, modes, resultStates, runStates } from '../labels'
import Drawer from '../components/Drawer.vue'
import EmptyState from '../components/EmptyState.vue'
import Events from '../components/Events.vue'
import Pagination from '../components/Pagination.vue'
import StateBadge from '../components/StateBadge.vue'

const props = defineProps({ session: Object, detail: String })
const emit = defineEmits(['navigate', 'notice'])
const table = ref({ rows: [], total: 0, page: 1, size: 20 })
const filters = ref({ query: '', mode: '', sourceId: '', enabled: '' })
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

const availableModes = computed(() => Object.fromEntries(
  Object.entries(modes).filter(([code]) => code !== 'WEB' || props.session.webSearch),
))

function taskInput(mode = 'URL') {
  return { name: '', mode, queryText: '', sourceId: '', captureMode: 'SINGLE',
    resultLimit: 8, intervalMinutes: 0, enabled: true }
}

async function load(page = table.value.page) {
  loading.value = true
  try {
    table.value = await list('/tasks', { ...filters.value, page, size: 20 })
    error.value = ''
  } catch (e) { error.value = e.message }
  finally { loading.value = false }
}

async function edit(task) {
  try {
    const detail = task ? await api('/tasks/' + task.id) : null
    const entry = detail?.task || taskInput()
    const urls = detail?.urls?.length ? detail.urls : entry.mode === 'URL' && entry.queryText ? [entry.queryText] : []
    const ids = detail?.sourceIds?.length ? detail.sourceIds : entry.sourceId ? [entry.sourceId] : []
    editing.value = { ...entry, sourceId: entry.sourceId || '', sourceIds: ids, targetUrls: urls.join('\n'),
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
function scheduleText(task) {
  const mode = scheduleMode(task)
  if (mode === 'INTERVAL') return '每 ' + task.intervalMinutes + ' 分钟'
  if (mode === 'DAILY') return '每日 ' + task.dailyTime + '（北京时间）'
  if (mode === 'ONCE') return '单次 · ' + date(task.scheduleAt, true)
  return scheduleModes.MANUAL
}

async function save() {
  busy.value = true
  drawerError.value = ''
  const form = editing.value
  try {
    const mode = form.mode === 'UPLOAD' ? 'MANUAL' : form.scheduleMode
    await send(form.id ? '/tasks/' + form.id : '/tasks', {
      ...form, intervalMinutes: mode === 'INTERVAL' ? form.intervalMinutes : 0, scheduleMode: mode,
      scheduleAt: mode === 'ONCE' && form.scheduleLocal ? new Date(form.scheduleLocal).getTime() : null,
      urls: form.mode === 'URL' ? form.targetUrls.split(/\r?\n/).map(url => url.trim()).filter(Boolean) : [],
      sourceIds: form.mode === 'EUROPE_PMC' ? [] : form.mode === 'UPLOAD'
        ? (form.sourceId ? [form.sourceId] : []) : form.sourceIds,
      selectResults: form.mode === 'WEB' && form.selectResults,
    }, form.id ? 'PUT' : 'POST')
    editing.value = null
    emit('notice', '采集任务已保存')
    if (selectedTask.value?.task.id === form.id) await openTask(form.id)
    await load(1)
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

function working(task) { return task.leaseUntil && task.leaseUntil > Date.now() }
function taskName(id) { return table.value.rows.find(task => task.id === id)?.name || '查看执行详情' }

async function run(task) {
  if (task.mode === 'UPLOAD') {
    emit('navigate', 'materials', 'upload:' + task.id)
    return
  }
  busy.value = true
  try {
    const result = await send('/tasks/' + task.id + '/runs')
    emit('notice', '已发起采集，可查看执行详情')
    await openRun(result.id)
    await load()
  } catch (e) { error.value = e.message }
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
  if (detail === 'new' && props.session.admin) await edit()
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
watch(() => props.detail, applyDetail)
</script>

<template>
  <section class="page-head">
    <div><h1>知识采集管理</h1><p>设置采集目标和执行计划，查看每次搜索、采集及失败明细。</p></div>
    <div class="actions">
      <button class="button secondary" @click="showHistory()"><History :size="18" /> 执行记录</button>
      <button v-if="session.admin" class="button secondary" @click="openPlanner"><Sparkles :size="18" /> 智能规划</button>
      <button v-if="session.admin" class="button primary" @click="edit()"><Plus :size="19" /> 新建任务</button>
    </div>
  </section>
  <p v-if="error" class="message error" role="alert">{{ error }}</p>
  <section class="panel">
    <div class="panel-head"><h2>任务列表</h2><button class="icon-button" aria-label="刷新任务" :disabled="loading" @click="load()"><RefreshCw :size="17" /></button></div>
    <form class="filters" @submit.prevent="load(1)">
      <input v-model.trim="filters.query" aria-label="搜索任务" placeholder="搜索任务或采集内容" maxlength="400" />
      <select v-model="filters.mode" aria-label="采集方式" @change="load(1)">
        <option value="">全部采集方式</option><option v-for="(name, mode) in modes" :key="mode" :value="mode">{{ name }}</option>
      </select>
      <select v-model="filters.sourceId" aria-label="资料来源" @change="load(1)">
        <option value="">全部资料来源</option><option v-for="source in sources" :key="source.id" :value="source.id">{{ source.name }}</option>
      </select>
      <select v-model="filters.enabled" aria-label="计划状态" @change="load(1)">
        <option value="">全部计划状态</option><option value="true">已启用</option><option value="false">已暂停</option>
      </select>
      <button class="button secondary" :disabled="loading">查询</button>
    </form>
    <div v-if="table.rows.length" class="table-wrap">
      <table><thead><tr><th>任务名称</th><th>采集方式</th><th>执行计划</th><th>下次执行</th><th>状态</th><th>操作</th></tr></thead>
        <tbody><tr v-for="task in table.rows" :key="task.id">
          <td><button class="link" @click="openTask(task.id)">{{ task.name }}</button>
            <small class="subline truncate" :title="task.queryText">{{ task.queryText || '—' }}</small></td>
          <td>{{ modes[task.mode] }}<small v-if="task.captureMode === 'LINKS'" class="subline">栏目链接</small></td>
          <td>{{ scheduleText(task) }}</td>
          <td>{{ date(task.nextRunAt) }}</td>
          <td><StateBadge :state="working(task) ? 'RUNNING' : task.enabled ? 'ACTIVE' : 'OFF'"
            :labels="{ ...runStates, ACTIVE: '可执行', OFF: '已暂停' }" /></td>
          <td><div class="table-actions">
            <button v-if="session.admin" class="link" :disabled="working(task) || busy" @click="run(task)">
              {{ task.mode === 'UPLOAD' ? '上传' : task.selectResults ? '搜索' : '执行' }}
            </button>
            <button v-if="session.admin" class="link" :disabled="working(task)" @click="edit(task)">编辑</button>
            <button v-if="!session.admin" class="link" @click="openTask(task.id)">详情</button>
          </div></td>
        </tr></tbody>
      </table>
    </div>
    <EmptyState v-else :title="loading ? '正在读取任务' : '暂无采集任务'" detail="支持指定网址、医学文献检索和手工上传。" />
    <Pagination v-bind="table" @change="load" />
  </section>

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
      <div><dt>采集范围</dt><dd>{{ [...(selectedTask.sourceIds || []).map(id => sources.find(item => item.id === id)?.name),
        ...(selectedTask.urls || []), selectedTask.task.queryText].filter(Boolean).filter((item, i, all) => all.indexOf(item) === i).join(' · ') ||
        sources.find(item => item.id === selectedTask.task.sourceId)?.url || '—' }}</dd></div>
      <div><dt>执行计划</dt><dd>{{ scheduleText(selectedTask.task) }}</dd></div>
      <div><dt>创建信息</dt><dd>{{ selectedTask.task.createdBy }} · {{ date(selectedTask.task.createdAt, true) }}</dd></div>
      <div><dt>下次执行</dt><dd>{{ date(selectedTask.task.nextRunAt, true) }}</dd></div>
    </dl>
    <section class="form-section">
      <h3>最近执行</h3>
      <p v-if="!selectedTask.runs.length" class="muted">暂无执行记录</p>
      <ul v-else class="simple-list"><li v-for="entry in selectedTask.runs" :key="entry.id">
        <button class="link" @click="openRun(entry.id)">{{ date(entry.startedAt, true) }}</button>
        <span>新增 {{ entry.newCount }} · 重复 {{ entry.duplicateCount }} · 失败 {{ entry.failureCount }}</span>
        <StateBadge :state="entry.status" :labels="runStates" />
      </li></ul>
    </section>
    <Events :events="selectedTask.events" />
    <template v-if="session.admin" #footer>
      <button class="button secondary" :disabled="working(selectedTask.task)" @click="edit(selectedTask.task)">编辑任务</button>
      <button class="button primary" :disabled="busy || working(selectedTask.task)" @click="run(selectedTask.task)">
        <Upload v-if="selectedTask.task.mode === 'UPLOAD'" :size="17" /><Play v-else :size="17" />
        {{ selectedTask.task.mode === 'UPLOAD' ? '上传文件' : '立即执行' }}
      </button>
    </template>
  </Drawer>

  <Drawer v-if="editing" :title="editing.id ? '编辑采集任务' : '新建采集任务'" :busy="busy" @close="editing = null">
    <form id="task-form" class="form-grid" @submit.prevent="save">
      <label>任务名称<input v-model.trim="editing.name" required maxlength="120" placeholder="例如 成人高血压管理指南" /></label>
      <label>采集方式<select v-model="editing.mode"><option v-for="(name, code) in availableModes" :key="code" :value="code">{{ name }}</option></select></label>
      <label v-if="editing.mode === 'UPLOAD'">资料来源<select v-model="editing.sourceId">
        <option value="">直接指定</option><option v-for="item in sources.filter(item => item.enabled || item.id === editing.sourceId)"
          :key="item.id" :value="item.id">{{ item.name }}{{ item.enabled ? '' : '（已停用）' }}</option></select></label>
      <label v-if="editing.mode === 'URL' || editing.mode === 'WEB'">资料来源（可多选）<select v-model="editing.sourceIds" multiple>
        <option v-for="item in sources.filter(item => item.enabled || editing.sourceIds.includes(item.id))"
          :key="item.id" :value="item.id">{{ item.name }}{{ item.accessState === 'PENDING' ? '（待授权）' : '' }}</option>
      </select></label>
      <label v-if="editing.mode === 'URL'">采集网址（每行一个）<textarea v-model="editing.targetUrls" rows="3" :required="!editing.sourceIds.length" placeholder="https://…" /></label>
      <label v-if="editing.mode === 'EUROPE_PMC' || editing.mode === 'WEB'">检索关键词<input v-model.trim="editing.queryText" required maxlength="1000" placeholder="例如 hypertension guideline" /></label>
      <div v-if="editing.mode === 'URL'" class="choice-row">
        <label class="check"><input v-model="editing.captureMode" type="radio" value="SINGLE" /> 单一网页或文件</label>
        <label class="check"><input v-model="editing.captureMode" type="radio" value="LINKS" /> 栏目内链接</label>
      </div>
      <label v-if="editing.mode === 'WEB'" class="check"><input v-model="editing.selectResults" type="checkbox" /> 先搜索，再人工选择结果采集</label>
      <div v-if="editing.mode !== 'UPLOAD'" class="form-grid">
        <label>执行计划<select v-model="editing.scheduleMode">
          <option v-for="(name, mode) in scheduleModes" :key="mode" :value="mode">{{ name }}</option>
        </select></label>
        <label v-if="editing.scheduleMode === 'INTERVAL'">间隔（分钟）<input v-model.number="editing.intervalMinutes" type="number" min="1" max="525600" required /></label>
        <label v-if="editing.scheduleMode === 'ONCE'">执行时间<input v-model="editing.scheduleLocal" type="datetime-local" required /></label>
        <label v-if="editing.scheduleMode === 'DAILY'">每天执行时间（北京时间）<input v-model="editing.dailyTime" type="time" required /></label>
        <label>单次结果上限<input v-model.number="editing.resultLimit" type="number" min="1" max="20" required /></label>
        <label v-if="editing.scheduleMode !== 'MANUAL'" class="check"><input v-model="editing.enabled" type="checkbox" /> 启用定时计划</label>
      </div>
      <p v-if="drawerError" class="message error" role="alert">{{ drawerError }}</p>
    </form>
    <template #footer>
      <button class="button secondary" :disabled="busy" @click="editing = null">取消</button>
      <button class="button primary" form="task-form" :disabled="busy">保存任务</button>
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
            <td><StateBadge :state="entry.status" :labels="resultStates" /></td><td>{{ entry.note || '—' }}</td>
          </tr></tbody></table>
      </div>
      <p v-else class="muted">{{ selectedRun.run.status === 'RUNNING' ? '等待采集结果…' : '暂无资料明细' }}</p>
    </section>
    <template v-if="session.admin && selectedRun.run.status !== 'RUNNING' && selectedRun.results.some(entry => entry.status === 'CANDIDATE')" #footer>
      <button class="button primary" :disabled="busy || !selectedCandidates.length" @click="captureSelected">采集所选结果</button>
    </template>
  </Drawer>

  <Drawer v-if="planner" title="智能采集规划" subtitle="根据需求拟定任务，确认后保存。" wide :busy="busy" @close="planner = false">
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
