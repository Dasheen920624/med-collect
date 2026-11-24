<script setup>
import { computed, onMounted, ref } from 'vue'
import { Download } from '@lucide/vue'
import { api, send } from '../api'
import Drawer from '../components/Drawer.vue'
import Events from '../components/Events.vue'
import EmptyState from '../components/EmptyState.vue'

const props = defineProps({ session: Object })
const emit = defineEmits(['notice'])
const categories = ref([])
const classes = ref([])
const code = ref('')
const classCode = ref('')
const schema = ref(null)
const rules = ref({})
const error = ref('')
const busy = ref(false)
const reading = ref(false)
const preview = ref(false)
const history = ref(null)
const expanded = ref(['properties','content'])
const sections = computed(() => schema.value ? [
  { key:'properties', name:'属性配置', items:schema.value.fields.filter(f => f.property) },
  { key:'content', name:'目录配置', items:schema.value.fields.filter(f => !f.property) },
].filter(g => g.items.length) : [])
const parameters = computed(() => schema.value && { ...schema.value, rules: Object.fromEntries(
  Object.entries(rules.value).filter(([,r]) => r.required || r.instruction.trim())) })
function toggle(key) { expanded.value = expanded.value.includes(key) ? expanded.value.filter(k => k !== key) : [...expanded.value,key] }
async function showHistory() {
  try { history.value = await api('/template-profiles/'+code.value+'/events?knowledgeClass='+encodeURIComponent(classCode.value)) }
  catch(e) { error.value=e.message }
}
const category = computed(() => categories.value.find(entry => entry.code === code.value))
const editable = field => !field.dictionary && !['references', 'laiyuan', 'guideType'].includes(field.id)
const selectedPath = () => '/template-profiles/' + code.value + '?knowledgeClass=' + encodeURIComponent(classCode.value)

async function chooseCategory() {
  schema.value = null
  classCode.value = ''
  classes.value = []
  error.value = ''
  if (!code.value) return
  const selected = code.value
  reading.value = true
  try {
    const result = await api('/categories/' + selected + '/classes')
    if (selected !== code.value) return
    classes.value = result
    if (!category.value.classRequired) await loadTemplate()
  } catch (e) { if (selected === code.value) error.value = e.message }
  finally { if (selected === code.value) reading.value = false }
}

async function loadTemplate() {
  schema.value = null
  if (!code.value || (category.value.classRequired && !classCode.value)) return
  const selected = selectedPath()
  reading.value = true
  try {
    const result = await api(selected)
    if (selected === selectedPath()) apply(result)
  } catch (e) { if (selected === selectedPath()) error.value = e.message }
  finally { if (selected === selectedPath()) reading.value = false }
}

function apply(value) {
  schema.value = value
  rules.value = Object.fromEntries(value.fields.filter(editable).map(field =>
    [field.id, { instruction: '', required: false, ...value.rules?.[field.id] }]))
  error.value = ''
}

async function save() {
  busy.value = true
  error.value = ''
  try {
    apply(await send(selectedPath(), { revision: schema.value.profileRevision, rules: rules.value }, 'PUT'))
    emit('notice', '生成规则已保存')
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}

function exportTemplate() {
  const uri = URL.createObjectURL(new Blob([JSON.stringify(parameters.value, null, 2)], { type: 'application/json' }))
  const link = document.createElement('a')
  link.href = uri
  link.download = '知识生成模板-' + code.value + '.json'
  link.click()
  setTimeout(() => URL.revokeObjectURL(uri), 500)
}

onMounted(async () => {
  try {
    categories.value = await api('/categories')
    code.value = categories.value.find(c => c.code === '1')?.code || categories.value[0]?.code || ''
    await chooseCategory()
  }
  catch (e) { error.value = e.message }
})
</script>

<template>
  <section class="page-head">
    <h1>知识生成模板</h1>
  </section>
  <p v-if="error" class="message error" role="alert">{{ error }}</p>
  <section class="panel">
    <div class="filters"><label class="filter-field">知识模板<select v-model="code" :disabled="busy" @change="chooseCategory">
      <option v-for="entry in categories" :key="entry.code" :value="entry.code">{{ entry.name }}</option></select></label>
      <label v-if="classes.length" class="filter-field">知识子类<select v-model="classCode" :disabled="busy" @change="loadTemplate">
        <option value="">{{ category?.classRequired ? '请选择知识子类' : '通用模板' }}</option>
        <option v-for="entry in classes" :key="entry.code" :value="entry.code">{{ entry.name }} · {{ entry.code }}</option>
      </select></label>
    </div>
    <div v-if="schema" class="template-content">
      <div class="toolbar"><h2>内容结构</h2><div class="actions">
        <button v-if="sections.some(s=>!expanded.includes(s.key))" class="link" @click="expanded=sections.map(s=>s.key)">全部展开</button><button v-if="expanded.length" class="link" @click="expanded=[]">全部收起</button>
        <button class="button secondary" @click="preview=true">参数预览</button>
        <button v-if="props.session.admin" class="button primary" :disabled="busy" @click="save">保存全部</button>
        <button class="button secondary" @click="showHistory">操作日志</button></div></div>
      <p class="template-origin">{{ schema.category.name }} · {{ schema.origin }} · 生成规则版本 {{ schema.profileRevision }}</p>
      <section v-for="section in sections" :key="section.key" class="template-section">
        <button class="section-toggle" :aria-expanded="expanded.includes(section.key)" @click="toggle(section.key)">{{ expanded.includes(section.key) ? '▾' : '▸' }} {{ section.name }} <span>{{ section.items.length }}</span></button>
        <div v-if="expanded.includes(section.key)" class="table-wrap"><table><thead><tr><th>字段 ID</th><th>字段名称</th><th>字段用途</th><th>提取说明</th><th>必填</th></tr></thead>
          <tbody><tr v-for="field in section.items" :key="field.id"><td>{{ field.id }}</td><td>{{ field.label }}</td>
            <td>{{ field.dictionary ? '业务字典' : field.property ? '知识属性' : '正文目录' }}</td>
            <td><textarea v-if="rules[field.id]" v-model="rules[field.id].instruction" :aria-label="field.label+'提取说明'" rows="1" maxlength="1000" :readonly="!props.session.admin" /><span v-else>业务字段</span></td>
            <td><input v-if="rules[field.id]" v-model="rules[field.id].required" type="checkbox" :aria-label="field.label+'必填'" :disabled="!props.session.admin" /><span v-else>—</span></td>
          </tr></tbody></table></div></section>
    </div>
    <EmptyState v-else :title="reading ? '正在加载业务模板' : '请选择知识模板及适用子类'" />
  </section>
  <Drawer v-if="preview" title="模板参数预览" wide @close="preview=false"><pre class="parameter-preview">{{ JSON.stringify(parameters,null,2) }}</pre>
    <template #footer><button class="button secondary" @click="exportTemplate"><Download :size="16" /> 导出 JSON</button></template>
  </Drawer>
  <Drawer v-if="history" title="模板操作日志" @close="history=null"><Events :events="history" /></Drawer>
</template>
