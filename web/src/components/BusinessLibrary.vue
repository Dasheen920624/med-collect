<script setup>
import { onMounted,ref,computed } from 'vue'
import { ArrowLeft, ExternalLink, FileText, Search } from '@lucide/vue'
import { list,api,send } from '../api'
import Drawer from './Drawer.vue'
import Pagination from './Pagination.vue'
const props=defineProps({admin:Boolean,directory:Array})
const emit=defineEmits(['close','collected'])
const types={'17':'临床指南','30':'医学书籍','31':'医学期刊'}
const type=ref('31'), query=ref(''), cls=ref(''), rows=ref({rows:[],page:1,size:8,total:0}), selected=ref(null), attachment=ref('')
const loading=ref(false), busy=ref(false), error=ref('')
const subjects=computed(()=>(props.directory || []).filter(n=>n.groupCode==='SUBJECT' && n.enabled && n.remoteKey))
const className=code=>subjects.value.find(n=>n.businessCode===code)?.name || code || '未分子类'
let sequence=0
async function search(page=1) {
  const token=++sequence;loading.value=true;error.value='';selected.value=null
  try { const data=await list('/business-literature',{type:type.value,query:query.value,knowledgeClass:cls.value,page,size:8})
    if(token===sequence) rows.value=data
  } catch(e) {if(token===sequence) {rows.value={rows:[],page:1,size:8,total:0};error.value=e.message}}
  finally {if(token===sequence) loading.value=false}
}
async function open(item){
  loading.value=true;error.value=''
  try { selected.value=await api('/business-literature/'+item.id);attachment.value=selected.value.attachments[0]?.id || '' }
  catch(e){error.value=e.message}finally{loading.value=false}
}
async function collect(){
  busy.value=true;error.value=''
  try{const run=await send('/business-literature/'+selected.value.item.id+'/collect',{attachmentId:attachment.value});emit('collected',run)}
  catch(e){error.value=e.message}finally{busy.value=false}
}
onMounted(()=>search())
</script>
<template><Drawer title="从业务文献库选采" wide :busy="busy" @close="emit('close')">
  <template v-if="!selected">
    <form class="library-query" @submit.prevent="search()">
      <label>文献类型<select v-model="type" @change="search()"><option v-for="value in ['31','17','30']" :key="value" :value="value">{{ types[value] }}</option></select></label>
      <label>业务子类<select v-model="cls" @change="search()"><option value="">全部类别</option><option v-for="n in subjects" :key="n.id" :value="n.businessCode">{{ n.name }} · {{ n.businessCode }}</option></select></label>
      <label>文献标题<input v-model.trim="query" placeholder="输入标题关键词" maxlength="100" /></label><button class="button primary" :disabled="loading"><Search :size="15"/>查询</button>
    </form>
    <p v-if="loading" class="muted" role="status">正在读取 4.23 文献…</p>
    <template v-else><div class="library-total">{{ types[type] }} · {{ rows.total }} 篇</div>
      <button v-for="r in rows.rows" :key="r.id" class="library-row" @click="open(r)"><FileText :size="17"/>
        <span><strong>{{ r.title }}</strong><small>{{ className(r.knowledgeClass) }}<template v-if="r.source"> · {{ r.source }}</template></small></span><span class="link">查看</span></button>
      <p v-if="!rows.total && !error" class="muted">没有符合条件的文献。</p>
      <Pagination v-if="rows.total" :page="rows.page" :size="rows.size" :total="rows.total" @change="search" /></template>
  </template>
  <section v-else class="form-grid"><button class="link" @click="selected=null;error=''"><ArrowLeft :size="15"/>返回检索结果</button>
    <div><p class="muted">{{ types[selected.item.type] }} · {{ className(selected.item.knowledgeClass) }}</p><h2>{{ selected.item.title }}</h2></div>
    <div><h3>业务摘要</h3><p class="reading-text">{{ selected.summary || '业务记录未填写摘要' }}</p></div>
    <a :href="selected.item.businessUrl" target="_blank" rel="noopener">打开 4.23 知识记录 <ExternalLink :size="14"/></a>
    <a v-if="selected.publisherUrl" :href="selected.publisherUrl" target="_blank" rel="noopener">查看原始文献来源 <ExternalLink :size="14"/></a>
    <label>采集内容<select v-model="attachment"><option v-for="f in selected.attachments" :key="f.id" :value="f.id">{{ f.name }}</option><option value="">仅采集来源和摘要</option></select></label>
  </section>
  <p v-if="error" class="message error" role="alert">{{ error }}</p>
  <template v-if="selected && admin" #footer><button class="button primary" :disabled="busy" @click="collect">{{ busy?'正在创建任务…':'采集到原文库' }}</button></template>
</Drawer></template>
