<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  Activity,
  CircleAlert,
  Database,
  FilePlus2,
  Layers,
  LoaderCircle,
  Package,
  RefreshCw,
  Search,
  Server,
} from 'lucide-vue-next'
import { api, errorMessage } from './api'
import type { Overview, Page, Product, Task } from './types'
import { dateTime, number, statusLabels } from './utils'
import ImportWizard from './components/ImportWizard.vue'
import StatusBadge from './components/StatusBadge.vue'
import TaskDrawer from './components/TaskDrawer.vue'
import TaskTable from './components/TaskTable.vue'

type Tab = 'overview' | 'tasks' | 'products'

const activeTab = ref<Tab>('overview')
const wizardOpen = ref(false)
const selectedTaskId = ref<string | null>(null)

const overview = ref<Overview | null>(null)
const recentTasks = ref<Task[]>([])
const taskPage = ref<Page<Task>>({ items: [], total: 0, page: 0, size: 20 })
const productPage = ref<Page<Product>>({ items: [], total: 0, page: 0, size: 20 })

const loadingOverview = ref(true)
const loadingTasks = ref(true)
const loadingProducts = ref(true)

const overviewError = ref('')
const tasksError = ref('')
const productsError = ref('')
const globalError = computed(() =>
  [...new Set([overviewError.value, tasksError.value, productsError.value].filter(Boolean))]
    .join('；'),
)

const q = ref('')
const status = ref<Task['status'] | ''>('')
const productQ = ref('')
const taskPageIndex = ref(0)
const productPageIndex = ref(0)

const hasTaskFilter = computed(() => Boolean(q.value.trim() || status.value))
const workers = computed(() => overview.value?.workers ?? [])
const onlineWorkers = computed(() =>
  workers.value.filter(worker => worker.status === 'ONLINE').length,
)
const totalWorkers = computed(() => workers.value.length)
const taskPageCount = computed(() =>
  Math.max(1, Math.ceil(taskPage.value.total / taskPage.value.size)),
)
const productPageCount = computed(() =>
  Math.max(1, Math.ceil(productPage.value.total / productPage.value.size)),
)
const queueLabel = computed(() => {
  if (!overview.value) return '—'
  return overview.value.queueMode === 'database' ? 'database 本地队列' : 'rabbitmq 队列'
})

let overviewReq = 0
let tasksReq = 0
let productsReq = 0
let disposed = false
let pollBusy = false
let pollTimer: ReturnType<typeof setInterval> | undefined
let taskSearchTimer: ReturnType<typeof setTimeout> | undefined
let productSearchTimer: ReturnType<typeof setTimeout> | undefined

function clearTaskSearch() {
  if (taskSearchTimer !== undefined) clearTimeout(taskSearchTimer)
  taskSearchTimer = undefined
}

function clearProductSearch() {
  if (productSearchTimer !== undefined) clearTimeout(productSearchTimer)
  productSearchTimer = undefined
}

async function loadOverview(silent = false) {
  if (disposed) return
  const request = ++overviewReq
  if (!silent) loadingOverview.value = true

  try {
    const [summary, tasks] = await Promise.all([
      api.overview(),
      api.tasks({ page: 0, size: 5 }),
    ])
    if (disposed || request !== overviewReq) return
    overview.value = summary
    recentTasks.value = tasks.items
    overviewError.value = ''
  } catch (error) {
    if (!disposed && request === overviewReq) overviewError.value = errorMessage(error)
  } finally {
    if (!disposed && request === overviewReq) loadingOverview.value = false
  }
}

