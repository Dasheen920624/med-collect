<script setup>
import { computed, onMounted, ref } from 'vue'
import { Plus, Search } from '@lucide/vue'
import { api, send } from '../api'
import Drawer from '../components/Drawer.vue'
import EmptyState from '../components/EmptyState.vue'
import StateBadge from '../components/StateBadge.vue'

const props = defineProps({ session: Object })
const emit = defineEmits(['notice', 'navigate'])
const groups = { SUBJECT: '学科病种目录', DOMAIN: '知识类别目录', MATERIAL: '资料类型' }
const nodes = ref([])
const group = ref('SUBJECT')
const query = ref('')
const form = ref(null)
const error = ref('')
const formError = ref('')
const busy = ref(false)

const rows = computed(() => {
  const current = nodes.value.filter(node => node.groupCode === group.value)
  const roots = current.filter(node => !node.parentId)
  const ordered = roots.flatMap(root => [root, ...current.filter(node => node.parentId === root.id)])
  const key = query.value.trim().toLowerCase()
  return ordered.filter(node => !key || [node.name, node.businessCode, nameOf(node.parentId)]
    .some(value => value?.toLowerCase().includes(key)))
})
const parents = computed(() => nodes.value.filter(node => node.groupCode === form.value?.groupCode
  && !node.parentId && node.id !== form.value?.id && (node.enabled || node.id === form.value?.parentId)))

function nameOf(id) { return nodes.value.find(node => node.id === id)?.name }
function active(node) { return node.enabled && (!node.parentId || nodes.value.find(parent => parent.id === node.parentId)?.enabled) }

async function load() {
  try {
    nodes.value = await api('/classifications')
    error.value = ''
  } catch (e) { error.value = e.message }
}

function edit(node) {
  form.value = node ? { ...node, parentId: node.parentId || '' } :
    { groupCode: group.value, parentId: '', name: '', businessCode: '', displayOrder: 10, enabled: true }
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
    await load()
  } catch (e) { formError.value = e.message }
  finally { busy.value = false }
}

onMounted(load)
</script>

<template>
  <section class="page-head">
    <div><h1>知识分类配置</h1><p>维护原文使用的学科病种、知识类别和资料类型。</p></div>
    <button v-if="props.session.admin" class="button primary" @click="edit()"><Plus :size="18" /> 新建分类</button>
  </section>
  <p v-if="error" class="message error" role="alert">{{ error }}</p>
  <section class="panel">
    <div class="filters">
      <select v-model="group" aria-label="分类维度">
        <option v-for="(label, key) in groups" :key="key" :value="key">{{ label }}</option>
      </select>
      <label class="search"><Search :size="17" /><input v-model="query" placeholder="搜索分类名称或编码" aria-label="搜索分类" /></label>
    </div>
    <div v-if="rows.length" class="table-wrap">
      <table>
        <thead><tr><th>分类名称</th><th>对应编码</th><th>状态</th><th>使用情况</th><th>操作</th></tr></thead>
        <tbody><tr v-for="node in rows" :key="node.id">
          <td><small v-if="node.parentId" class="subline">{{ nameOf(node.parentId) }}</small>{{ node.name }}</td>
          <td>{{ node.businessCode || '—' }}</td>
          <td><StateBadge :state="active(node) ? 'ACTIVE' : 'OFF'" :labels="{ ACTIVE: '启用', OFF: node.enabled ? '上级停用' : '停用' }" /></td>
          <td><button class="link" @click="emit('navigate', 'materials', 'class:' + node.id)">{{ node.materialCount }} 份原文</button> / {{ node.knowledgeCount }} 条知识</td>
          <td><button v-if="props.session.admin" class="link" @click="edit(node)">编辑</button></td>
        </tr></tbody>
      </table>
    </div>
    <EmptyState v-else title="暂无匹配分类" />
  </section>
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
