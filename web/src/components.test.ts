// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import StatusBadge from './components/StatusBadge.vue'
import TaskTable from './components/TaskTable.vue'
import ImportWizard from './components/ImportWizard.vue'
import TaskDrawer from './components/TaskDrawer.vue'
import * as apiModule from './api'
import type { Task, TaskDetail } from './types'

function makeTask(overrides: Partial<Task> = {}): Task {
  return {
    id: 'task-1',
    name: '11 月商品导入',
    filename: 'products.csv',
    status: 'COMPLETED_WITH_ERRORS',
    totalRows: 100,
    processedRows: 100,
    successRows: 96,
    errorRows: 4,
    progress: 100,
    batchSize: 200,
    attempt: 1,
    maxAttempts: 3,
    checkpoint: 100,
    workerId: 'worker-abc',
    createdAt: '2026-11-25T08:00:00Z',
    startedAt: '2026-11-25T08:00:01Z',
    finishedAt: '2026-11-25T08:00:05Z',
    lastHeartbeatAt: '2026-11-25T08:00:05Z',
    lastError: null,
    ...overrides,
  }
}

describe('UI components', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('renders StatusBadge labels and status classes', () => {
    const cases = [
      ['QUEUED', '等待执行'],
      ['RUNNING', '处理中'],
      ['COMPLETED', '已完成'],
      ['COMPLETED_WITH_ERRORS', '完成 · 有错误'],
      ['FAILED', '执行失败'],
      ['CANCELLED', '已取消'],
    ] as const

    for (const [status, label] of cases) {
      const wrapper = mount(StatusBadge, { props: { status } })
      expect(wrapper.text()).toBe(label)
      expect(wrapper.classes()).toContain(status.toLowerCase())
    }
  })

  it('renders TaskTable empty, filtered empty, and row interactions', async () => {
    const emptyWrapper = mount(TaskTable, { props: { tasks: [] } })
    expect(emptyWrapper.text()).toContain('还没有导入任务')
    await emptyWrapper.get('button').trigger('click')
    expect(emptyWrapper.emitted('create')).toHaveLength(1)

    const filteredEmpty = mount(TaskTable, { props: { tasks: [], emptyTitle: '没有符合条件的任务' } })
    expect(filteredEmpty.text()).toContain('没有符合条件的任务')

    const task = makeTask()
    const tableWrapper = mount(TaskTable, { props: { tasks: [task] } })
    expect(tableWrapper.text()).toContain('11 月商品导入')
    expect(tableWrapper.text()).toContain('96')
    expect(tableWrapper.text()).toContain('4')
    await tableWrapper.get('button.task-name').trigger('click')
    expect(tableWrapper.emitted('open')).toEqual([['task-1']])
  })

  it('completes ImportWizard two-step flow with template upload, mapping, and idempotency key', async () => {
    vi.spyOn(apiModule, 'requestBlob').mockResolvedValue(
      new Blob(['sku,name,category,price,stock\nP001,鼠标,办公,59.9,10\n'], { type: 'text/csv' }),
    )
    vi.spyOn(apiModule.api, 'upload').mockResolvedValue({
      uploadId: 'up-1',
      filename: '商品导入示例.csv',
      headers: ['sku', 'name', 'category', 'price', 'stock'],
      previewRows: [{ sku: 'P001', name: '鼠标', category: '办公', price: '59.9', stock: '10' }],
      totalRows: 7,
    })
    const createdTask = makeTask({ id: 'task-new', status: 'QUEUED', processedRows: 0, successRows: 0, errorRows: 0 })
    const createSpy = vi.spyOn(apiModule.api, 'createTask').mockResolvedValue(createdTask)

    const wrapper = mount(ImportWizard, {
      props: { open: false },
      global: { stubs: { Teleport: true } },
    })
    await wrapper.setProps({ open: true })
    await flushPromises()

    const useExampleBtn = wrapper.findAll('button').find(b => b.text().includes('使用示例文件'))
    expect(useExampleBtn).toBeDefined()
    await useExampleBtn!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('准备就绪')
    expect(wrapper.text()).toContain('7 行数据 · 5 列')

    const nextBtn = wrapper.findAll('button').find(b => b.text().includes('下一步'))
    await nextBtn!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('已关联 5 / 5')
    await wrapper.get('#create-task-form').trigger('submit')
    await flushPromises()

    expect(createSpy).toHaveBeenCalledTimes(1)
    expect(createSpy.mock.calls[0]?.[0]).toMatchObject({
      name: '商品导入示例',
      uploadId: 'up-1',
      batchSize: 200,
      maxAttempts: 3,
    })
    expect(createSpy.mock.calls[0]?.[1]).toMatch(/^[0-9a-f-]{36}$/i)
    expect(wrapper.emitted('created')?.[0]).toEqual([createdTask])
  })

  it('supports cancel confirmation, failed retry, error pagination, and CSV download in TaskDrawer', async () => {
    const runningDetail: TaskDetail = {
      ...makeTask({ id: 't-run', status: 'RUNNING', processedRows: 40, successRows: 40, errorRows: 0, progress: 40 }),
      events: [{ id: 1, type: 'STARTED', message: '开始处理', createdAt: '2026-11-25T08:00:01Z' }],
    }
    const cancelledDetail: TaskDetail = {
      ...runningDetail,
      status: 'CANCELLED',
      events: [...runningDetail.events, { id: 2, type: 'CANCELLED', message: '任务已取消', createdAt: '2026-11-25T08:00:04Z' }],
    }
    const taskSpy = vi.spyOn(apiModule.api, 'task')
      .mockResolvedValueOnce(runningDetail)
      .mockResolvedValueOnce(cancelledDetail)
    const cancelSpy = vi.spyOn(apiModule.api, 'cancel').mockResolvedValue(cancelledDetail)
    const errorsSpy = vi.spyOn(apiModule.api, 'errors').mockResolvedValue({ items: [], total: 0, page: 0, size: 10 })

    const wrapper = mount(TaskDrawer, {
      props: { taskId: null },
      global: { stubs: { Teleport: true } },
    })
    await wrapper.setProps({ taskId: 't-run' })
    await flushPromises()

    const cancelBtn = wrapper.findAll('button').find(b => b.text().includes('取消任务'))
    await cancelBtn!.trigger('click')
    expect(wrapper.text()).toContain('确认取消？已提交的数据会保留')

    const confirmCancelBtn = wrapper.findAll('button').find(b => b.text().includes('确认取消任务'))
    await confirmCancelBtn!.trigger('click')
    await flushPromises()

    expect(cancelSpy).toHaveBeenCalledWith('t-run')
    expect(wrapper.emitted('changed')).toHaveLength(1)

    // Switch to FAILED task with errors and verify retry + error CSV export
    const failedDetail: TaskDetail = {
      ...makeTask({ id: 't-fail', status: 'FAILED', errorRows: 12, lastError: 'Worker 失联' }),
      events: [{ id: 1, type: 'FAILED', message: 'Worker 失联', createdAt: '2026-11-25T08:00:10Z' }],
    }
    taskSpy.mockResolvedValue(failedDetail)
    errorsSpy.mockResolvedValue({
      items: [{ rowNumber: 3, sku: 'BAD-1', reason: '价格必须大于 0', rawData: '{"sku":"BAD-1"}' }],
      total: 12,
      page: 0,
      size: 10,
    })
    const retrySpy = vi.spyOn(apiModule.api, 'retry').mockResolvedValue({ ...failedDetail, status: 'QUEUED' })
    const blobSpy = vi.spyOn(apiModule, 'requestBlob').mockResolvedValue(new Blob(['\uFEFF行号,商品编码\n']))
    const saveSpy = vi.spyOn(apiModule, 'saveBlob').mockImplementation(() => {})

    await wrapper.setProps({ taskId: 't-fail' })
    await flushPromises()

    expect(errorsSpy).toHaveBeenCalledWith('t-fail', 0)
    expect(wrapper.text()).toContain('价格必须大于 0')

    const exportBtn = wrapper.findAll('button').find(b => b.text().includes('导出 CSV'))
    await exportBtn!.trigger('click')
    await flushPromises()
    expect(blobSpy).toHaveBeenCalledWith('/tasks/t-fail/errors.csv')
    expect(saveSpy).toHaveBeenCalled()

    const retryBtn = wrapper.findAll('button').find(b => b.text().includes('从检查点重试'))
    await retryBtn!.trigger('click')
    await flushPromises()
    expect(retrySpy).toHaveBeenCalledWith('t-fail')
  })
})