async function loadTasks(silent = false) {
  if (disposed) return
  const request = ++tasksReq
  if (!silent) loadingTasks.value = true

  try {
    const result = await api.tasks({
      q: q.value.trim(),
      status: status.value || undefined,
      page: taskPageIndex.value,
      size: taskPage.value.size,
    })
    if (disposed || request !== tasksReq) return

    const lastPage = Math.max(0, Math.ceil(result.total / result.size) - 1)
    if (taskPageIndex.value > lastPage) {
      taskPageIndex.value = lastPage
      await loadTasks(silent)
      return
    }

    taskPage.value = result
    tasksError.value = ''
  } catch (error) {
    if (!disposed && request === tasksReq) tasksError.value = errorMessage(error)
  } finally {
    if (!disposed && request === tasksReq) loadingTasks.value = false
  }
}

async function loadProducts(silent = false) {
  if (disposed) return
  const request = ++productsReq
  if (!silent) loadingProducts.value = true

  try {
    const result = await api.products(
      productQ.value.trim(),
      productPageIndex.value,
      productPage.value.size,
    )
    if (disposed || request !== productsReq) return

    const lastPage = Math.max(0, Math.ceil(result.total / result.size) - 1)
    if (productPageIndex.value > lastPage) {
      productPageIndex.value = lastPage
      await loadProducts(silent)
      return
    }

    productPage.value = result
    productsError.value = ''
  } catch (error) {
    if (!disposed && request === productsReq) productsError.value = errorMessage(error)
  } finally {
    if (!disposed && request === productsReq) loadingProducts.value = false
  }
}

async function refreshAll() {
  clearTaskSearch()
  clearProductSearch()
  await Promise.all([loadOverview(), loadTasks(), loadProducts()])
}

function refreshTasks() {
  clearTaskSearch()
  void loadTasks()
}

function refreshProducts() {
  clearProductSearch()
  void loadProducts()
}

function changeTaskPage(offset: number) {
  const next = taskPageIndex.value + offset
  if (loadingTasks.value || next < 0 || next >= taskPageCount.value) return
  taskPageIndex.value = next
  void loadTasks()
}

function changeProductPage(offset: number) {
  const next = productPageIndex.value + offset
  if (loadingProducts.value || next < 0 || next >= productPageCount.value) return
  productPageIndex.value = next
  void loadProducts()
}

function openTask(id: string) {
  selectedTaskId.value = id
}

function handleTaskCreated(task: Task) {
  wizardOpen.value = false
  selectedTaskId.value = task.id
  taskPageIndex.value = 0
  void refreshAll()
}

watch(q, () => {
  clearTaskSearch()
  ++tasksReq
  taskPageIndex.value = 0
  loadingTasks.value = true
  taskSearchTimer = setTimeout(() => {
    taskSearchTimer = undefined
    void loadTasks()
  }, 300)
}, { flush: 'sync' })

watch(status, () => {
  clearTaskSearch()
  ++tasksReq
  taskPageIndex.value = 0
  void loadTasks()
}, { flush: 'sync' })

watch(productQ, () => {
  clearProductSearch()
  ++productsReq
  productPageIndex.value = 0
  loadingProducts.value = true
  productSearchTimer = setTimeout(() => {
    productSearchTimer = undefined
    void loadProducts()
  }, 300)
}, { flush: 'sync' })

watch(activeTab, tab => {
  if (tab === 'overview') {
    void loadOverview()
  } else if (tab === 'tasks' && taskSearchTimer === undefined) {
    void loadTasks()
  } else if (tab === 'products' && productSearchTimer === undefined) {
    void loadProducts()
  }
})

async function poll() {
  if (disposed || document.hidden || pollBusy) return

  const hasPendingTasks = Boolean(
    overview.value && (overview.value.runningTasks > 0 || overview.value.queuedTasks > 0),
  )
  if (!hasPendingTasks && activeTab.value === 'products') return

  pollBusy = true
  try {
    const requests: Promise<void>[] = []
    if (!loadingOverview.value) requests.push(loadOverview(true))
    if (
      activeTab.value === 'tasks'
      && taskSearchTimer === undefined
      && !loadingTasks.value
    ) {
      requests.push(loadTasks(true))
    }
    if (
      activeTab.value === 'products'
      && productSearchTimer === undefined
      && !loadingProducts.value
    ) {
      requests.push(loadProducts(true))
    }
    await Promise.all(requests)
  } finally {
    pollBusy = false
  }
}

