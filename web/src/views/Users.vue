<script setup>
import { onMounted, ref } from 'vue'
import { Plus, RefreshCw, Search } from '@lucide/vue'
import { list, send } from '../api'
import { date } from '../labels'
import Drawer from '../components/Drawer.vue'
import EmptyState from '../components/EmptyState.vue'
import Pagination from '../components/Pagination.vue'
import StateBadge from '../components/StateBadge.vue'

const emit = defineEmits(['notice'])
const roles = { ADMIN: '管理员', REVIEWER: '审核员' }
const table = ref({ rows: [], total: 0, page: 1, size: 20 })
const query = ref('')
const form = ref(null)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const formError = ref('')

async function load(page = 1) {
  loading.value = true
  error.value = ''
  try {
    table.value = await list('/users', { query: query.value, page, size: 20 })
  } catch (e) { error.value = e.message }
  finally { loading.value = false }
}

function edit(account) {
  form.value = account ? { ...account, password: '' } : { username: '', password: '', role: 'REVIEWER' }
  formError.value = ''
}

async function save() {
  const { id, username, role, enabled, revision, password } = form.value
  formError.value = ''
  busy.value = true
  try {
    if (id) {
      await send('/users/' + id, { role, enabled, revision, password: password || undefined }, 'PUT')
    } else {
      await send('/users', { username, role, password })
    }
    form.value = null
    emit('notice', id ? '账号已更新' : '账号已创建')
    await load(id ? table.value.page : 1)
  } catch (e) { formError.value = e.message }
  finally { busy.value = false }
}

onMounted(() => load())
</script>

<template>
  <section class="page-head">
    <div><h1>用户管理</h1><p>添加账号、调整角色和状态，或重置密码。</p></div>
    <button class="button primary" @click="edit()"><Plus :size="18" /> 新建账号</button>
  </section>
  <p v-if="error" class="message error" role="alert">{{ error }}</p>
  <section class="panel">
    <div class="panel-head">
      <h2>用户列表</h2>
      <button class="icon-button" aria-label="刷新用户" :disabled="loading" @click="load(table.page)"><RefreshCw :size="17" /></button>
    </div>
    <form class="filters" @submit.prevent="load()">
      <label class="search"><Search :size="17" /><input v-model.trim="query" aria-label="搜索账号" maxlength="80" placeholder="搜索账号" /></label>
      <button class="button secondary" :disabled="loading">搜索</button>
    </form>
    <div v-if="table.rows.length" class="table-wrap">
      <table>
        <thead><tr><th>账号</th><th>角色</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead>
        <tbody><tr v-for="account in table.rows" :key="account.id">
          <td>{{ account.username }}</td><td>{{ roles[account.role] }}</td>
          <td><StateBadge :state="account.enabled ? 'ACTIVE' : 'OFF'" :labels="{ ACTIVE: '已启用', OFF: '已停用' }" /></td>
          <td>{{ date(account.createdAt) }}</td>
          <td><button class="link" @click="edit(account)">编辑</button></td>
        </tr></tbody>
      </table>
    </div>
    <EmptyState v-else :title="loading ? '正在读取用户' : '暂无匹配的用户'" />
    <Pagination v-bind="table" @change="load" />
  </section>

  <Drawer v-if="form" :title="form.id ? '编辑账号' : '新建账号'" :busy="busy" @close="form = null">
    <form id="user-form" class="form-grid" @submit.prevent="save">
      <label>账号<input v-model.trim="form.username" required maxlength="80" :readonly="Boolean(form.id)" autocomplete="off" /></label>
      <label>角色<select v-model="form.role"><option v-for="(label, role) in roles" :key="role" :value="role">{{ label }}</option></select></label>
      <label v-if="form.id" class="check"><input v-model="form.enabled" type="checkbox" /> 启用账号</label>
      <label>{{ form.id ? '新密码' : '密码' }}<input v-model="form.password" type="password" autocomplete="new-password"
        :required="!form.id" minlength="8" maxlength="72" :placeholder="form.id ? '留空保留原密码' : '至少 8 个字符'" /></label>
      <p v-if="formError" class="message error" role="alert">{{ formError }}</p>
    </form>
    <template #footer>
      <button class="button secondary" :disabled="busy" @click="form = null">取消</button>
      <button class="button primary" form="user-form" :disabled="busy">保存账号</button>
    </template>
  </Drawer>
</template>
