<script setup>
import { Clock3 } from '@lucide/vue'
import { actions, date } from '../labels'
defineProps({ events: Array })
</script>

<template>
  <section class="form-section">
    <h3><Clock3 :size="17" /> 操作记录</h3>
    <p v-if="!events?.length" class="muted">暂无记录</p>
    <ol v-else class="events">
      <li v-for="event in events" :key="event.id">
        <div><strong>{{ actions[event.action] || event.action }}</strong><span>{{ event.actor }} · {{ date(event.occurredAt, true) }}</span></div>
        <p v-if="event.note">{{ event.note }}</p>
      </li>
    </ol>
  </section>
</template>
