<script setup>
import { computed } from 'vue'
import { FileText } from '@lucide/vue'
import { original } from '../api'

const props = defineProps({ material: { type: Object, required: true } })
const pdf = computed(() => props.material.formatCode === 'PDF')
const name = computed(() => props.material.displayFileName || props.material.fileName || props.material.title)
</script>

<template>
  <a class="original-link" :href="original(material.id, pdf)" :target="pdf ? '_blank' : undefined"
    :title="name" rel="noopener noreferrer"><FileText :size="15" aria-hidden="true"/><span>{{ name }}</span></a>
</template>
