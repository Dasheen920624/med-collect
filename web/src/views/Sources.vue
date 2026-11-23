<script setup>
import { onMounted, ref } from 'vue'
import { Plus } from '@lucide/vue'
import { api, send } from '../api'
import { date } from '../labels'
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

async function showUsage(source) {
  try { usage.value = await api('/sources/' + source.id) }
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
    <div><h1>知识来源配置</h1><p>维护任务引用的来源、授权状态和使用记录。</p></div>
    <button v-if="session.admin" class="button primary" @click="edit()"><Plus :size="18" /> 新建来源</button>
  </section>
  <p v-if="issue" class="message error" role="alert">{{ issue }}</p>
  <section class="panel">
    <div class="panel-head"><h2>来源列表</h2><span class="muted">{{ sources.length }} 个来源</span></div>
    <div v-if="sources.length" class="table-wrap">
      <table><thead><tr><th>来源名称</th><th>资料地址</th><th>授权状态</th><th>状态</th><th>登记时间</th><th>操作</th></tr></thead>
        <tbody><tr v-for="item in sources" :key="item.id">
          <td><strong>{{ item.name }}</strong><small v-if="item.note" class="subline">{{ item.note }}</small></td>
          <td><a class="table-url" :href="item.url" target="_blank" rel="noopener noreferrer">{{ item.url }}</a></td>
          <td>{{ accessLabels[item.accessState] }}</td>
          <td><StateBadge :state="item.enabled ? 'ACTIVE' : 'OFF'" :labels="{ ACTIVE: '已启用', OFF: '已停用' }" /></td>
          <td>{{ date(item.createdAt) }}</td>
          <td class="table-actions"><button class="link" @click="showUsage(item)">使用情况</button>
            <button v-if="session.admin" class="link" @click="edit(item)">编辑</button></td>
        </tr></tbody>
      </table>
    </div>
    <EmptyState v-else title="暂无资料来源" detail="添加常用站点后，可在新建采集任务时直接引用。" />
  </section>
  <Drawer v-if="form" :title="form.id ? '编辑资料来源' : '新建资料来源'" :busy="busy" @close="form = null">
    <form id="source-form" class="form-grid" @submit.prevent="save">
      <label>来源名称<input v-model.trim="form.name" maxlength="120" required placeholder="例如 学术期刊官网" /></label>
      <label>资料地址<input v-model.trim="form.url" type="url" maxlength="1000" required placeholder="https://…" /></label>
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
    <p v-if="!usage.tasks.total" class="muted">当前没有采集任务使用此来源。</p>
    <ul v-else class="simple-list"><li v-for="task in usage.tasks.rows" :key="task.id">
      <button class="link" @click="emit('navigate', 'tasks', task.id)">{{ task.name }}</button>
    </li></ul>
    <template #footer>
      <button class="button secondary" @click="emit('navigate', 'tasks', 'source:' + usage.source.id)">查询使用此来源的任务</button>
    </template>
  </Drawer>
</template>
