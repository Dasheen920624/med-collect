<script setup>
import { computed, ref, watch } from 'vue'
import { Search, X } from '@lucide/vue'
import { api, list } from '../api'
const props=defineProps({ type:String, relation:String, label:String, modelValue:Array, editable:Boolean })
const emit=defineEmits(['update:modelValue'])
const term=ref(''), page=ref(1), open=ref(false), result=ref({rows:[],total:0}), known=ref({}), error=ref(''), reading=ref(false)
const selected=computed(()=>props.modelValue || [])
const base=computed(()=>'/business-catalog/relations/'+encodeURIComponent(props.type)+'/'+encodeURIComponent(props.relation))
const single=computed(()=>['countyUseId','deptCode','nurseIds'].includes(props.relation))
watch(()=>[base.value,selected.value.join(',')],async ([path],_,onCleanup)=>{
  let active=true; onCleanup(()=>{active=false})
  const missing=selected.value.filter(v=>!known.value[v])
  if(!missing.length) return
  try {
    const q=new URLSearchParams(); missing.forEach(v=>q.append('values',v))
    const items=await api(path+'/selected?'+q)
    if(active) known.value={...known.value,...Object.fromEntries(items.map(o=>[o.value,o]))}
  } catch(e) { if(active) error.value=e.message }
},{immediate:true})
watch([term,base],()=>{page.value=1})
watch([term,page,open,base],([q,p,isOpen,path],_,onCleanup)=>{
  let active=true
  if(!isOpen) return
  reading.value=true; error.value=''; result.value={rows:[],total:0}
  const timer=setTimeout(async()=>{
    try { const resp=await list(path,{query:q,page:p,size:8}); if(active) result.value=resp }
    catch(e){if(active) error.value=e.message} finally{if(active) reading.value=false}
  }, q ? 250 : 0)
  onCleanup(()=>{active=false;clearTimeout(timer)})
})
function choose(o) {
  known.value={...known.value,[o.value]:o}
  emit('update:modelValue',single.value?[o.value]:[...new Set([...selected.value,o.value])])
  open.value=false; term.value=''
}
</script>
<template>
  <section class="dictionary-input"><div class="field-top"><strong>{{ label }}</strong>
    <button v-if="editable" type="button" class="link" @click="open=!open">{{ open?'收起搜索':'从业务字典选择' }}</button></div>
    <div v-if="selected.length" class="selected-values"><span v-for="v in selected" :key="v">
      {{ known[v]?.name || v }} <small v-if="known[v]?.code">{{ known[v].code }}</small>
      <button v-if="editable" type="button" class="icon-button" :aria-label="'移除'+(known[v]?.name || v)" @click="emit('update:modelValue',selected.filter(i=>i!==v))"><X :size="13" /></button></span></div>
    <small v-else class="muted">尚未关联</small>
    <div v-if="open && editable" class="dict-search"><div class="search"><Search :size="15"/><input v-model.trim="term" :aria-label="'搜索'+label" placeholder="输入名称或编码" @keydown.enter.prevent /></div>
      <small v-if="reading">正在查询 4.23…</small>
      <button v-for="o in result.rows" :key="o.value" type="button" class="option-row" :disabled="selected.includes(o.value)" @click="choose(o)">
        <span>{{ o.name }} <small>{{ o.source }}</small></span><code>{{ o.code }}</code></button>
      <small v-if="!reading && !result.rows.length && !error">没有匹配条目</small>
      <div v-if="result.total>8" class="field-top"><button type="button" class="link" :disabled="page===1" @click="page--">上一页</button>
        <small>{{ page }} / {{ Math.ceil(result.total/8) }}</small><button type="button" class="link" :disabled="page*8>=result.total" @click="page++">下一页</button></div>
    </div><p v-if="error" role="alert" class="message error">{{ error }}</p>
  </section>
</template>
