<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  Activity, BookOpenCheck, Cross, ChevronDown, CloudDownload, CircleUserRound, LogOut, Menu, Settings, X,
  LayoutDashboard, Inbox, Files, ScanText, ClipboardCheck, Rss, FolderTree, ListTree,
} from '@lucide/vue'
import { api, login, logout, send } from './api'
import Dashboard from './views/Dashboard.vue'
import Tasks from './views/Tasks.vue'
import Sources from './views/Sources.vue'
import Materials from './views/Materials.vue'
import Knowledge from './views/Knowledge.vue'
import SettingsView from './views/Settings.vue'
import UsersView from './views/Users.vue'
import ClassificationsView from './views/Classifications.vue'
import TemplatesView from './views/Templates.vue'

const views = [
  { id: 'overview', name: '文献采集工作台', component: Dashboard },
  { id: 'tasks', name: '采集任务', component: Tasks },
  { id: 'materials', name: '文献原文库', component: Materials },
  { id: 'production', name: '文献解析', component: Materials },
  { id: 'knowledge', name: '审核与同步', component: Knowledge },
  { id: 'sources', name: '采集来源', component: Sources },
  { id: 'classifications', name: '业务分类', component: ClassificationsView },
  { id: 'templates', name: '解析模板', component: TemplatesView },
  { id: 'settings', name: '系统设置', component: SettingsView, admin: true },
  { id: 'users', name: '用户管理', component: UsersView, admin: true },
]
const navIcons={overview:LayoutDashboard,tasks:Inbox,materials:Files,production:ScanText,knowledge:ClipboardCheck,sources:Rss,classifications:FolderTree,templates:ListTree}
const session = ref()
const loading = ref(true)
const signingIn = ref(false)
const registering = ref(false)
const authError = ref('')
const authNotice = ref('')
const form = ref({ username: '', password: '', confirmation: '' })
const mobileMenu = ref(false)
const route = ref({ view: 'overview', detail: '' })
const toast = ref('')
let toastTimer
const collectionOpen = ref(true)
const settingsOpen = ref(false)
const directory = ref([])
const specialty = ref('')
const disease = ref('')
const scope = computed(() => disease.value || specialty.value)
const subjects = computed(() => directory.value.filter(node => node.groupCode === 'SUBJECT'))
const hasClinicalScope = computed(() => ['overview','tasks','materials','production','knowledge'].includes(route.value.view))
const reread = ref(0)
async function loadDirectory() {
  if (!session.value) return
  try {
    directory.value = await api('/classifications')
    if (scope.value && scope.value !== 'unassigned' && !subjects.value.some(n => n.id === scope.value)) selectScope('')
  } catch (e) { announce(e.message) }
}
function selectScope(id) {
  const node = subjects.value.find(n => n.id === id)
  specialty.value = node?.parentId || id || ''
  disease.value = node?.parentId ? id : ''
}
watch(session, (value) => {
  if (value) loadDirectory()
  else { directory.value = []; selectScope('') }
})
const selected = computed(() => views.find(item => item.id === route.value.view) || views[0])

function readRoute() {
  const [view = 'overview', detail = ''] = window.location.hash.slice(1).split('/')
  route.value = { view: views.some(item => item.id === view) ? view : 'overview', detail }
  if (selected.value.admin) settingsOpen.value = true
  else collectionOpen.value = true
  if (selected.value.admin && session.value && !session.value.admin) go('overview')
}

function go(view, detail = '') {
  const hash = view + (detail ? '/' + detail : '')
  if (window.location.hash.slice(1) === hash) reread.value++
  else window.location.hash = hash
  mobileMenu.value = false
}

function announce(message) {
  toast.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = '' }, 3800)
}

async function authenticate() {
  authError.value = ''
  authNotice.value = ''
  const { username, password, confirmation } = form.value
  if (registering.value && password !== confirmation) {
    authError.value = '两次输入的密码不一致'
    return
  }
  signingIn.value = true
  try {
    if (registering.value) {
      await send('/register', { username, password })
      switchAuth(false)
      authNotice.value = '账号已注册，请登录。'
    } else {
      session.value = await login(username, password)
      form.value.password = ''
      readRoute()
    }
  } catch (error) {
    authError.value = error.status === 401 ? '账号或密码不正确' : error.message
  } finally {
    signingIn.value = false
  }
}

function switchAuth(register) {
  registering.value = register
  form.value.password = ''
  form.value.confirmation = ''
  authError.value = ''
  authNotice.value = ''
}

function sessionExpired() {
  if (!session.value) return
  form.value.username = session.value.username
  session.value = null
  switchAuth(false)
  authNotice.value = '会话已结束，请重新登录。'
}

async function signOut() {
  try {
    await logout()
    session.value = null
    switchAuth(false)
  } catch (error) {
    announce(error.message)
  }
}

onMounted(async () => {
  window.addEventListener('hashchange', readRoute)
  window.addEventListener('medcollect:session-expired', sessionExpired)
  readRoute()
  try {
    session.value = await api('/session')
    readRoute()
  } catch (error) {
    if (error.status !== 401) authError.value = error.message
  } finally {
    loading.value = false
  }
})
onUnmounted(() => {
  window.removeEventListener('hashchange', readRoute)
  window.removeEventListener('medcollect:session-expired', sessionExpired)
  clearTimeout(toastTimer)
})
</script>

