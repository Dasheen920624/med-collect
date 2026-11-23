export const materialStates = { PENDING: '待审核', APPROVED: '已通过', RETURNED: '已退回' }
export const knowledgeStates = {
  DRAFT: '草稿', PENDING: '待审核', APPROVED: '待同步', RETURNED: '已退回',
  SYNCING: '同步中', SYNC_FAILED: '同步异常', SYNCED: '已同步',
}
export const runStates = { RUNNING: '执行中', SUCCEEDED: '已完成', PARTIAL: '部分完成', FAILED: '失败' }
export const modes = { URL: '指定网址下载', EUROPE_PMC: '文献检索', WEB: '全网搜索', UPLOAD: '手工上传', BUSINESS: '4.23 业务文献' }
export const resultStates = { CAPTURED: '原文已入库', DUPLICATE: '已有记录', FAILED: '获取失败',
  CANDIDATE: '搜索结果', METADATA: '仅来源和摘要' }

export const relations = {
  icd10Ids: '标准 ICD-10', diseaseIds: '疾病', drugCodes: '药品', itemIds: '检验或检查项目',
  deptCode: '科室', countyUseId: '是否县用', operationIds: '手术', treatmentIds: '治疗',
  nurseIds: '护理分类', ruleIds: '关联规则',
}
export const actions = {
  AI_REVIEW: 'AI 预审核', CLASSIFIED: '资料分类', CREATED: '创建', UPDATED: '更新', CAPTURED: '采集入库', PARSED: '模型解析', EDITED: '保存草稿',
  PENDING: '提交人工审核', APPROVED: '审核通过', RETURNED: '审核退回', SYNC_STARTED: '发起同步',
  SYNC_POSTING: '请求新增知识', SYNC_CREATED: '取得业务主键', SYNC_UPLOADED: '上传 PDF',
  SYNC_ATTACHED: '绑定 PDF', SYNC_REJECTED: '业务接口拒绝', SYNC_FAILED: '同步异常',
  SYNCED: '同步成功', SYNC_RESET: '复核后重新排队', SYNC_REVIEWED: '检查业务重复与差异',
  SYNC_REVIEW_STALE: '业务内容变化，待重新审核', SYNC_SKIPPED: '内容一致，跳过写入',
  SYNC_UPDATE_POSTING: '请求更新原业务记录', SYNC_UPDATED: '原业务记录已更新',
  REVISION_CREATED: '发起新版本草稿', ...runStates,
}

const localTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})
const fullTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})
export const date = (time, detailed = false) => time ? (detailed ? fullTime : localTime).format(time) : '—'
export const size = (bytes) => bytes < 1024 ? bytes + ' B' : bytes < 1048576
  ? (bytes / 1024).toFixed(1) + ' KB' : (bytes / 1048576).toFixed(1) + ' MB'
export function selection(groups) {
  return Object.entries(groups).map(([value, label]) => ({ value, label }))
}
export function scheduleText(task) {
  if (task.mode === 'UPLOAD') return '单次上传'
  if (task.scheduleMode === 'DAILY') return '每日 ' + task.dailyTime
  if (task.scheduleMode === 'ONCE') return '单次 · ' + date(task.scheduleAt)
  if (task.intervalMinutes) return '每 ' + task.intervalMinutes + ' 分钟'
  return '手动执行'
}
export const taskState = task => runStates[task.latestRun?.status] || '待执行'
export function dateRange(from, until) {
  return {
    fromTime: from ? Date.parse(from + 'T00:00:00+08:00') : null,
    untilTime: until ? Date.parse(until + 'T00:00:00+08:00') + 86400000 : null,
  }
}
export const objectId = (id) => encodeURIComponent(id)
