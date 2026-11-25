<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowDownToLine, Check, ChevronLeft, ChevronRight, CircleAlert, Clock3, FileSpreadsheet, LoaderCircle, RefreshCw, Square, X } from 'lucide-vue-next'
import { api, errorMessage, requestBlob, saveBlob } from '../api'
import type { Page, RowError, TaskDetail } from '../types'
import { dateTime, duration, number, progress, trapDialogKey } from '../utils'
import StatusBadge from './StatusBadge.vue'

const props = defineProps<{ taskId: string | null }>()
const emit = defineEmits<{ close: []; changed: [] }>()
const dialog = ref<HTMLElement | null>(null)
const detail = ref<TaskDetail | null>(null)
const errors = ref<Page<RowError>>({ items: [], total: 0, page: 0, size: 10 })
const errorPage = ref(0)
const loading = ref(false)
const loadError = ref('')
const rowsError = ref('')
const actionError = ref('')
const acting = ref(false)
const downloading = ref(false)
const confirmingCancel = ref(false)
let requestId = 0
let errorsRequestId = 0
let interval: ReturnType<typeof setInterval> | undefined
let previousFocus: HTMLElement | null = null
const active = computed(() => detail.value && ['QUEUED', 'RUNNING'].includes(detail.value.status))
const pages = computed(() => Math.max(1, Math.ceil(errors.value.total / errors.value.size)))
function close() { if (!acting.value) emit('close') }
function keydown(event: KeyboardEvent) { if (props.taskId) trapDialogKey(event, dialog.value, close) }

async function load(showLoading = false) {
  const id = props.taskId
  if (!id) return
  const current = ++requestId
  if (showLoading) loading.value = true
  try {
    const value = await api.task(id)
    if (current !== requestId || props.taskId !== id) return
    const oldCount = detail.value?.errorRows
    detail.value = value
    loadError.value = ''
    if (oldCount !== value.errorRows) void loadRows()
  } catch (cause) { if (current === requestId) loadError.value = errorMessage(cause) }
  finally { if (current === requestId) loading.value = false }
}
async function loadRows() {
  const id = props.taskId
  if (!id) return
  const current = ++errorsRequestId
  try {
    const value = await api.errors(id, errorPage.value)
    if (current === errorsRequestId && props.taskId === id) { errors.value = value; rowsError.value = '' }
  } catch (cause) { if (current === errorsRequestId) rowsError.value = errorMessage(cause) }
}
watch(errorPage, () => void loadRows())
watch(() => props.taskId, async id => {
  ++requestId; ++errorsRequestId
  clearInterval(interval)
  detail.value = null; errorPage.value = 0; errors.value = { items: [], total: 0, page: 0, size: 10 }
  loadError.value = ''; rowsError.value = ''; actionError.value = ''; confirmingCancel.value = false
  if (id) {
    previousFocus = document.activeElement as HTMLElement | null
    window.addEventListener('keydown', keydown)
    void load(true)
    interval = setInterval(() => { if (!document.hidden && !acting.value) void load() }, 2500)
    await nextTick(); dialog.value?.querySelector<HTMLButtonElement>('[aria-label="关闭任务详情"]')?.focus()
  } else {
    window.removeEventListener('keydown', keydown)
    previousFocus?.focus()
  }
})
onBeforeUnmount(() => { ++requestId; ++errorsRequestId; clearInterval(interval); window.removeEventListener('keydown', keydown) })

async function action(kind: 'retry' | 'cancel') {
  if (!props.taskId || acting.value) return
  acting.value = true; actionError.value = ''
  try {
    await api[kind](props.taskId)
    confirmingCancel.value = false
    await load()
    emit('changed')
  } catch (cause) { actionError.value = errorMessage(cause); void load() }
  finally { acting.value = false }
}
async function downloadErrors() {
  if (!props.taskId || downloading.value) return
  downloading.value = true; actionError.value = ''
  try { saveBlob(await requestBlob(`/tasks/${encodeURIComponent(props.taskId)}/errors.csv`), `错误明细-${detail.value?.name ?? props.taskId}.csv`) }
  catch (cause) { actionError.value = errorMessage(cause) }
  finally { downloading.value = false }
}
</script>

