<script setup>
import { ref } from 'vue'
import { Upload } from '@lucide/vue'
import { send } from '../api'
import Drawer from './Drawer.vue'

const props = defineProps({ taskId: String })
const emit = defineEmits(['close','complete','notice'])
const files = ref([])
const task = ref(props.taskId || '')
const title = ref('')
const url = ref('')
const busy = ref(false)
const error = ref('')
const receipts = []
async function upload() {
  if (!files.value.length || files.value.length > 20) { error.value = '请选择 1 至 20 份原件'; return }
  busy.value = true
  error.value = ''
  try {
    if (!task.value) {
      const name = '手工上传：' + (title.value || files.value[0].name) + (files.value.length > 1 ? ' 等 ' + files.value.length + ' 份' : '')
      task.value = (await send('/tasks', { name: Array.from(name).slice(0,120).join(''), mode: 'UPLOAD', enabled: true,
        captureMode: 'SINGLE', resultLimit: 1, intervalMinutes: 0, scheduleMode: 'MANUAL' })).id
    }
    const failed = []
    for (const file of files.value) {
      const data = new FormData()
      data.append('file', file)
      data.append('taskId', task.value)
      if (files.value.length === 1 && !receipts.length && title.value) data.append('title',title.value)
      if (url.value) data.append('sourceUrl',url.value)
      try { receipts.push(await send('/materials/upload',data)) }
      catch (e) { failed.push(file); error.value += file.name + ' · ' + e.message + '\n' }
    }
    if (failed.length) { files.value = failed; return }
    const duplicate = receipts.filter(r => r.duplicate).length
    emit('notice','新增 ' + (receipts.length - duplicate) + ' 份，重复 ' + duplicate + ' 份')
    emit('complete', { taskId: task.value, receipts })
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}
</script>
<template>
  <Drawer title="手工上传" :busy="busy" @close="emit('close')">
    <form id="upload-form" class="form-grid" @submit.prevent="upload">
      <label class="file-picker"><Upload :size="26" /><strong>{{ files.length ? '已选择 ' + files.length + ' 份原件' : '选择原件文件' }}</strong>
        <span>PDF、DOCX、TXT、CSV、XLS、XLSX、HTML，每份最多 20 MB</span>
        <input type="file" multiple accept=".pdf,.docx,.txt,.csv,.xls,.xlsx,.htm,.html" :disabled="busy" @change="files = Array.from($event.target.files)" />
      </label>
      <label v-if="files.length <= 1">资料名称<input v-model.trim="title" maxlength="400" placeholder="留空时使用文件中的标题或文件名" /></label>
      <label>来源网址<input v-model.trim="url" type="url" maxlength="1000" placeholder="原文下载页或发布机构地址" /></label>
      <p v-if="error" role="alert" class="message error">{{ error }}</p>
    </form>
    <template #footer><button class="button secondary" :disabled="busy" @click="emit('close')">关闭</button>
      <button class="button primary" form="upload-form" :disabled="busy">{{ busy ? '正在上传…' : '上传入库' }}</button></template>
  </Drawer>
</template>
