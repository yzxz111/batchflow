import type { CreateTask, Overview, Page, Product, RowError, Task, TaskDetail, Upload } from './types'

export class ApiError extends Error {
  constructor(message: string, public status: number, public code?: string) {
    super(message)
    this.name = 'ApiError'
  }
}

async function checkResponse(response: Response) {
  if (response.ok) return response
  let message = `请求失败（HTTP ${response.status}）`
  let code: string | undefined
  try {
    const body: unknown = await response.json()
    if (body && typeof body === 'object') {
      const error = body as Record<string, unknown>
      if (typeof error.message === 'string' && error.message.trim()) message = error.message
      if (typeof error.code === 'string') code = error.code
    }
  } catch { /* Keep an explicit HTTP error for non-JSON server responses. */ }
  throw new ApiError(message, response.status, code)
}

export async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(`/api${path}`, init)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError('暂时无法连接服务，请确认后端已启动后重试。', 0, 'NETWORK_ERROR')
  }
  await checkResponse(response)
  try { return await response.json() as T }
  catch { throw new ApiError('服务返回了无法识别的数据，请稍后重试。', response.status, 'INVALID_RESPONSE') }
}

export async function requestBlob(path: string): Promise<Blob> {
  let response: Response
  try { response = await fetch(`/api${path}`) }
  catch { throw new ApiError('下载失败，暂时无法连接服务。', 0, 'NETWORK_ERROR') }
  await checkResponse(response)
  return response.blob()
}

function query(params: Record<string, string | number | undefined>) {
  const values = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) if (value !== undefined && value !== '') values.set(key, String(value))
  return values.toString()
}

const post = <T>(path: string) => requestJson<T>(path, { method: 'POST' })
export const api = {
  overview: () => requestJson<Overview>('/overview'),
  tasks: (params: { q?: string; status?: string; page?: number; size?: number } = {}) => requestJson<Page<Task>>(`/tasks?${query(params)}`),
  task: (id: string) => requestJson<TaskDetail>(`/tasks/${encodeURIComponent(id)}`),
  errors: (id: string, page = 0, size = 10) => requestJson<Page<RowError>>(`/tasks/${encodeURIComponent(id)}/errors?${query({ page, size })}`),
  products: (q = '', page = 0, size = 20) => requestJson<Page<Product>>(`/products?${query({ q, page, size })}`),
  upload: (file: File) => {
    const data = new FormData()
    data.append('file', file)
    return requestJson<Upload>('/uploads', { method: 'POST', body: data })
  },
  createTask: (body: CreateTask, idempotencyKey: string) => requestJson<Task>('/tasks', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(body),
  }),
  retry: (id: string) => post<Task>(`/tasks/${encodeURIComponent(id)}/retry`),
  cancel: (id: string) => post<Task>(`/tasks/${encodeURIComponent(id)}/cancel`),
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '操作未完成，请重试。'
}

export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
