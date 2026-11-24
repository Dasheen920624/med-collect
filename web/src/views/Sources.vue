<script setup>
import { computed, onMounted, ref } from 'vue'
import { Plus } from '@lucide/vue'
import { api, list, send } from '../api'
import Events from '../components/Events.vue'
import Pagination from '../components/Pagination.vue'
import Drawer from '../components/Drawer.vue'
import EmptyState from '../components/EmptyState.vue'
import StateBadge from '../components/StateBadge.vue'

defineProps({ session: Object })
const emit = defineEmits(['notice', 'navigate'])
const accessLabels = { PUBLIC: '公开', PENDING: '待授权', AUTHORIZED: '已授权' }
const sources = ref([])
const form = ref(null)
const issue = ref('')
const formIssue = ref('')
const busy = ref(false)
const usage = ref(null)
const logs = ref(null)
const size = ref(10)
const page = ref(1)
const visible = computed(() => sources.value.slice((page.value-1)*size.value,page.value*size.value))
async function history(p = 1) {
  try { logs.value = await list('/sources/events',{page:p,size:20}) } catch (e) { issue.value = e.message }
}
async function toggle(source) {
  busy.value = true
  try { await send('/sources/'+source.id+'/state',{enabled:!source.enabled},'PUT'); await load() }
  catch (e) { issue.value = e.message }
  finally { busy.value = false }
}

async function load() {
  try {
    sources.value = await api('/sources')
    issue.value = ''
  } catch (e) { issue.value = e.message }
}

function edit(source) {
  form.value = source ? { ...source } : { name: '', url: '', note: '', enabled: true, accessState: 'PUBLIC' }
  formIssue.value = ''
}

async function showUsage(source, page = 1) {
  try { usage.value = await list('/sources/' + source.id, { page, size: 10 }) }
  catch (e) { issue.value = e.message }
}

async function save() {
  const source = form.value
  busy.value = true
  formIssue.value = ''
  try {
    await send(source.id ? '/sources/' + source.id : '/sources', {
      name: source.name, url: source.url, note: source.note, enabled: source.enabled, accessState: source.accessState,
    }, source.id ? 'PUT' : 'POST')
    form.value = null
    emit('notice', '资料来源已保存')
    await load()
  } catch (e) { formIssue.value = e.message }
  finally { busy.value = false }
}

onMounted(load)
</script>

<template>
  <section class="page-head">
    <h1>知识来源配置</h1>
    <div class="actions"><button v-if="session.admin" class="button primary" @click="edit()"><Plus :size="16" />新增来源</button>
      <button class="button secondary" @click="history()">操作日志</button></div>
  </section>
  <p v-if="issue" class="message error" role="alert">{{ issue }}</p>
  <section class="panel"><div v-if="sources.length" class="table-wrap">
    <table class="source-list"><thead><tr><th>序号</th><th>来源名称</th><th>采集地址</th><th>授权状态</th><th>状态</th><th>备注</th><th class="usage-cell">使用情况</th><th v-if="session.admin" class="actions-cell">操作</th></tr></thead>
      <tbody><tr v-for="(item,i) in visible" :key="item.id">
        <td>{{ (page-1)*size+i+1 }}</td><td>{{ item.name }}</td><td><a class="table-url" :href="item.url" :title="item.url" target="_blank" rel="noopener noreferrer">{{ item.url }}</a></td>
        <td>{{ accessLabels[item.accessState] }}</td><td><StateBadge :state="item.enabled ? 'ACTIVE' : 'OFF'" :labels="{ ACTIVE: '启用', OFF: '停用' }" /></td>
        <td>{{ item.note || '—' }}</td><td class="usage-cell"><button v-if="item.taskCount" class="link" @click="showUsage(item)">{{ item.taskCount }} 个任务</button><span v-else>0 个任务</span></td>
        <td v-if="session.admin" class="actions-cell"><div class="table-actions"><button class="link" @click="edit(item)">编辑</button><button class="link" :disabled="busy" @click="toggle(item)">{{ item.enabled ? '停用' : '启用' }}</button></div></td>
      </tr></tbody></table></div>
    <EmptyState v-else title="暂无资料来源" detail="添加常用站点后，可在新建采集任务时直接引用。" />
    <Pagination :page="page" :size="size" :total="sources.length" resizable @change="page=$event" @resize="size=$event;page=1" />
  </section>
  <Drawer v-if="logs" title="来源配置操作日志" @close="logs=null"><Events :events="logs.rows" /><Pagination v-bind="logs" @change="history" /></Drawer>
  <Drawer v-if="form" :title="form.id ? '编辑知识来源' : '新增知识来源'" :busy="busy" @close="form = null">
    <form id="source-form" class="form-grid" @submit.prevent="save">
      <label>来源名称<input v-model.trim="form.name" maxlength="120" required placeholder="例如 学术期刊官网" /></label>
      <label>采集地址<input v-model.trim="form.url" type="url" maxlength="1000" required placeholder="https://…" /></label>
      <label>授权状态<select v-model="form.accessState">
        <option v-for="(label, key) in accessLabels" :key="key" :value="key">{{ label }}</option>
      </select></label>
      <label>备注<textarea v-model.trim="form.note" rows="4" maxlength="500" placeholder="机构、范围或授权信息"></textarea></label>
      <label class="check"><input v-model="form.enabled" type="checkbox" /> 启用来源</label>
      <p v-if="formIssue" class="message error" role="alert">{{ formIssue }}</p>
    </form>
    <template #footer><button class="button secondary" :disabled="busy" @click="form = null">取消</button>
      <button class="button primary" form="source-form" :disabled="busy">保存来源</button></template>
  </Drawer>
  <Drawer v-if="usage" title="来源使用情况" :subtitle="usage.source.name" @close="usage = null">
    <dl class="metadata">
      <div><dt>采集来源</dt><dd><a :href="usage.source.url" :title="usage.source.url" target="_blank" rel="noopener noreferrer">{{ usage.source.name }}</a></dd></div>
      <div><dt>授权 / 状态</dt><dd>{{ accessLabels[usage.source.accessState] }} · {{ usage.source.enabled ? '启用' : '停用' }}</dd></div>
    </dl>
    <p v-if="!usage.tasks.total" class="muted">当前没有采集任务使用此来源。</p>
    <ul v-else class="simple-list"><li v-for="task in usage.tasks.rows" :key="task.id">
      <button class="link" @click="emit('navigate', 'tasks', task.id)">{{ task.name }}</button>
    </li></ul>
    <Pagination v-if="usage.tasks.total > usage.tasks.size" v-bind="usage.tasks" @change="page => showUsage(usage.source,page)" />
    <Events :events="usage.events" />
    <template #footer>
      <button class="button secondary" @click="emit('navigate', 'tasks', 'source:' + usage.source.id)">查询使用此来源的任务</button>
    </template>
  </Drawer>
</template>
