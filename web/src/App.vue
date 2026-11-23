<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  Activity, BookOpenCheck, ClipboardCheck, Cross, Database, FileArchive, GitPullRequest,
  LayoutDashboard, LogOut, Menu, Settings, Users, X, ListTree, Sparkles, FileText,
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
  { id: 'overview', name: '知识采集概览', icon: LayoutDashboard, component: Dashboard },
  { id: 'tasks', name: '知识采集管理', icon: GitPullRequest, component: Tasks },
  { id: 'materials', name: '文献原文管理', icon: FileArchive, component: Materials },
  { id: 'production', name: '静态知识解析', icon: Sparkles, component: Materials },
  { id: 'knowledge', name: '知识审核管理', icon: ClipboardCheck, component: Knowledge },
  { id: 'sources', name: '知识来源配置', icon: Database, component: Sources },
  { id: 'classifications', name: '知识分类配置', icon: ListTree, component: ClassificationsView },
  { id: 'templates', name: '知识生成模板', icon: FileText, component: TemplatesView },
  { id: 'settings', name: '系统设置', icon: Settings, component: SettingsView, admin: true },
  { id: 'users', name: '用户管理', icon: Users, component: UsersView, admin: true },
]
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
const selected = computed(() => views.find(item => item.id === route.value.view) || views[0])

function readRoute() {
  const [view = 'overview', detail = ''] = window.location.hash.slice(1).split('/')
  route.value = { view: views.some(item => item.id === view) ? view : 'overview', detail }
  if (selected.value.admin && session.value && !session.value.admin) go('overview')
}

function go(view, detail = '') {
  window.location.hash = view + (detail ? '/' + detail : '')
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
        <div class="brand-symbol"><Cross :size="20" /></div>
        <div><strong>医疗文献采集</strong><span>MEDCOLLECT</span></div>
      </div>
      <div class="side-section">工作台</div>
      <nav aria-label="主导航">
        <button v-for="item in views.filter(item => !item.admin || session.admin)" :key="item.id"
          :class="{ active: selected.id === item.id }" :aria-current="selected.id === item.id ? 'page' : undefined"
          @click="go(item.id)">
          <component :is="item.icon" :size="19" :stroke-width="1.8" /><span>{{ item.name }}</span>
        </button>
      </nav>
      <div class="sidebar-bottom">
        <div class="side-avatar"><span>{{ Array.from(session.username)[0].toUpperCase() }}</span></div>
        <div class="side-user"><strong>{{ session.username }}</strong><small>{{ session.admin ? '系统管理员' : '知识审核员' }}</small></div>
        <button class="icon-button" aria-label="退出登录" title="退出登录" @click="signOut"><LogOut :size="17" /></button>
      </div>
    </aside>
    <button v-if="mobileMenu" class="mobile-backdrop" aria-label="关闭菜单" @click="mobileMenu = false"></button>
    <div class="workspace">
      <header class="topbar">
        <button class="icon-button mobile-toggle" aria-label="打开菜单" @click="mobileMenu = !mobileMenu"><Menu :size="22" /></button>
        <span class="breadcrumb">知识自动采集 <span>/</span> <strong>{{ selected.name }}</strong></span>
      </header>
      <main class="main">
        <component :is="selected.component" :key="selected.id" :session="session" :detail="route.detail"
          :parse-only="selected.id === 'production'"
          @navigate="go" @notice="announce" />
      </main>
    </div>
  </div>
  <div v-if="toast" class="toast" role="status">{{ toast }}<button class="icon-button" aria-label="关闭提示" @click="toast = ''"><X :size="16" /></button></div>
</template>
