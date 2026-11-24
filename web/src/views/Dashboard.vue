<script setup>
import { computed, ref, watch } from 'vue'
import { ArrowRight } from '@lucide/vue'
import { list } from '../api'
import { modes, scheduleText, taskState } from '../labels'

const props = defineProps({ subjectId: String, directory: Array })
const emit = defineEmits(['navigate'])
const stats = ref({}), recent = ref([]), error = ref(''), loading = ref(false)
const scope = computed(() => props.directory?.find(n => n.id === props.subjectId)?.name
  || (props.subjectId === 'unassigned' ? '未关联学科' : '全部专科'))
const steps = [
  ['知识采集管理','取得文件和网页正文','tasks','个任务','tasks',''],
  ['文献原文管理','查看原文、分类和来源','materials','份原文','materials','originals'],
  ['静态知识解析','按知识模板整理内容','unparsed','份未解析','production','unparsed'],
  ['知识审核管理','跟踪提交、审核与发布','pendingKnowledge','条待审核','knowledge','pending'],
]
const configuration = [
  ['知识来源配置','维护采集网址、授权和启用状态','sources'],
  ['知识分类配置','维护学科病种和资料分类','classifications'],
  ['知识生成模板','维护知识属性和内容结构','templates'],
]
const delivery = [
  ['审核通过待同步','readyToSync','ready'],['同步异常','syncFailed','failed'],
  ['相同已跳过','skipped','skipped'],['已写入 4.23','written','synced'],
]
watch(() => props.subjectId, async (subjectId, _, cleanup) => {
  let active = true
  cleanup(() => { active = false })
  loading.value = true; error.value = ''; stats.value = {}; recent.value = []
  try {
    const [summary, tasks] = await Promise.all([list('/dashboard',{subjectId}),list('/tasks',{subjectId,size:4})])
    if (active) { stats.value = summary; recent.value = tasks.rows }
  } catch (e) { if (active) error.value = e.message }
  finally { if (active) loading.value = false }
}, {immediate:true})
</script>
<template>
  <p class="scope-caption">{{ scope }}</p>
  <section class="page-head overview-heading"><h1>知识采集概览</h1></section>
  <p v-if="error" class="message error" role="alert">{{ error }}</p>
  <section class="desk-panel flow-panel">
    <header><h2>知识采集与处理</h2><span>{{ scope }}</span></header>
    <div class="flow-steps">
      <button v-for="([name,description,key,unit,view,detail],index) in steps" :key="view" @click="emit('navigate',view,detail)">
        <span class="flow-number" aria-hidden="true">{{ String(index + 1).padStart(2,'0') }}</span><strong>{{ name }}</strong><small>{{ description }}</small>
        <span class="flow-count"><b>{{ stats[key] ?? '—' }}</b> {{ unit }}</span><span class="flow-enter">进入 <ArrowRight :size="15"/></span>
      </button>
    </div>
  </section>
  <div class="desk-columns">
    <section class="desk-panel recent-work"><header><h2>最近采集任务</h2><button class="link" @click="emit('navigate','tasks')">全部任务 <ArrowRight :size="15"/></button></header>
      <div class="recent-label"><span>任务标题 / 采集方式</span><span>任务状态</span></div>
      <button v-for="task in recent" :key="task.id" class="recent-task" @click="emit('navigate','tasks',task.id)">
        <span><strong>{{ task.name }}</strong><small>{{ modes[task.mode] }} · {{ scheduleText(task) }}</small></span><span>{{ taskState(task) }}</span>
      </button>
      <p v-if="!recent.length" class="desk-empty">{{ loading ? '正在读取任务…' : '当前范围暂无采集任务' }}</p>
    </section>
    <section class="desk-panel config-shortcuts"><header><h2>配置入口</h2></header>
      <button v-for="[name,desc,view] in configuration" :key="view" @click="emit('navigate',view)">
        <span><strong>{{ name }}</strong><small>{{ desc }}</small></span><ArrowRight :size="15"/>
      </button>
    </section>
  </div>
  <section class="desk-panel"><header><h2>同步至 4.23</h2><span>知识管理</span></header>
    <div class="delivery-summary"><button v-for="[label,key,detail] in delivery" :key="key" @click="emit('navigate','knowledge',detail)">
      {{ label }} <b>{{ stats[key] ?? '—' }}</b></button></div>
  </section>
</template>
