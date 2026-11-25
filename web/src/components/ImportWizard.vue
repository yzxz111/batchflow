<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, Check, CircleAlert, FileSpreadsheet, LoaderCircle, UploadCloud, X } from 'lucide-vue-next'
import { api, errorMessage, requestBlob, saveBlob } from '../api'
import type { CreateTask, FieldMapping, Task, Upload } from '../types'
import { fieldDefinitions, guessMapping, mappingError, number, trapDialogKey } from '../utils'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; created: [task: Task] }>()
const dialog = ref<HTMLElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const step = ref(1)
const upload = ref<Upload | null>(null)
const uploading = ref(false)
const submitting = ref(false)
const dragging = ref(false)
const error = ref('')
const name = ref('')
const mapping = ref<FieldMapping>({ sku: '', name: '', category: '', price: '', stock: '' })
const batchSize = ref(200)
const maxAttempts = ref(3)
let generation = 0
let previousFocus: HTMLElement | null = null
let lastFingerprint = ''
let submissionKey = ''
const busy = computed(() => uploading.value || submitting.value)
const mappedCount = computed(() => fieldDefinitions.filter(field => mapping.value[field.key]).length)

function close() { if (!busy.value) emit('close') }
function keydown(event: KeyboardEvent) { if (props.open) trapDialogKey(event, dialog.value, close) }
watch(() => props.open, async open => {
  generation++
  if (open) {
    previousFocus = document.activeElement as HTMLElement | null
    step.value = 1; upload.value = null; error.value = ''; name.value = ''
    uploading.value = false; submitting.value = false; dragging.value = false
    batchSize.value = 200; maxAttempts.value = 3
    lastFingerprint = ''; submissionKey = ''
    window.addEventListener('keydown', keydown)
    await nextTick(); dialog.value?.querySelector<HTMLButtonElement>('[aria-label="关闭导入窗口"]')?.focus()
  } else {
    window.removeEventListener('keydown', keydown)
    previousFocus?.focus()
  }
})
onBeforeUnmount(() => { generation++; window.removeEventListener('keydown', keydown) })

