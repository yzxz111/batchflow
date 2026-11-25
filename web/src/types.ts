export type TaskStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'FAILED' | 'CANCELLED'
export interface Page<T> { items: T[]; total: number; page: number; size: number }
export interface Task {
  id: string; name: string; filename: string; status: TaskStatus
  totalRows: number; processedRows: number; successRows: number; errorRows: number
  progress: number; batchSize: number; attempt: number; maxAttempts: number; checkpoint: number
  workerId: string | null; createdAt: string; startedAt: string | null; finishedAt: string | null
  lastHeartbeatAt: string | null; lastError: string | null
}
export interface TaskEvent { id: number; type: string; message: string; createdAt: string }
export interface TaskDetail extends Task { events: TaskEvent[] }
export interface RowError { rowNumber: number; sku: string | null; reason: string; rawData: string }
export interface Product {
  id: string; sku: string; name: string; category: string; price: number; stock: number
  sourceTaskId: string; createdAt: string
}
export interface Worker { id: string; status: 'ONLINE' | 'OFFLINE'; lastSeenAt: string; currentTaskId: string | null }
export interface Overview {
  totalTasks: number; runningTasks: number; queuedTasks: number; completedTasks: number
  failedTasks: number; cancelledTasks: number; processedRows: number; successRows: number
  errorRows: number; productCount: number; queueMode: 'database' | 'rabbitmq'; workers: Worker[]
}
export interface Upload {
  uploadId: string; filename: string; headers: string[]; previewRows: Record<string, string>[]; totalRows: number
}
export type FieldName = 'sku' | 'name' | 'category' | 'price' | 'stock'
export type FieldMapping = Record<FieldName, string>
export interface CreateTask {
  name: string; uploadId: string; mapping: Partial<FieldMapping>; batchSize: number; maxAttempts: number
}
