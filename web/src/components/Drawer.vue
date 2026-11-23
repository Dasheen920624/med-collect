<script setup>
import { onMounted, ref } from 'vue'
import { X } from '@lucide/vue'

const props = defineProps({ title: String, subtitle: String, wide: Boolean, workbench: Boolean, busy: Boolean })
const emit = defineEmits(['close'])
const dialog = ref()
onMounted(() => dialog.value.showModal())
function close() {
  if (!props.busy) emit('close')
}
</script>

<template>
  <dialog ref="dialog" class="drawer" :class="{ wide, 'review-workbench': workbench }" :aria-label="title" @cancel.prevent="close">
    <header class="drawer-head">
      <div><h2>{{ title }}</h2><p v-if="subtitle">{{ subtitle }}</p></div>
      <button class="icon-button" type="button" aria-label="关闭" :disabled="busy" @click="close"><X :size="21" /></button>
    </header>
    <div class="drawer-scroll"><slot /></div>
    <footer v-if="$slots.footer" class="drawer-foot"><slot name="footer" /></footer>
  </dialog>
</template>