<template>
  <Teleport to="body"><div v-if="taskId" class="drawer-backdrop" @click.self="close"><aside ref="dialog" class="task-drawer" role="dialog" aria-modal="true" aria-labelledby="detail-title">
    <header class="drawer-header"><div><p class="eyebrow">TASK DETAILS</p><h2 id="detail-title">任务详情</h2></div><button class="icon-button" aria-label="关闭任务详情" :disabled="acting" @click="close"><X :size="21" /></button></header>
    <div v-if="loading && !detail" class="empty-state"><LoaderCircle :size="28" class="spin" /><p>正在读取任务详情…</p></div>
    <div class="drawer-body" v-else>
      <div v-if="loadError" class="alert error" role="alert"><CircleAlert :size="18" /><span>{{ loadError }}</span><button class="text-link" @click="load(true)">重试</button></div>
      <template v-if="detail">
        <div class="detail-task-title"><span class="file-symbol large"><FileSpreadsheet :size="25" /></span><div><h3>{{ detail.name }}</h3><p class="muted small break-word">{{ detail.filename }}</p></div><StatusBadge :status="detail.status" /></div>
        <div v-if="actionError" class="alert error" role="alert"><CircleAlert :size="18" /><span>{{ actionError }}</span></div>
        <section class="detail-progress-section"><div class="section-title"><h3>处理进度</h3><strong>{{ progress(detail.progress).toFixed(1) }}<small>%</small></strong></div><div class="progress-track large-track" role="progressbar" :aria-valuenow="progress(detail.progress)" aria-valuemin="0" aria-valuemax="100" aria-label="任务处理进度"><span :style="{ width: `${progress(detail.progress)}%` }" /></div><div class="progress-footer"><span>已处理 {{ number(detail.processedRows) }} / {{ number(detail.totalRows) }} 行</span><span>{{ duration(detail.startedAt, detail.finishedAt) }}</span></div><div class="detail-numbers"><div><span class="metric-marker success" /><span>成功入库</span><strong>{{ number(detail.successRows) }}</strong></div><div><span class="metric-marker warning" /><span>校验错误</span><strong :class="{ 'error-text': detail.errorRows > 0 }">{{ number(detail.errorRows) }}</strong></div><div><span class="metric-marker neutral" /><span>待处理</span><strong>{{ number(Math.max(0, detail.totalRows - detail.processedRows)) }}</strong></div></div></section>
        <div class="detail-meta"><div><span>任务 ID</span><code>{{ detail.id }}</code></div><div><span>创建时间</span><strong>{{ dateTime(detail.createdAt) }}</strong></div><div><span>开始时间</span><strong>{{ dateTime(detail.startedAt) }}</strong></div><div v-if="detail.finishedAt"><span>结束时间</span><strong>{{ dateTime(detail.finishedAt) }}</strong></div><div><span>批次大小</span><strong>{{ number(detail.batchSize) }} 行 / 批</strong></div><div><span>本轮尝试</span><strong>{{ detail.attempt }} / {{ detail.maxAttempts }}</strong></div><div><span>已提交检查点</span><strong>第 {{ number(detail.checkpoint) }} 条数据</strong></div><div><span>执行 Worker</span><code>{{ detail.workerId || '等待分配' }}</code></div><div><span>最近任务心跳</span><strong>{{ dateTime(detail.lastHeartbeatAt) }}</strong></div></div>
        <div v-if="detail.lastError" class="alert error"><CircleAlert :size="18" /><div><strong>最近执行异常</strong><p>{{ detail.lastError }}</p></div></div>
        <section class="detail-section"><div class="section-title"><h3>错误明细 <span class="count-tag">{{ number(detail.errorRows) }}</span></h3><button class="button secondary small-button" :disabled="!detail.errorRows || downloading" @click="downloadErrors"><LoaderCircle v-if="downloading" :size="14" class="spin" /><ArrowDownToLine v-else :size="14" />导出 CSV</button></div><p class="muted small section-description">行号按 CSV 逻辑记录计算，包含表头；带换行字段仍计为一条。</p><div v-if="rowsError" class="alert error" role="alert"><span>{{ rowsError }}</span><button class="text-link" @click="loadRows">重试</button></div><div v-else-if="errors.items.length" class="error-list"><div v-for="row in errors.items" :key="row.rowNumber" class="error-row"><span class="row-number">{{ row.rowNumber }}</span><div><strong>{{ row.sku || '无商品编码' }}</strong><p>{{ row.reason }}</p><details v-if="row.rawData"><summary>原始数据</summary><pre>{{ row.rawData }}</pre></details></div></div></div><div v-else class="compact-empty"><Check :size="19" /><span>{{ detail.processedRows ? '当前没有行校验错误' : '任务开始处理后，错误行将显示在这里' }}</span></div><div v-if="errors.total > errors.size" class="pagination slim"><span>第 {{ errorPage + 1 }} / {{ pages }} 页</span><div class="button-group"><button class="icon-button bordered" :disabled="errorPage === 0" aria-label="错误明细上一页" @click="errorPage--"><ChevronLeft :size="16" /></button><button class="icon-button bordered" :disabled="errorPage + 1 >= pages" aria-label="错误明细下一页" @click="errorPage++"><ChevronRight :size="16" /></button></div></div></section>
        <section class="detail-section"><div class="section-title"><h3>处理记录</h3><span class="muted small">最近 200 条</span></div><ol v-if="detail.events.length" class="event-timeline"><li v-for="event in detail.events" :key="event.id"><span class="event-node" :class="{ warning: /FAIL|ERROR|CANCEL/i.test(event.type) }" /><div><strong>{{ event.message }}</strong><span>{{ dateTime(event.createdAt) }}</span></div></li></ol><div v-else class="compact-empty"><Clock3 :size="18" />尚无处理记录</div></section>
      </template>
    </div>
    <footer v-if="detail" class="drawer-footer"><div v-if="confirmingCancel" class="cancel-confirm"><p>确认取消？已提交的数据会保留，后续批次将停止。</p><div class="button-group"><button class="button secondary small-button" :disabled="acting" @click="confirmingCancel = false">继续执行</button><button class="button danger small-button" :disabled="acting" @click="action('cancel')"><LoaderCircle v-if="acting" :size="14" class="spin" />确认取消任务</button></div></div><template v-else><span class="muted small">{{ active ? '处理进度每 2.5 秒自动更新' : '任务状态来自持久化记录' }}</span><button v-if="active" class="button secondary" :disabled="acting" @click="confirmingCancel = true"><Square :size="14" />取消任务</button><button v-else-if="detail.status === 'FAILED'" class="button primary" :disabled="acting" @click="action('retry')"><LoaderCircle v-if="acting" :size="15" class="spin" /><RefreshCw v-else :size="15" />从检查点重试</button><button v-else class="button secondary" @click="close">关闭详情</button></template></footer>
  </aside></div></Teleport>
</template>
