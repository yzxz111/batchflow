<script setup lang="ts">
import { ArrowUpRight, FileSpreadsheet, Inbox, LoaderCircle } from 'lucide-vue-next'
import type { Task } from '../types'
import { dateTime, number, progress } from '../utils'
import StatusBadge from './StatusBadge.vue'
defineProps<{ tasks: Task[]; loading?: boolean; compact?: boolean; emptyTitle?: string }>()
defineEmits<{ open: [id: string]; create: [] }>()
</script>

<template>
  <div class="table-wrap">
    <table class="data-table task-table" v-if="tasks.length">
      <thead><tr><th>任务名称</th><th>状态</th><th>处理进度</th><th class="numeric" v-if="!compact">成功 / 错误</th><th>创建时间</th><th><span class="sr-only">查看</span></th></tr></thead>
      <tbody>
        <tr v-for="task in tasks" :key="task.id" :data-task-id="task.id">
          <td><div class="task-name-cell"><span class="file-symbol"><FileSpreadsheet :size="19" /></span><div class="truncate-cell"><button class="text-link task-name" @click="$emit('open', task.id)">{{ task.name }}</button><p class="muted small truncate" :title="task.filename">{{ task.filename }}</p></div></div></td>
          <td><StatusBadge :status="task.status" /></td>
          <td><div class="table-progress"><div class="progress-meta"><span>{{ number(task.processedRows) }} <span class="muted">/ {{ number(task.totalRows) }}</span></span><span>{{ progress(task.progress).toFixed(0) }}%</span></div><div class="progress-track" :class="{ failed: task.status === 'FAILED', cancelled: task.status === 'CANCELLED' }" role="progressbar" :aria-valuenow="progress(task.progress)" aria-valuemin="0" aria-valuemax="100" :aria-label="`${task.name}处理进度`"><span :style="{ width: `${progress(task.progress)}%` }" /></div></div></td>
          <td class="numeric" v-if="!compact"><span>{{ number(task.successRows) }}</span><span class="muted"> / </span><span :class="{ 'error-text': task.errorRows > 0 }">{{ number(task.errorRows) }}</span></td>
          <td class="muted small nowrap">{{ dateTime(task.createdAt, true) }}</td>
          <td><button class="icon-button" :aria-label="`查看任务 ${task.name}`" @click="$emit('open', task.id)"><ArrowUpRight :size="17" /></button></td>
        </tr>
      </tbody>
    </table>
    <div v-else-if="loading" class="empty-state"><LoaderCircle :size="28" class="spin" /><p>正在获取任务…</p></div>
    <div v-else class="empty-state"><span class="empty-icon"><Inbox :size="29" /></span><h3>{{ emptyTitle || '还没有导入任务' }}</h3><p>{{ emptyTitle ? '尝试调整搜索关键词或筛选条件。' : '上传一份 CSV，开始你的第一个数据处理任务。' }}</p><button v-if="!emptyTitle" class="button secondary small-button" @click="$emit('create')">创建第一个任务 <ArrowUpRight :size="14" /></button></div>
  </div>
</template>
