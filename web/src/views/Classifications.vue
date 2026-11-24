<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { Plus, RefreshCw, Search } from '@lucide/vue'
import { api, send } from '../api'
import DirectoryTree from '../components/DirectoryTree.vue'
import Pagination from '../components/Pagination.vue'
import Drawer from '../components/Drawer.vue'
import EmptyState from '../components/EmptyState.vue'
import StateBadge from '../components/StateBadge.vue'

const props = defineProps({ session: Object })
const emit = defineEmits(['notice', 'navigate', 'directory-changed', 'scope'])
const groups = { SUBJECT: '学科病种目录', DOMAIN: '知识类别目录', MATERIAL: '资料类型' }
const nodes = ref([])
const reference=ref(null)
const refreshing=ref(false)
async function refreshReference() {
  refreshing.value=true; error.value=''
  try {reference.value=await send('/business-catalog/refresh',{}); await load(); emit('directory-changed');emit('notice','已更新 4.23 业务资料')}
  catch(e){error.value=e.message}finally{refreshing.value=false}
}
const group = ref('SUBJECT')
const query = ref('')
const form = ref(null)
const error = ref('')
const formError = ref('')
const busy = ref(false)

const selected = ref('')
const size = ref(10)
const page = ref(1)
const groupNodes = computed(() => nodes.value.filter(n => n.groupCode === group.value))
const current = computed(() => groupNodes.value.find(n => n.id === selected.value))
const siblingsOf = node => groupNodes.value.filter(n => n.parentId === node.parentId)
const rows = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (q) return groupNodes.value.filter(n => [n.name,n.businessCode].some(v => v?.toLowerCase().includes(q)))
  if (!current.value) return groupNodes.value.filter(n => !n.parentId)
  if (current.value.parentId || group.value === 'MATERIAL') return [current.value]
  return groupNodes.value.filter(n => n.parentId === current.value.id)
})
const visible = computed(() => rows.value.slice((page.value-1)*size.value,page.value*size.value))
watch(group, () => { selected.value = ''; query.value = ''; page.value = 1 })
watch([selected,query], () => { page.value = 1 })
function adjacent(node, offset) {
  const siblings = siblingsOf(node).filter(n=>!n.remoteKey)
  return siblings[siblings.findIndex(n => n.id === node.id)+offset]
}
async function move(node,offset) {
  const peer = adjacent(node,offset)
  if (!peer) return
  busy.value = true
  try { await send('/classifications/'+node.id+'/position',{ peerId:peer.id, up:offset<0, revision:node.revision },'PUT'); await load(); emit('directory-changed') }
  catch(e) { error.value=e.message } finally { busy.value=false }
}
async function toggle(node) {
  busy.value=true
  try { await send('/classifications/'+node.id,{...node,enabled:!node.enabled},'PUT'); await load(); emit('directory-changed') }
  catch(e) { error.value=e.message } finally { busy.value=false }
}
const parents = computed(() => nodes.value.filter(node => node.groupCode === form.value?.groupCode
  && !node.parentId && node.id !== form.value?.id && (node.enabled || node.id === form.value?.parentId)))

function showMaterials(node) {
  emit('scope', node.groupCode === 'SUBJECT' ? node.id : '')
  emit('navigate', 'materials', 'class:' + node.id)
}
function nameOf(id) { return nodes.value.find(node => node.id === id)?.name }
function active(node) { return node.enabled && (!node.parentId || nodes.value.find(parent => parent.id === node.parentId)?.enabled) }

async function load() {
  try {
    const [items,state]=await Promise.all([api('/classifications'),api('/business-catalog')]); nodes.value=items; reference.value=state
    error.value = ''
  } catch (e) { error.value = e.message }
}

function edit(node) {
  form.value = node ? { ...node, parentId: node.parentId || '' } :
    { groupCode: group.value, parentId: group.value === 'MATERIAL' ? '' : current.value?.parentId || current.value?.id || '', name: '', businessCode: '', displayOrder: 10, enabled: true }
  formError.value = ''
}

async function save() {
  busy.value = true
  formError.value = ''
  try {
    const { id, groupCode, name, parentId, businessCode, displayOrder, enabled, revision } = form.value
    await send(id ? '/classifications/' + id : '/classifications',
      { groupCode, name, parentId, businessCode, displayOrder, enabled, revision }, id ? 'PUT' : 'POST')
    form.value = null
    emit('notice', '分类已保存')
    emit('directory-changed')
    await load()
  } catch (e) { formError.value = e.message }
  finally { busy.value = false }
}

onMounted(load)
</script>

