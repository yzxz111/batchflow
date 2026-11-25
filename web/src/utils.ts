import type { FieldMapping, FieldName, TaskStatus } from './types'

export const statusLabels: Record<TaskStatus, string> = {
  QUEUED: '等待执行', RUNNING: '处理中', COMPLETED: '已完成',
  COMPLETED_WITH_ERRORS: '完成 · 有错误', FAILED: '执行失败', CANCELLED: '已取消',
}
export const fieldDefinitions: { key: FieldName; label: string; required: boolean; hint: string }[] = [
  { key: 'sku', label: '商品编码', required: true, hint: '唯一编码，字母 / 数字 / . _ -' },
  { key: 'name', label: '商品名称', required: true, hint: '不超过 200 个字符' },
  { key: 'category', label: '商品分类', required: false, hint: '选填，不超过 100 个字符' },
  { key: 'price', label: '商品价格', required: true, hint: '大于 0，最多两位小数' },
  { key: 'stock', label: '商品库存', required: true, hint: '大于或等于 0 的整数' },
]
const aliases: Record<FieldName, string[]> = {
  sku: ['sku', 'productcode', 'productid', '商品编码', '商品编号', '编码', '货号'],
  name: ['name', 'productname', '商品名称', '名称'],
  category: ['category', 'type', '商品分类', '分类', '类别'],
  price: ['price', 'unitprice', '商品价格', '价格', '单价'],
  stock: ['stock', 'inventory', 'quantity', '商品库存', '库存', '库存数量'],
}
const normalize = (text: string) => text.trim().toLowerCase().replace(/[\s_-]/g, '')

export function guessMapping(headers: string[]): FieldMapping {
  const mapping: FieldMapping = { sku: '', name: '', category: '', price: '', stock: '' }
  for (const field of fieldDefinitions) mapping[field.key] = headers.find(header => aliases[field.key].includes(normalize(header))) ?? ''
  return mapping
}

export function mappingError(mapping: FieldMapping, headers: string[]): string | null {
  for (const field of fieldDefinitions) {
    const value = mapping[field.key]
    if (field.required && !value) return `请选择“${field.label}”对应的文件列。`
    if (value && !headers.includes(value)) return `“${field.label}”对应的文件列已失效，请重新选择。`
  }
  return null
}

export const number = (value: number | undefined | null) => value == null || !Number.isFinite(value) ? '—' : new Intl.NumberFormat('zh-CN').format(value)
export function dateTime(value: string | null | undefined, compact = false) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  return new Intl.DateTimeFormat('zh-CN', compact
    ? { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }
    : { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }
  ).format(date)
}
export function progress(value: number) { return Math.min(100, Math.max(0, Number.isFinite(value) ? value : 0)) }
export function duration(start: string | null, end: string | null) {
  if (!start) return '尚未开始'
  const seconds = Math.max(0, Math.floor(((end ? new Date(end) : new Date()).getTime() - new Date(start).getTime()) / 1000))
  if (seconds < 60) return `${seconds} 秒`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
  return `${Math.floor(seconds / 3600)} 小时 ${Math.floor(seconds % 3600 / 60)} 分`
}

export function trapDialogKey(event: KeyboardEvent, root: HTMLElement | null, close: () => void) {
  if (event.key === 'Escape') { close(); return }
  if (event.key !== 'Tab' || !root) return
  const elements = [...root.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex="0"]')]
    .filter(element => element.offsetParent !== null)
  if (!elements.length) { event.preventDefault(); return }
  const first = elements[0]
  const last = elements[elements.length - 1]
  if (event.shiftKey && (document.activeElement === first || !root.contains(document.activeElement))) {
    event.preventDefault(); last?.focus()
  } else if (!event.shiftKey && (document.activeElement === last || !root.contains(document.activeElement))) {
    event.preventDefault(); first?.focus()
  }
}
