<script setup>
import { computed, onMounted, ref } from 'vue'
import { Download } from '@lucide/vue'
import { api, send } from '../api'
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
  try {
    const result = await api('/categories/' + selected + '/classes')
    if (selected !== code.value) return
    classes.value = result
    if (!category.value.classRequired) await loadTemplate()
  } catch (e) { error.value = e.message }
}

async function loadTemplate() {
  schema.value = null
  if (!code.value || (category.value.classRequired && !classCode.value)) return
  const selected = selectedPath()
  try {
    const result = await api(selected)
    if (selected === selectedPath()) apply(result)
  } catch (e) { error.value = e.message }
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
  const uri = URL.createObjectURL(new Blob([JSON.stringify(schema.value, null, 2)], { type: 'application/json' }))
  const link = document.createElement('a')
  link.href = uri
  link.download = '知识生成模板-' + code.value + '.json'
  link.click()
  setTimeout(() => URL.revokeObjectURL(uri), 500)
}

onMounted(async () => {
  try { categories.value = await api('/categories') }
  catch (e) { error.value = e.message }
})
</script>

<template>
  <section class="page-head">
    <div><h1>知识生成模板</h1><p>核对 4.23 模板字段，并配置新稿件的提取说明和提交校验。</p></div>
    <div v-if="schema" class="actions">
      <button class="button secondary" @click="exportTemplate"><Download :size="17" /> 导出模板</button>
      <button v-if="props.session.admin" class="button primary" :disabled="busy" @click="save">保存生成规则</button>
    </div>
  </section>
  <p v-if="error" class="message error" role="alert">{{ error }}</p>
  <section class="panel">
    <div class="filters">
      <select v-model="code" aria-label="知识类型" :disabled="busy" @change="chooseCategory">
        <option value="">请选择知识类型</option>
        <option v-for="entry in categories" :key="entry.code" :value="entry.code">{{ entry.name }}</option>
      </select>
      <select v-if="classes.length" v-model="classCode" aria-label="知识子类" :disabled="busy" @change="loadTemplate">
        <option value="">{{ category?.classRequired ? '请选择知识子类' : '通用模板' }}</option>
        <option v-for="entry in classes" :key="entry.code" :value="entry.code">{{ entry.name }} · {{ entry.code }}</option>
      </select>
    </div>
    <template v-if="schema">
      <div class="panel-head"><h2>{{ schema.category.name }}</h2><span class="muted">{{ schema.origin }} · 规则版本 {{ schema.profileRevision }}</span></div>
      <div class="table-wrap"><table>
        <thead><tr><th>字段</th><th>字段 ID</th><th>用途</th><th>提取说明</th><th>必填</th></tr></thead>
        <tbody><tr v-for="field in schema.fields" :key="field.id">
          <td>{{ field.label }}</td><td>{{ field.id }}</td>
          <td>{{ field.dictionary ? '业务字典' : field.property ? '属性' : '正文目录' }}</td>
          <td v-if="rules[field.id]"><textarea v-model="rules[field.id].instruction" :aria-label="field.label + '提取说明'" maxlength="1000"
            rows="2" :readonly="!props.session.admin" /></td>
          <td v-else>—</td>
          <td v-if="rules[field.id]"><input v-model="rules[field.id].required" :aria-label="field.label + '必填'" type="checkbox" :disabled="!props.session.admin" /></td>
          <td v-else>—</td>
        </tr></tbody>
      </table></div>
    </template>
    <EmptyState v-else title="选择要查看的知识模板" />
  </section>
</template>
