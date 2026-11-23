<script setup>
import { computed,ref,watch } from 'vue'
import { ArrowRight, BookOpen, BookOpenCheck, CheckCheck, ClipboardCheck, CloudDownload, FileSearch, Files, FolderTree, LibraryBig, Plus, Upload } from '@lucide/vue'
import { api,list } from '../api'
import { date,modes,taskState } from '../labels'

const props=defineProps({subjectId:String,session:Object,directory:Array})
const emit=defineEmits(['navigate'])
const stats=ref({}), recent=ref([]), catalog=ref(null), queue=ref({}), tab=ref('raw'), busy=ref(true), issue=ref('')
const featured=ref(null)
const today=new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',month:'long',day:'numeric',weekday:'long'}).format(new Date())
const queues=[
  {key:'raw',label:'核验原文',view:'materials',action:'核验原文'},
  {key:'parse',label:'解析文献',view:'production',action:'开始解析'},
  {key:'review',label:'审核稿件',view:'knowledge',action:'审核稿件'},
]
const current=computed(()=>queues.find(q=>q.key===tab.value))
const scope=computed(()=>props.directory?.find(n=>n.id===props.subjectId)?.name || (props.subjectId==='unassigned'?'未关联学科':'全部专科'))
const metrics=[
  {key:'materials',title:'已采集原文',icon:Files,view:'materials',detail:'originals'},
  {key:'tasks',title:'采集任务',icon:CloudDownload,view:'tasks'},
  {key:'synced',title:'已同步知识',icon:CheckCheck,view:'knowledge',detail:'synced'},
]
function handleRow(row) {
  const entry=current.value
  emit('navigate',entry.view,entry.key==='parse'?'parse:'+row.id:row.id)
}
watch(()=>props.subjectId,async(subjectId,_,clean)=>{
  let active=true;clean(()=>{active=false});busy.value=true;issue.value=''
  stats.value={};queue.value={};recent.value=[];featured.value=null
  try {
    const [totals,tasks,raw,parse,review,state,published]=await Promise.all([
      list('/dashboard',{subjectId}),list('/tasks',{subjectId,size:3}),
      list('/materials',{subjectId,status:'PENDING',size:4}),list('/materials',{subjectId,status:'APPROVED',parsed:false,originalsOnly:true,size:4}),
      list('/knowledge',{subjectId,status:'PENDING',size:4}),api('/business-catalog'),list('/knowledge',{subjectId,status:'SYNCED',size:1})])
    if(active){stats.value=totals;recent.value=tasks.rows;queue.value={raw,parse,review};catalog.value=state
      tab.value=queues.find(q=>queue.value[q.key].total)?.key || 'raw'}
    if(active && published.rows.length) {
      const k=published.rows[0]
      const m=await api('/materials/'+k.materialId)
      if(active) featured.value={...k,material:m.material}
    }
  } catch(e){if(active) issue.value=e.message}finally{if(active) busy.value=false}
},{immediate:true})
</script>
<template>
  <section class="desk-heading desk-masthead"><div class="desk-intro"><div class="desk-context"><span class="context-dot"></span>{{ scope }}<i></i>{{ today }}</div><h1>文献采集工作台</h1>
    <div class="actions"><button v-if="session.admin" class="button primary" @click="emit('navigate','tasks','new')"><Plus :size="16"/>新建采集</button>
      <button v-if="session.admin" class="button mast-action" @click="emit('navigate','materials','upload')"><Upload :size="15"/>上传原文</button></div></div>
    <button v-if="featured" class="featured-paper" @click="emit('navigate','knowledge',featured.id)">
      <span class="paper-info"><small><span class="paper-dot"></span>最近同步的文献</small><strong>{{ featured.title }}</strong>
        <span>{{ featured.material.publisher || featured.material.sourceName || '业务文献' }}<ArrowRight :size="15"/></span></span>
      <span v-if="featured.material.formatCode==='PDF'" class="paper-cover"><img :src="'/api/materials/'+featured.materialId+'/pdf-preview/1'" alt="该文献的 PDF 首页" /></span>
    </button>
  </section>
  <p v-if="issue" class="message error" role="alert">{{ issue }}</p>
  <section class="desk-metrics" aria-label="文献处理统计"><button v-for="m in metrics" :key="m.key" @click="emit('navigate',m.view,m.detail)">
    <span class="desk-icon"><component :is="m.icon" :size="19"/></span><span><small>{{ m.title }}</small><strong>{{ stats[m.key] ?? '—' }}</strong></span><ArrowRight :size="15"/></button></section>
  <div class="desk-columns"><section class="desk-panel inbox"><header><div><ClipboardCheck :size="18"/><h2>待办文献</h2></div></header>
    <div class="inbox-tabs"><button v-for="q in queues" :key="q.key" :aria-pressed="q.key===tab" @click="tab=q.key">{{ q.label }}<b>{{ queue[q.key]?.total ?? '—' }}</b></button></div>
    <div v-if="busy" class="desk-empty" role="status">正在读取待办…</div>
    <button v-for="row in queue[tab]?.rows || []" :key="row.id" class="inbox-row" @click="handleRow(row)">
      <FileSearch :size="20"/><span><strong>{{ row.title }}</strong><small>{{ row.sourceName || row.materialTitle || row.formatCode }} · {{ date(row.capturedAt || row.updatedAt) }}</small></span><span class="row-action">{{ current.action }} <ArrowRight :size="13"/></span></button>
    <div v-if="!busy && !queue[tab]?.total && !issue" class="desk-empty"><BookOpenCheck :size="22"/>当前范围暂无{{ current.label }}待办</div>
    <button v-if="queue[tab]?.total>4" class="link desk-more" @click="emit('navigate',current.view,tab==='parse'?'ready':'pending')">查看全部 {{ queue[tab].total }} 条 <ArrowRight :size="14"/></button>
  </section>
  <section class="desk-panel masterdata"><header><div><FolderTree :size="18"/><h2>业务资料</h2></div><span>4.23</span></header>
    <div class="reference-counts"><div><strong>{{ catalog?.specialties ?? '—' }}</strong><small>专科</small></div><div><strong>{{ catalog?.diseases ?? '—' }}</strong><small>病种</small></div><div><strong>{{ catalog?.types ?? '—' }}</strong><small>业务入口</small></div></div>
    <p v-if="catalog?.error" class="message error">{{ catalog.error }}</p><small class="reference-time">{{ catalog?.syncedAt ? '上次同步 '+date(catalog.syncedAt,true) : '尚无业务资料同步记录' }}</small>
    <div class="reference-links"><button v-if="session.cdssConfigured" class="link" @click="emit('navigate','tasks','business')"><LibraryBig :size="14"/>检索业务文献 <ArrowRight :size="14"/></button>
      <button class="link" @click="emit('navigate','classifications')">分类与同步 <ArrowRight :size="14"/></button></div></section></div>
  <section class="desk-panel recent-work"><header><div><BookOpen :size="18"/><h2>最近采集任务</h2></div><button class="link" @click="emit('navigate','tasks')">全部任务 <ArrowRight :size="14"/></button></header>
    <button v-for="task in recent" :key="task.id" class="inbox-row" @click="emit('navigate','tasks',task.id)"><span class="task-mark"></span><span><strong>{{ task.name }}</strong><small>{{ modes[task.mode] }} · {{ date(task.createdAt,true) }}</small></span><span class="task-status">{{ taskState(task) }}</span></button>
    <p v-if="!busy && !recent.length" class="desk-empty">当前专科暂无采集任务</p></section>
</template>