async function uploadFile(file: File | undefined) {
  if (!file || busy.value) return
  error.value = ''
  if (!/\.csv$/i.test(file.name)) { error.value = '请选择 .csv 格式的文件。'; return }
  if (file.size > 50 * 1024 * 1024) { error.value = '文件超过 50 MiB，请拆分后上传。'; return }
  if (!file.size) { error.value = '文件是空的，请选择包含数据的 CSV。'; return }
  const current = generation
  uploading.value = true
  try {
    const result = await api.upload(file)
    if (current !== generation) return
    upload.value = result
    mapping.value = guessMapping(result.headers)
    name.value = file.name.replace(/\.csv$/i, '')
    lastFingerprint = ''; submissionKey = ''
  } catch (cause) { if (current === generation) error.value = errorMessage(cause) }
  finally { if (current === generation) uploading.value = false }
}
function fileChanged(event: Event) {
  const input = event.target as HTMLInputElement
  void uploadFile(input.files?.[0])
  input.value = ''
}
function dropFile(event: DragEvent) {
  dragging.value = false
  if (event.dataTransfer?.files.length !== 1) { error.value = '每次请选择一个 CSV 文件。'; return }
  void uploadFile(event.dataTransfer.files[0])
}
async function useExample() {
  if (busy.value) return
  error.value = ''
  const current = generation
  uploading.value = true
  let file: File | undefined
  try { file = new File([await requestBlob('/templates/products.csv')], '商品导入示例.csv', { type: 'text/csv' }) }
  catch (cause) { if (current === generation) error.value = errorMessage(cause) }
  finally { if (current === generation) uploading.value = false }
  if (file && current === generation) await uploadFile(file)
}
async function downloadTemplate() {
  try { saveBlob(await requestBlob('/templates/products.csv'), '商品导入示例.csv') }
  catch (cause) { error.value = errorMessage(cause) }
}
function next() {
  if (!upload.value) return
  error.value = ''
  step.value = 2
  void nextTick(() => document.getElementById('task-name')?.focus())
}
async function submit() {
  if (!upload.value || busy.value) return
  error.value = mappingError(mapping.value, upload.value.headers) ?? ''
  if (error.value) return
  if (!name.value.trim()) { error.value = '请填写任务名称。'; return }
  if (!Number.isInteger(batchSize.value) || batchSize.value < 10 || batchSize.value > 1000) { error.value = '批次大小需为 10～1000 的整数。'; return }
  if (!Number.isInteger(maxAttempts.value) || maxAttempts.value < 1 || maxAttempts.value > 5) { error.value = '最大尝试次数需为 1～5 的整数。'; return }
  const payload: CreateTask = {
    name: name.value.trim(), uploadId: upload.value.uploadId,
    mapping: Object.fromEntries(Object.entries(mapping.value).filter(([, value]) => value !== '')),
    batchSize: batchSize.value, maxAttempts: maxAttempts.value,
  }
  const fingerprint = JSON.stringify(payload)
  if (fingerprint !== lastFingerprint) { submissionKey = crypto.randomUUID(); lastFingerprint = fingerprint }
  const current = generation
  submitting.value = true
  try {
    const task = await api.createTask(payload, submissionKey)
    if (current === generation) emit('created', task)
  } catch (cause) { if (current === generation) error.value = errorMessage(cause) }
  finally { if (current === generation) submitting.value = false }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="modal-backdrop" @click.self="close">
      <section ref="dialog" class="import-dialog" role="dialog" aria-modal="true" aria-labelledby="import-title" :aria-busy="busy">
        <header class="dialog-header"><div><p class="eyebrow">NEW IMPORT</p><h2 id="import-title">创建导入任务</h2><p class="muted">把文件交给 BatchFlow，追踪每一批数据的处理结果。</p></div><button class="icon-button" aria-label="关闭导入窗口" :disabled="busy" @click="close"><X :size="21" /></button></header>
        <div class="wizard-steps"><span :class="{ active: step === 1, done: step > 1 }"><i><Check v-if="step > 1" :size="14" /><template v-else>1</template></i>上传与预览</span><div /><span :class="{ active: step === 2 }"><i>2</i>字段映射与配置</span></div>
        <div class="dialog-body">
          <div v-if="error" class="alert error" role="alert"><CircleAlert :size="18" /><span>{{ error }}</span></div>
          <template v-if="step === 1">
            <input ref="fileInput" class="sr-only" type="file" accept=".csv,text/csv" aria-label="选择 CSV 文件" @change="fileChanged" :disabled="busy" />
            <button type="button" class="upload-zone" :class="{ dragging, busy }" :disabled="busy" @click="fileInput?.click()" @dragover.prevent="dragging = true" @dragleave.prevent="dragging = false" @drop.prevent="dropFile">
              <span class="upload-symbol"><LoaderCircle v-if="uploading" :size="29" class="spin" /><UploadCloud v-else :size="29" /></span>
              <strong>{{ uploading ? '正在上传并读取文件…' : upload ? '点击或拖拽，重新选择文件' : '点击选择文件，或将文件拖到这里' }}</strong>
              <span>UTF-8 编码的 CSV · 最大 50 MiB · 最多 100 万行</span>
            </button>
            <div class="template-line"><span>第一次使用？<button class="text-link" type="button" :disabled="busy" @click="downloadTemplate">下载示例 CSV</button></span><button class="text-link" type="button" :disabled="busy" @click="useExample">使用示例文件 <ArrowRight :size="14" /></button></div>
            <div v-if="upload" class="upload-preview"><div class="preview-file"><span class="file-symbol"><FileSpreadsheet :size="21" /></span><div><strong>{{ upload.filename }}</strong><p class="small muted">{{ number(upload.totalRows) }} 行数据 · {{ upload.headers.length }} 列 · 已读取</p></div><span class="success-chip"><Check :size="14" />准备就绪</span></div><h3 class="subsection-label">数据预览 <span class="muted">前 {{ Math.min(upload.previewRows.length, 5) }} 行</span></h3><div class="preview-scroll"><table class="data-table preview-table"><thead><tr><th v-for="header in upload.headers" :key="header">{{ header }}</th></tr></thead><tbody><tr v-for="(row, index) in upload.previewRows.slice(0, 5)" :key="index"><td v-for="header in upload.headers" :key="header">{{ row[header] || '—' }}</td></tr></tbody></table></div></div>
            <div v-else class="quiet-note"><FileSpreadsheet :size="17" /><p>支持带引号的字段、字段内换行和 UTF-8 BOM。上传后可预览数据，再确认字段映射。</p></div>
          </template>
          <form v-else id="create-task-form" @submit.prevent="submit">
            <div class="form-group"><label for="task-name">任务名称 <span class="required">*</span></label><input id="task-name" v-model="name" maxlength="100" placeholder="例如：11 月商品数据导入" required :disabled="submitting" /></div>
            <div class="mapping-heading"><div><h3>字段映射</h3><p class="muted small">将文件列关联到商品字段，带 * 为必填。</p></div><span class="muted small">已关联 {{ mappedCount }} / 5</span></div>
            <div class="mapping-table"><div class="mapping-table-head"><span>目标字段</span><span>文件中的列</span></div><div v-for="field in fieldDefinitions" :key="field.key" class="mapping-row"><div><label :for="`map-${field.key}`">{{ field.label }} <span v-if="field.required" class="required">*</span><code>{{ field.key }}</code></label><p>{{ field.hint }}</p></div><select :id="`map-${field.key}`" v-model="mapping[field.key]" :required="field.required" :disabled="submitting"><option value="">{{ field.required ? '请选择对应列' : '不导入此字段' }}</option><option v-for="header in upload?.headers" :key="header" :value="header">{{ header }}</option></select></div></div>
            <div class="form-grid"><div class="form-group"><label for="batch-size">每批处理行数</label><input id="batch-size" v-model.number="batchSize" type="number" min="10" max="1000" step="1" required :disabled="submitting" /><p class="field-hint">10～1000 行；每批独立提交检查点。</p></div><div class="form-group"><label for="max-attempts">最大尝试次数</label><select id="max-attempts" v-model.number="maxAttempts" :disabled="submitting"><option v-for="n in 5" :key="n" :value="n">{{ n }} 次</option></select><p class="field-hint">任务异常后自动重试，行校验错误不触发重试。</p></div></div>
            <div class="quiet-note"><Check :size="17" /><p>重复商品编码会记入错误明细，已存在的商品不会被覆盖。提交后可在任务详情查看进度与处理记录。</p></div>
          </form>
        </div>
        <footer class="dialog-footer"><span class="muted small" v-if="upload">{{ upload.filename }} · {{ number(upload.totalRows) }} 行</span><span v-else class="muted small">文件保存在本项目的数据目录</span><div class="button-group"><button v-if="step === 2" class="button secondary" :disabled="busy" @click="step = 1; error = ''"><ArrowLeft :size="15" />上一步</button><button v-else class="button secondary" :disabled="busy" @click="close">取消</button><button v-if="step === 1" class="button primary" :disabled="!upload || busy" @click="next">下一步<ArrowRight :size="16" /></button><button v-else class="button primary" form="create-task-form" type="submit" :disabled="busy"><LoaderCircle v-if="submitting" :size="16" class="spin" /><template v-else><Check :size="16" /></template>{{ submitting ? '正在提交…' : '提交导入任务' }}</button></div></footer>
      </section>
    </div>
  </Teleport>
</template>
