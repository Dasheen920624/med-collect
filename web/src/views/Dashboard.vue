<script setup>
import { onMounted, ref } from 'vue'
import {
  Activity, BookOpenCheck, ClipboardCheck, FileArchive, GitPullRequest, ListChecks, Plus, Sparkles, Upload,
} from '@lucide/vue'
import { api } from '../api'
import { date, runStates } from '../labels'
import EmptyState from '../components/EmptyState.vue'
import StateBadge from '../components/StateBadge.vue'

defineProps({ session: Object })
const emit = defineEmits(['navigate'])
const counts = ref({})
const recent = ref([])
const tasks = ref({})
const error = ref('')
const loading = ref(true)

const metrics = [
  { key: 'tasks', label: '采集任务', icon: GitPullRequest, tone: 'blue', view: 'tasks' },
  { key: 'materials', label: '已获取原文', icon: FileArchive, tone: 'teal', view: 'materials', detail: 'originals' },
  { key: 'pendingKnowledge', label: '待审知识', icon: ClipboardCheck, tone: 'amber', view: 'knowledge', detail: 'pending' },
  { key: 'synced', label: '已同步知识', icon: BookOpenCheck, tone: 'green', view: 'knowledge', detail: 'synced' },
]
const stages = [
  { title: '采集任务', icon: GitPullRequest, text: '按网址、关键词或文件获取资料。', view: 'tasks' },
  { title: '原文审核', icon: FileArchive, text: '核对来源、原件和原文摘要。', view: 'materials', detail: 'pending' },
  { title: '知识解析', icon: Sparkles, text: '按 4.23 的知识模板整理原文。', view: 'production' },
  { title: '审核与同步', icon: ClipboardCheck, text: '审核稿件，查看业务系统回执。', view: 'knowledge' },
]

onMounted(async () => {
  try {
    const [dashboard, runs, taskRecords] = await Promise.all([
      api('/dashboard'), api('/runs?size=6'), api('/tasks?size=100'),
    ])
    counts.value = dashboard
    recent.value = runs.rows
    tasks.value = Object.fromEntries(taskRecords.rows.map(task => [task.id, task.name]))
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section class="page-head">
    <div><h1>采集概览</h1><p>跟踪文献获取、原文核验与知识入库。</p></div>
    <div v-if="session.admin" class="actions">
      <button class="button secondary" @click="emit('navigate', 'materials', 'upload')"><Upload :size="17" /> 上传原文</button>
      <button class="button primary" @click="emit('navigate', 'tasks', 'new')"><Plus :size="19" /> 新建采集任务</button>
    </div>
  </section>
  <p v-if="error" role="alert" class="message error">{{ error }}</p>
  <p v-if="counts.metadata" class="muted">另有 {{ counts.metadata }} 条检索记录包含来源和摘要。</p>
  <div class="metrics">
    <button v-for="metric in metrics" :key="metric.key" class="metric" @click="emit('navigate', metric.view, metric.detail)">
      <span class="metric-icon" :class="metric.tone"><component :is="metric.icon" :size="21" /></span>
      <span><span class="metric-label">{{ metric.label }}</span><strong>{{ loading ? '—' : counts[metric.key] ?? '—' }}</strong></span>
    </button>
  </div>
  <section class="panel dashboard-stage">
    <div class="panel-head"><h2><ListChecks :size="20" /> 采集与处理流程</h2></div>
    <div class="stages">
      <button v-for="stage in stages" :key="stage.title" class="stage" @click="emit('navigate', stage.view, stage.detail)">
        <div class="stage-icon"><component :is="stage.icon" :size="22" :stroke-width="1.75" /></div>
        <strong>{{ stage.title }}</strong><span>{{ stage.text }}</span>
      </button>
    </div>
  </section>
  <section class="panel">
    <div class="panel-head">
      <h2><Activity :size="20" /> 最近执行</h2>
      <button class="link" @click="emit('navigate', 'tasks', 'history')">查看执行记录</button>
    </div>
    <div v-if="recent.length" class="table-wrap">
      <table>
        <thead><tr><th>采集任务</th><th>执行时间</th><th>结果</th><th>执行状态</th></tr></thead>
        <tbody><tr v-for="run in recent" :key="run.id">
          <td><button class="link" @click="emit('navigate', 'tasks', 'run:' + run.id)">{{ tasks[run.taskId] || '采集任务' }}</button></td>
          <td>{{ date(run.startedAt) }}</td>
          <td>新增 {{ run.newCount }} · 重复 {{ run.duplicateCount }} · 失败 {{ run.failureCount }}</td>
          <td><StateBadge :state="run.status" :labels="runStates" /></td>
        </tr></tbody>
      </table>
    </div>
    <EmptyState v-else :title="loading ? '正在读取执行记录' : '暂无执行记录'" detail="执行采集任务后，可在这里查看结果。" />
  </section>
</template>
