<script setup>
import { computed, ref, watch } from 'vue'
import { ChevronDown, ChevronRight, Folder } from '@lucide/vue'
const props = defineProps({ nodes: Array, selected: String, title: String, allLabel: String, unassigned: Boolean })
const emit = defineEmits(['select'])
const search = ref('')
const closed = ref([])
const parents = computed(() => (props.nodes || []).filter(n => !n.parentId))
const children = id => props.nodes.filter(n => n.parentId === id)
const matches = n => (n.name + ' ' + (n.businessCode || '')).toLowerCase().includes(search.value.trim().toLowerCase())
const visibleRoots = computed(() => parents.value.filter(n => matches(n) || children(n.id).some(matches)))
function toggle(id) { closed.value = closed.value.includes(id) ? closed.value.filter(i => i !== id) : [...closed.value,id] }
watch(() => [props.selected, props.nodes], () => {
  const parent = props.nodes?.find(n => n.id === props.selected)?.parentId
  if (parent) closed.value = closed.value.filter(id => id !== parent)
})
</script>
<template>
  <aside class="panel directory-tree">
    <div class="directory-head"><strong>{{ title }}</strong><slot><button class="link" @click="closed = closed.length ? [] : parents.map(n => n.id)">{{ closed.length ? '展开全部' : '收起全部' }}</button></slot></div>
    <input v-model.trim="search" type="search" :aria-label="'搜索' + title" placeholder="搜索目录" />
    <button class="tree-entry tree-root" :class="{chosen:!selected}" @click="emit('select','')"><Folder :size="15" />{{ allLabel || '全部目录' }}</button>
    <div v-for="root in visibleRoots" :key="root.id">
      <div class="tree-line">
        <button v-if="children(root.id).length" class="tree-toggle" :aria-label="(closed.includes(root.id) ? '展开' : '收起') + root.name"
          :aria-expanded="!closed.includes(root.id) || !!search" @click="toggle(root.id)"><component :is="closed.includes(root.id) && !search ? ChevronRight : ChevronDown" :size="14" /></button>
        <span v-else class="tree-spacer"></span>
        <button class="tree-entry" :class="{chosen:selected===root.id}" @click="emit('select',root.id)">{{ root.name }}<small>{{ root.materialCount }}</small></button>
      </div>
      <div v-if="search || !closed.includes(root.id)" class="tree-children"><button v-for="n in children(root.id).filter(n => matches(root) || matches(n))"
        :key="n.id" class="tree-entry" :class="{chosen:selected===n.id}" @click="emit('select',n.id)">{{ n.name }}<small>{{ n.materialCount }}</small></button></div>
    </div>
    <button v-if="unassigned" class="tree-entry" :class="{chosen:selected==='unassigned'}" @click="emit('select','unassigned')">未关联学科</button>
  </aside>
</template>
