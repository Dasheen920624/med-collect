<script setup>
import { ref, watch } from 'vue'
import { api } from '../api'
const props=defineProps({ dictionary:String, modelValue:String, editable:Boolean, label:String })
defineEmits(['update:modelValue'])
const options=ref([]), error=ref('')
watch(()=>props.dictionary,async(name,_,clean)=>{
  let active=true;clean(()=>{active=false});options.value=[];error.value=''
  if(!name) return
  try { const result=await api('/business-catalog/parameters/'+encodeURIComponent(name));if(active) options.value=result }
  catch(e) { if(active) error.value=e.message }
},{immediate:true})
</script>
<template><select :value="modelValue || ''" :aria-label="label" :disabled="!editable || !options.length" @change="$emit('update:modelValue',$event.target.value)">
  <option value="">请选择</option><option v-if="modelValue && !options.some(o=>o.value===modelValue)" :value="modelValue">{{ modelValue }}（历史值）</option>
  <option v-for="o in options" :key="o.value" :value="o.value">{{ o.name }} · {{ o.code }}</option></select>
  <small v-if="error" role="alert" class="muted">{{ error }}</small></template>
