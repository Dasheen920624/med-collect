<script setup>
import { ref, watch } from 'vue'
import { ArrowRight } from '@lucide/vue'
import { list } from '../api'
import { modes, scheduleText, taskState } from '../labels'
import EmptyState from '../components/EmptyState.vue'

const props = defineProps({ subjectId: String })
const emit = defineEmits(['navigate'])
const counts = ref({})
const recent = ref([])
const error = ref('')
const loading = ref(true)
const stages = [
  { title: '知识采集管理', text: '取得文件和网页正文', view: 'tasks', key: 'tasks', unit: '个任务' },
  { title: '文献原文管理', text: '查看原文、分类和来源', view: 'materials', detail: 'originals', key: 'materials', unit: '份原文' },
  { title: '静态知识解析', text: '按知识模板整理内容', view: 'production', detail: 'unparsed', key: 'unparsed', unit: '份未解析' },
  { title: '知识审核管理', text: '审核生成内容并同步至知识中心', view: 'knowledge', detail: 'pending', key: 'pendingKnowledge', unit: '条待审核' },
]
const settings = [
  { view: 'sources', name: '知识来源配置', text: '维护采集网址、授权和启用状态' },
  { view: 'classifications', name: '知识分类配置', text: '维护学科病种和资料分类' },
  { view: 'templates', name: '知识生成模板', text: '查看知识属性和内容结构，维护生成规则' },
]
watch(() => props.subjectId, async (subjectId, _, onCleanup) => {
  let active = true
  onCleanup(() => { active = false })
  loading.value = true
  counts.value = {}
  recent.value = []
  try {
    const [stats, tasks] = await Promise.all([list('/dashboard', { subjectId }), list('/tasks', { subjectId, size: 4 })])
    if (active) { counts.value = stats; recent.value = tasks.rows; error.value = '' }
  } catch (e) { if (active) error.value = e.message }
  finally { if (active) loading.value = false }
}, { immediate: true })
</script>

<template>
  <section class="page-head"><h1>知识采集概览</h1></section>
  <p v-if="error" role="alert" class="message error">{{ error }}</p>
  <section class="panel flow-panel">
    <h2>知识采集与处理</h2>
    <div class="flow-cards">
      <button v-for="(stage,i) in stages" :key="stage.view" class="flow-card" @click="emit('navigate',stage.view,stage.detail)">
        <span class="step-number">0{{ i + 1 }}</span><strong>{{ stage.title }}</strong><span class="flow-text">{{ stage.text }}</span>
        <span class="flow-count"><b>{{ counts[stage.key] ?? '—' }}</b> {{ stage.unit }}</span>
        <span class="flow-enter">进入 <ArrowRight :size="14" /></span>
      </button>
    </div>
  </section>
  <div class="overview-bottom">
    <section class="panel flow-panel">
      <div class="toolbar"><h2>最近采集任务</h2><button class="link" @click="emit('navigate','tasks')">全部任务 <ArrowRight :size="14" /></button></div>
      <div class="recent-label"><span>任务标题 / 采集方式</span><span>任务状态</span></div>
      <button v-for="task in recent" :key="task.id" class="recent-row" @click="emit('navigate','tasks',task.id)">
        <span><strong>{{ task.name }}</strong><small class="subline">{{ modes[task.mode] }} · {{ scheduleText(task) }}</small></span>
        <span>{{ taskState(task) }}</span>
      </button>
      <EmptyState v-if="!recent.length" :title="loading ? '正在读取任务' : '暂无匹配任务'" />
    </section>
    <section class="panel flow-panel"><h2>配置入口</h2>
      <button v-for="entry in settings" :key="entry.view" class="config-shortcut" @click="emit('navigate',entry.view)">
        <span><strong>{{ entry.name }}</strong><small class="subline">{{ entry.text }}</small></span><ArrowRight :size="15" />
      </button>
    </section>
  </div>
</template>
