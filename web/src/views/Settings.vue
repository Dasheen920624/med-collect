<script setup>
import { onMounted, ref } from 'vue'
import { Cable, Database, Sparkles } from '@lucide/vue'
import { api, send } from '../api'

const emit = defineEmits(['notice'])
const form = ref(null)
const issue = ref('')
const busy = ref('')
const modelResult = ref('')
const targetResult = ref('')

async function load() {
  const settings = await api('/settings')
  form.value = { ...settings, apiKey: '', clearKey: false }
}

async function save() {
  busy.value = 'save'
  issue.value = ''
  try {
    const { baseUrl, model, temperature, timeoutSeconds, apiKey, clearKey } = form.value
    const next = await send('/settings', { baseUrl, model, temperature, timeoutSeconds, apiKey, clearKey }, 'PUT')
    form.value = { ...next, apiKey: '', clearKey: false }
    modelResult.value = ''
    emit('notice', '模型配置已保存')
  } catch (e) { issue.value = e.message }
  finally { busy.value = '' }
}

async function test(target) {
  busy.value = target
  issue.value = ''
  if (target === 'model') modelResult.value = ''
  else targetResult.value = ''
  try {
    const result = await send('/settings/' + target + '-test')
    if (target === 'model') modelResult.value = result.message + ' · ' + result.model
    else targetResult.value = result.message + ' · ' + result.categories.length + ' 个业务类别入口'
  } catch (e) { issue.value = e.message }
  finally { busy.value = '' }
}

onMounted(async () => {
  try { await load() } catch (e) { issue.value = e.message }
})
</script>

<template>
  <section class="page-head"><div><h1>系统设置</h1><p>维护解析模型并检查业务系统连接。</p></div></section>
  <p v-if="issue" class="message error" role="alert">{{ issue }}</p>
  <div v-if="form" class="settings-grid">
    <section class="panel">
      <div class="panel-head"><h2><Sparkles :size="20" /> 大模型配置</h2><span class="badge blue">OpenAI 兼容</span></div>
      <form id="settings-form" class="form-grid panel-body" @submit.prevent="save">
        <label>API 地址<input v-model.trim="form.baseUrl" type="url" required maxlength="1000" placeholder="http://服务地址/v1" /></label>
        <label>模型名称<input v-model.trim="form.model" required maxlength="160" /></label>
        <label>API 密钥<input v-model="form.apiKey" type="password" autocomplete="new-password" maxlength="2000"
          :disabled="!form.keyEditable" :placeholder="form.keyConfigured ? '已配置，留空保留' : '输入网关的 API 密钥'" />
          <small v-if="!form.keyEditable">服务端当前通过环境变量管理密钥。</small></label>
        <label v-if="form.savedKey" class="check"><input v-model="form.clearKey" type="checkbox" /> 清除已保存的密钥，使用环境密钥</label>
        <div class="two-fields">
          <label>温度<input v-model.number="form.temperature" type="number" min="0" max="2" step=".1" required /></label>
          <label>超时（秒）<input v-model.number="form.timeoutSeconds" type="number" min="10" max="180" required /></label>
        </div>
        <p v-if="modelResult" class="message success" role="status">{{ modelResult }}</p>
        <div class="actions">
          <button class="button primary" :disabled="Boolean(busy)">保存模型配置</button>
          <button type="button" class="button secondary" :disabled="Boolean(busy)" @click="test('model')"><Cable :size="17" />
            {{ busy === 'model' ? '正在测试…' : '测试已保存配置' }}</button>
        </div>
      </form>
    </section>
    <section class="panel settings-connection">
      <div class="panel-head"><h2><Database :size="20" /> 知识管理连接</h2></div>
      <div class="panel-body form-grid">
        <dl class="metadata">
          <div><dt>业务账户</dt><dd>{{ form.cdssConfigured ? '已配置' : '待配置' }}</dd></div>
          <div><dt>同步开关</dt><dd>{{ form.cdssSyncEnabled ? '已启用' : '未启用' }}</dd></div>
          <div><dt>全网搜索</dt><dd>{{ form.webSearchEnabled ? 'SearXNG 已配置' : 'SearXNG 待配置' }}</dd></div>
        </dl>
        <button class="button secondary fit" :disabled="!form.cdssConfigured || Boolean(busy)" @click="test('cdss')">
          <Cable :size="17" /> {{ busy === 'cdss' ? '正在验证…' : '测试连接并刷新模板' }}
        </button>
        <p v-if="targetResult" class="message success" role="status">{{ targetResult }}</p>
      </div>
    </section>
  </div>
</template>
