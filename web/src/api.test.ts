// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, api, errorMessage, requestBlob, requestJson, saveBlob } from './api'

describe('api client', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('throws NETWORK_ERROR when fetch rejects', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await expect(requestJson('/overview')).rejects.toMatchObject({
      name: 'ApiError',
      status: 0,
      code: 'NETWORK_ERROR',
      message: '暂时无法连接服务，请确认后端已启动后重试。',
    })
  })

  it('parses structured backend error message and code', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ message: '该幂等键已用于不同的导入请求', code: 'CONFLICT' }), {
        status: 409,
        headers: { 'Content-Type': 'application/json' },
      }),
    )

    await expect(requestJson('/tasks')).rejects.toMatchObject({
      status: 409,
      code: 'CONFLICT',
      message: '该幂等键已用于不同的导入请求',
    })
  })

  it('throws INVALID_RESPONSE when success body is not valid JSON', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('not-json', { status: 200 }))
    await expect(requestJson('/overview')).rejects.toMatchObject({
      status: 200,
      code: 'INVALID_RESPONSE',
    })
  })

  it('sends Idempotency-Key header on createTask and encodes query params', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify({ id: 't-1', items: [], total: 0, page: 0, size: 20 }), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    )

    await api.createTask(
      {
        name: '测试任务',
        uploadId: 'u-1',
        mapping: { sku: 'sku', name: 'name', price: 'price', stock: 'stock' },
        batchSize: 200,
        maxAttempts: 3,
      },
      'idem-key-123',
    )

    expect(fetchSpy).toHaveBeenCalledWith(
      '/api/tasks',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': 'idem-key-123' },
      }),
    )

    await api.tasks({ q: '鼠标', status: 'FAILED', page: 1, size: 10 })
    expect(fetchSpy).toHaveBeenLastCalledWith('/api/tasks?q=%E9%BC%A0%E6%A0%87&status=FAILED&page=1&size=10', {})

    await api.retry('task/1')
    expect(fetchSpy).toHaveBeenLastCalledWith('/api/tasks/task%2F1/retry', { method: 'POST' })

    await api.cancel('task/1')
    expect(fetchSpy).toHaveBeenLastCalledWith('/api/tasks/task%2F1/cancel', { method: 'POST' })
  })

  it('requests blob and handles download network failures', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(new Response('\uFEFFsku\n', { status: 200 }))
    const blob = await requestBlob('/templates/products.csv')
    expect(await blob.text()).toContain('sku')

    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new TypeError('offline'))
    await expect(requestBlob('/templates/products.csv')).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
      message: '下载失败，暂时无法连接服务。',
    })
  })

  it('triggers download anchor and revokes object URL in saveBlob', () => {
    vi.useFakeTimers()
    const createUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock-url')
    const revokeUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    saveBlob(new Blob(['hello']), 'errors.csv')

    expect(createUrl).toHaveBeenCalledTimes(1)
    expect(clickSpy).toHaveBeenCalledTimes(1)
    vi.advanceTimersByTime(1000)
    expect(revokeUrl).toHaveBeenCalledWith('blob:mock-url')
    vi.useRealTimers()
  })

  it('formats errorMessage fallback for unknown values', () => {
    expect(errorMessage(new ApiError('明确错误', 400))).toBe('明确错误')
    expect(errorMessage('unknown')).toBe('操作未完成，请重试。')
  })
})
