<script setup>
defineProps({ differences: { type: Array, required: true } })
</script>

<template>
  <div v-if="differences.length" class="table-wrap">
    <table class="difference-table">
      <thead><tr><th scope="col">字段</th><th scope="col">4.23 当前内容</th><th scope="col">审核稿拟更新内容</th></tr></thead>
      <tbody>
        <tr v-for="(item, index) in differences" :key="index">
          <th scope="row">{{ item.field }}</th><td>{{ item.before || '—' }}</td><td>{{ item.after || '—' }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.difference-table { table-layout: fixed; min-width: 560px; }
.difference-table th:first-child { width: 22%; }
.difference-table td { white-space: pre-wrap; overflow-wrap: anywhere; vertical-align: top; }
.difference-table th[scope='row'] { white-space: normal; vertical-align: top; }
</style>