function handleVisibilityChange() {
  if (!document.hidden) void poll()
}

onMounted(() => {
  void refreshAll()
  pollTimer = setInterval(() => void poll(), 3000)
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onBeforeUnmount(() => {
  disposed = true
  ++overviewReq
  ++tasksReq
  ++productsReq
  clearTaskSearch()
  clearProductSearch()
  if (pollTimer !== undefined) clearInterval(pollTimer)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <div class="brand-mark">
          <Layers :size="22" aria-hidden="true" />
        </div>
        <div>
          <h1>BatchFlow</h1>
          <p>批量数据处理与任务调度平台</p>
        </div>
      </div>

      <div class="topbar-actions">
        <span class="mode-pill">
          <Database :size="15" aria-hidden="true" />
          {{ queueLabel }}
        </span>
        <span class="worker-pill">
          <Server :size="15" aria-hidden="true" />
          在线 Worker {{ overview ? number(onlineWorkers) : '—' }}
        </span>
        <button type="button" class="button primary" @click="wizardOpen = true">
          <FilePlus2 :size="17" aria-hidden="true" />
          新建导入任务
        </button>
      </div>
    </header>

    <main class="main-content">
      <div v-if="globalError" class="alert error" role="alert">
        <CircleAlert :size="18" aria-hidden="true" />
        <span>{{ globalError }}</span>
        <button type="button" class="text-link" @click="refreshAll">
          重新加载
        </button>
      </div>

      <nav class="tabs-bar" role="tablist" aria-label="工作区">
        <button
          id="overview-tab"
          type="button"
          role="tab"
          class="tab-button"
          :class="{ active: activeTab === 'overview' }"
          :aria-selected="activeTab === 'overview'"
          aria-controls="overview-panel"
          @click="activeTab = 'overview'"
        >
          <Activity :size="17" aria-hidden="true" />
          总览工作台
        </button>
        <button
          id="tasks-tab"
          type="button"
          role="tab"
          class="tab-button"
          :class="{ active: activeTab === 'tasks' }"
          :aria-selected="activeTab === 'tasks'"
          aria-controls="tasks-panel"
          @click="activeTab = 'tasks'"
        >
          <Layers :size="17" aria-hidden="true" />
          导入任务
        </button>
        <button
          id="products-tab"
          type="button"
          role="tab"
          class="tab-button"
          :class="{ active: activeTab === 'products' }"
          :aria-selected="activeTab === 'products'"
          aria-controls="products-panel"
          @click="activeTab = 'products'"
        >
          <Package :size="17" aria-hidden="true" />
          已入库商品
        </button>
      </nav>

      <section
        v-if="activeTab === 'overview'"
        id="overview-panel"
        role="tabpanel"
        aria-labelledby="overview-tab"
      >
        <div class="metrics-grid">
          <article class="metric-card">
            <div class="metric-label">
              <Layers :size="16" aria-hidden="true" />
              总任务数
            </div>
            <div class="metric-value">{{ number(overview?.totalTasks) }}</div>
            <div class="metric-meta">
              运行中 {{ number(overview?.runningTasks) }}
              · 排队中 {{ number(overview?.queuedTasks) }}
            </div>
          </article>

          <article class="metric-card">
            <div class="metric-label">
              <Activity :size="16" aria-hidden="true" />
              累计处理行
            </div>
            <div class="metric-value">{{ number(overview?.processedRows) }}</div>
            <div class="metric-meta">
              成功 {{ number(overview?.successRows) }}
              · 错误 {{ number(overview?.errorRows) }}
            </div>
          </article>

          <article class="metric-card">
            <div class="metric-label">
              <Package :size="16" aria-hidden="true" />
              已入库商品
            </div>
            <div class="metric-value">{{ number(overview?.productCount) }}</div>
            <div class="metric-meta">全局唯一 SKU</div>
          </article>

          <article class="metric-card">
            <div class="metric-label">
              <Server :size="16" aria-hidden="true" />
              Worker 节点
            </div>
            <div class="metric-value">
              {{ overview ? `${number(onlineWorkers)} / ${number(totalWorkers)}` : '—' }}
            </div>
            <div class="metric-meta">{{ queueLabel }}</div>
          </article>
        </div>

        <section class="panel">
          <div class="panel-header">
            <h2>执行节点</h2>
            <span class="count-tag">{{ overview ? number(totalWorkers) : '—' }}</span>
          </div>

          <div v-if="workers.length" class="workers-grid">
            <article v-for="worker in workers" :key="worker.id" class="worker-card">
              <div class="worker-header">
                <Server :size="16" aria-hidden="true" />
                <strong class="truncate">{{ worker.id }}</strong>
                <span
                  class="worker-status"
                  :class="worker.status === 'ONLINE' ? 'online' : 'offline'"
                >
                  {{ worker.status === 'ONLINE' ? '在线' : '离线' }}
                </span>
              </div>
              <p class="muted small">最近心跳：{{ dateTime(worker.lastSeenAt) }}</p>
              <div class="small">
                <template v-if="worker.currentTaskId">
                  当前任务：
                  <button
                    type="button"
                    class="text-link"
                    @click="openTask(worker.currentTaskId)"
                  >
                    {{ worker.currentTaskId }}
                  </button>
                </template>
                <span v-else class="muted">空闲中</span>
              </div>
            </article>
          </div>
          <div v-else class="compact-empty">
            {{ loadingOverview ? '正在加载执行节点…' : overviewError ? '执行节点暂时无法加载' : '暂无已注册的执行节点' }}
          </div>
        </section>

        <section class="panel">
          <div class="panel-header">
            <h2>近期导入任务</h2>
            <button type="button" class="text-link" @click="activeTab = 'tasks'">
              查看全部任务
            </button>
          </div>
          <TaskTable
            :tasks="recentTasks"
            :loading="loadingOverview"
            :compact="true"
            @open="openTask"
            @create="wizardOpen = true"
          />
        </section>
      </section>

      <section
        v-else-if="activeTab === 'tasks'"
        id="tasks-panel"
        class="panel"
        role="tabpanel"
        aria-labelledby="tasks-tab"
      >
        <div class="toolbar">
          <div class="search-box">
            <Search :size="17" aria-hidden="true" />
            <input
              v-model="q"
              type="search"
              aria-label="搜索导入任务"
              placeholder="搜索任务名称或文件名"
            />
          </div>
          <select v-model="status" class="filter-select" aria-label="任务状态">
            <option value="">全部状态</option>
            <option v-for="(label, value) in statusLabels" :key="value" :value="value">
              {{ label }}
            </option>
          </select>
          <StatusBadge v-if="status" :status="status" />
          <button
            type="button"
            class="button secondary"
            :disabled="loadingTasks"
            @click="refreshTasks"
          >
            <RefreshCw :size="15" :class="{ spin: loadingTasks }" aria-hidden="true" />
            刷新
          </button>
        </div>

        <TaskTable
          :tasks="taskPage.items"
          :loading="loadingTasks"
          :empty-title="hasTaskFilter ? '没有符合条件的任务' : undefined"
          @open="openTask"
          @create="wizardOpen = true"
        />

        <div v-if="taskPage.total > taskPage.size" class="pagination">
          <span>
            第 {{ taskPageIndex + 1 }} / {{ taskPageCount }} 页
            · 共 {{ number(taskPage.total) }} 条
          </span>
          <div class="button-group">
            <button
              type="button"
              class="icon-button bordered"
              aria-label="上一页任务"
              :disabled="loadingTasks || taskPageIndex === 0"
              @click="changeTaskPage(-1)"
            >
              ‹
            </button>
            <button
              type="button"
              class="icon-button bordered"
              aria-label="下一页任务"
              :disabled="loadingTasks || taskPageIndex + 1 >= taskPageCount"
              @click="changeTaskPage(1)"
            >
              ›
            </button>
          </div>
        </div>
      </section>

      <section
        v-else
        id="products-panel"
        class="panel"
        role="tabpanel"
        aria-labelledby="products-tab"
      >
        <div class="toolbar">
          <div class="search-box">
            <Search :size="17" aria-hidden="true" />
            <input
              v-model="productQ"
              type="search"
              aria-label="搜索已入库商品"
              placeholder="搜索 SKU、名称或分类"
            />
          </div>
          <button
            type="button"
            class="button secondary"
            :disabled="loadingProducts"
            @click="refreshProducts"
          >
            <RefreshCw :size="15" :class="{ spin: loadingProducts }" aria-hidden="true" />
            刷新
          </button>
        </div>

        <div class="table-wrap" :aria-busy="loadingProducts">
          <table v-if="productPage.items.length" class="data-table product-table">
            <thead>
              <tr>
                <th scope="col">商品编码</th>
                <th scope="col">商品名称</th>
                <th scope="col">分类</th>
                <th scope="col" class="numeric">价格</th>
                <th scope="col" class="numeric">库存</th>
                <th scope="col">来源任务</th>
                <th scope="col">入库时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in productPage.items" :key="p.id">
                <td class="sku-code">{{ p.sku }}</td>
                <td>{{ p.name }}</td>
                <td>{{ p.category || '—' }}</td>
                <td class="numeric nowrap">{{ `¥${p.price.toFixed(2)}` }}</td>
                <td class="numeric nowrap">{{ number(p.stock) }}</td>
                <td>
                  <button
                    type="button"
                    class="text-link sku-code"
                    @click="openTask(p.sourceTaskId)"
                  >
                    {{ p.sourceTaskId }}
                  </button>
                </td>
                <td class="muted small nowrap">{{ dateTime(p.createdAt) }}</td>
              </tr>
            </tbody>
          </table>

          <div
            v-else-if="loadingProducts"
            class="empty-state"
            role="status"
          >
            <LoaderCircle :size="28" class="spin" aria-hidden="true" />
            <p>正在加载商品…</p>
          </div>

          <div
            v-else
            class="empty-state"
            role="status"
          >
            <span class="empty-icon">
              <CircleAlert v-if="productsError" :size="28" aria-hidden="true" />
              <Package v-else :size="28" aria-hidden="true" />
            </span>
            <h3>
              {{ productsError ? '商品暂时无法加载' : productQ.trim() ? '没有符合条件的商品' : '暂无已入库商品' }}
            </h3>
            <p v-if="!productsError">
              {{ productQ.trim() ? '试试其他 SKU、名称或分类。' : '导入任务处理成功后，商品将在这里展示。' }}
            </p>
          </div>
        </div>

        <div v-if="productPage.total > productPage.size" class="pagination">
          <span>
            第 {{ productPageIndex + 1 }} / {{ productPageCount }} 页
            · 共 {{ number(productPage.total) }} 条
          </span>
          <div class="button-group">
            <button
              type="button"
              class="icon-button bordered"
              aria-label="上一页商品"
              :disabled="loadingProducts || productPageIndex === 0"
              @click="changeProductPage(-1)"
            >
              ‹
            </button>
            <button
              type="button"
              class="icon-button bordered"
              aria-label="下一页商品"
              :disabled="loadingProducts || productPageIndex + 1 >= productPageCount"
              @click="changeProductPage(1)"
            >
              ›
            </button>
          </div>
        </div>
      </section>
    </main>

    <ImportWizard
      :open="wizardOpen"
      @close="wizardOpen = false"
      @created="handleTaskCreated"
    />
    <TaskDrawer
      :task-id="selectedTaskId"
      @close="selectedTaskId = null"
      @changed="refreshAll"
    />
  </div>
</template>