<template>
  <div v-if="loading" class="splash"><Activity :size="24" /> 正在连接采集工作台</div>
  <div v-else-if="!session" class="login-layout">
    <div class="login-art">
      <div class="brand"><div class="brand-symbol"><Cross :size="20" /></div> 医疗文献采集系统</div>
      <div class="login-intro">
        <div class="login-illustration" aria-hidden="true"><BookOpenCheck :size="77" :stroke-width="1.1" /></div>
        <h1>文献采集<br />与知识审核</h1>
        <p>在一个工作台完成文献采集、原文核验、结构化整理与业务同步。</p>
      </div>
    </div>
    <main class="login-main">
      <form class="login-form" @submit.prevent="authenticate">
        <h2>{{ registering ? '注册账号' : '登录工作台' }}</h2>
        <p class="muted">{{ registering ? '新账号注册后可用审核员身份登录。' : '使用采集系统账户继续。' }}</p>
        <label>账号<input v-model.trim="form.username" autocomplete="username" required maxlength="80" placeholder="输入账号" /></label>
        <label>密码<input v-model="form.password" type="password" :autocomplete="registering ? 'new-password' : 'current-password'"
          :minlength="registering ? 8 : undefined" maxlength="72" required :placeholder="registering ? '至少 8 个字符' : '输入密码'" /></label>
        <label v-if="registering">确认密码<input v-model="form.confirmation" type="password" autocomplete="new-password"
          maxlength="72" required placeholder="再次输入密码" /></label>
        <p v-if="authError" class="message error" role="alert">{{ authError }}</p>
        <p v-if="authNotice" class="message" role="status">{{ authNotice }}</p>
        <button class="button primary" :disabled="signingIn">{{ signingIn ? '正在提交…' : registering ? '注册' : '登录' }}</button>
        <button type="button" class="link fit" :disabled="signingIn" @click="switchAuth(!registering)">
          {{ registering ? '返回登录' : '注册账号' }}
        </button>
      </form>
    </main>
  </div>
  <div v-else class="shell">
    <aside class="sidebar" :class="{ 'is-open': mobileMenu }">
      <div class="brand">
        <div class="brand-symbol"><BookOpenCheck :size="20" /></div>
        <div><strong>医疗文献采集</strong></div>
      </div>

      <nav aria-label="主导航">
        <button class="nav-group" :aria-expanded="collectionOpen" @click="collectionOpen = !collectionOpen">
          <CloudDownload :size="17" /> 文献工作区 <ChevronDown :size="15" :class="{ folded: !collectionOpen }" />
        </button>
        <div v-show="collectionOpen" class="nav-children">
          <template v-for="item in views.filter(v=>!v.admin)" :key="item.id"><div v-if="item.id==='sources'" class="nav-divider">采集配置</div>
          <button :class="{ active: selected.id === item.id }"
            :aria-current="selected.id === item.id ? 'page' : undefined" @click="go(item.id)"><component :is="navIcons[item.id]" :size="16" />{{ item.name }}</button></template>
        </div>
        <template v-if="session.admin">
          <button class="nav-group admin-group" :aria-expanded="settingsOpen" @click="settingsOpen = !settingsOpen">
            <Settings :size="18" /> 系统管理 <ChevronDown :size="15" :class="{ folded: !settingsOpen }" />
          </button>
          <div v-show="settingsOpen" class="nav-children">
            <button v-for="item in views.filter(v => v.admin)" :key="item.id" :class="{ active: selected.id === item.id }"
              :aria-current="selected.id === item.id ? 'page' : undefined" @click="go(item.id)">{{ item.name }}</button>
          </div>
        </template>
      </nav>
    </aside>
    <button v-if="mobileMenu" class="mobile-backdrop" aria-label="关闭菜单" @click="mobileMenu = false"></button>
    <div class="workspace">
      <header class="topbar" :class="{ 'clinical-topbar': hasClinicalScope }">
        <button class="icon-button mobile-toggle" aria-label="打开菜单" @click="mobileMenu = !mobileMenu"><Menu :size="22" /></button>
        <button class="link home-crumb" @click="go('overview')">医疗文献采集</button><span class="crumb-slash">/</span>
        <template v-if="hasClinicalScope">
          <label class="context-field">专科<select aria-label="全局专科切换" :value="specialty" @change="selectScope($event.target.value)">
            <option value="">全部专科</option><option v-for="n in subjects.filter(n => !n.parentId && n.enabled)" :key="n.id" :value="n.id">{{ n.name }}</option>
            <option value="unassigned">未关联学科</option></select></label>
          <span class="crumb-slash">/</span>
          <label class="context-field">病种<select aria-label="全局病种切换" v-model="disease" :disabled="!subjects.some(n => n.parentId === specialty)">
            <option value="">全部病种</option><option v-for="n in subjects.filter(n => n.parentId === specialty && n.enabled)" :key="n.id" :value="n.id">{{ n.name }}</option></select></label>
        </template>
        <span v-else class="breadcrumb">{{ selected.name }}</span>
        <div class="top-account"><CircleUserRound :size="20" /><span>{{ session.username }}</span>
          <button class="icon-button" aria-label="退出登录" title="退出登录" @click="signOut"><LogOut :size="16" /></button></div>
      </header>
      <main class="main">
        <component :is="selected.component" :key="selected.id + reread" :session="session" :detail="route.detail"
          :parse-only="selected.id === 'production'" :directory="directory" :subject-id="scope"
          @scope="selectScope" @directory-changed="loadDirectory"
          @navigate="go" @notice="announce" />
      </main>
    </div>
  </div>
  <div v-if="toast" class="toast" role="status">{{ toast }}<button class="icon-button" aria-label="关闭提示" @click="toast = ''"><X :size="16" /></button></div>
</template>