<template>
  <section class="page-head">
    <h1>知识分类配置</h1>
    <div class="tab-buttons"><button v-for="(name,k) in groups" :key="k" :aria-pressed="group===k" @click="group=k">{{ name }}</button></div>
  </section>
  <p v-if="error" class="message error" role="alert">{{ error }}</p>
  <section v-if="reference" class="reference-strip"><div><strong>4.23 业务目录</strong><small>{{ reference.syncedAt ? '更新于 '+new Date(reference.syncedAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}) : '尚无同步记录' }} · {{ reference.specialties }} 个专科 · {{ reference.diseases }} 个病种</small></div>
    <button v-if="session.admin && reference.configured" class="button secondary" :disabled="refreshing" @click="refreshReference"><RefreshCw :size="15"/>{{ refreshing?'正在同步…':'同步业务资料' }}</button></section>
  <p v-if="reference?.error" class="message error">{{ reference.error }}</p>
  <div class="directory-layout">
    <DirectoryTree :key="group" :title="groups[group]" :nodes="groupNodes" :all-label="group === 'SUBJECT' ? '全部学科' : '全部分类'"
      :selected="selected" @select="selected=$event;query=''" />
    <section class="panel"><div class="panel-head"><h2>{{ current?.name || groups[group] }}</h2>
      <button v-if="props.session.admin" class="button primary" @click="edit()"><Plus :size="16" />{{ group==='SUBJECT' ? (selected ? '新增病种' : '新增学科') : '新增分类' }}</button></div>
      <div class="filters"><label class="search"><Search :size="15" /><input v-model.trim="query" placeholder="搜索分类名称或编码" aria-label="搜索分类" /></label></div>
      <div v-if="rows.length" class="table-wrap"><table class="classification-list"><thead><tr><th>序号</th><th>名称</th><th>{{ group==='SUBJECT' && !selected ? '病种数量' : '子项数量' }}</th><th>对应编码</th><th>状态</th><th class="usage-cell">使用情况</th><th class="actions-cell">操作</th></tr></thead>
        <tbody><tr v-for="(node,i) in visible" :key="node.id"><td>{{ (page-1)*size+i+1 }}</td>
          <td><button class="link" @click="selected=node.id;query=''">{{ node.name }}</button></td>
          <td>{{ groupNodes.filter(n => n.parentId === node.id).length }}</td><td>{{ node.businessCode || '—' }}<small class="subline">{{ node.remoteKey ? '4.23 业务目录' : '采集端维护' }}</small></td>
          <td><StateBadge :state="active(node) ? 'ACTIVE' : 'OFF'" :labels="{ACTIVE:'启用',OFF:node.enabled ? '上级停用' : '停用'}" /></td>
          <td class="usage-cell"><button v-if="node.materialCount" class="link" @click="showMaterials(node)">{{ node.materialCount }} 份原文</button><span v-else>0 份原文</span><span class="usage-separator" aria-hidden="true"> / </span>{{ node.knowledgeCount }} 条知识</td>
          <td class="actions-cell"><span v-if="node.remoteKey" class="muted">随业务同步</span><div v-else-if="props.session.admin" class="table-actions"><button class="link" @click="edit(node)">编辑</button>
            <button class="link" :disabled="busy" @click="toggle(node)">{{ node.enabled ? '停用' : '启用' }}</button>
            <button v-if="adjacent(node,-1)" class="link" :disabled="busy" @click="move(node,-1)">上移</button><button v-if="adjacent(node,1)" class="link" :disabled="busy" @click="move(node,1)">下移</button></div><span v-else>—</span></td>
        </tr></tbody></table></div>
      <EmptyState v-else title="暂无匹配分类" />
      <Pagination :page="page" :size="size" :total="rows.length" resizable @change="page=$event" @resize="size=$event;page=1" />
    </section>
  </div>
  <Drawer v-if="form" :title="form.id ? '编辑分类' : '新建分类'" :busy="busy" @close="form = null">
    <form id="class-form" class="form-grid" @submit.prevent="save">
      <label>分类名称<input v-model.trim="form.name" required maxlength="120" /></label>
      <label v-if="form.groupCode !== 'MATERIAL'">上级目录<select v-model="form.parentId">
        <option value="">一级目录</option><option v-for="node in parents" :key="node.id" :value="node.id">{{ node.name }}</option>
      </select></label>
      <label>对应编码<input v-model.trim="form.businessCode" maxlength="80" /></label>
      <label>显示顺序<input v-model.number="form.displayOrder" type="number" min="0" max="999999" required /></label>
      <label class="check"><input v-model="form.enabled" type="checkbox" /> 启用分类</label>
      <p v-if="formError" class="message error" role="alert">{{ formError }}</p>
    </form>
    <template #footer>
      <button class="button secondary" :disabled="busy" @click="form = null">取消</button>
      <button class="button primary" form="class-form" :disabled="busy">保存分类</button>
    </template>
  </Drawer>
</template>
