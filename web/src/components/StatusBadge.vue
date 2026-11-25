<script setup lang="ts">
import type { TaskStatus } from '../types'
import { statusLabels } from '../utils'
defineProps<{ status: TaskStatus }>()
</script>

<template>
  <span class="status-badge" :class="status.toLowerCase()"><span class="status-dot" />{{ statusLabels[status] ?? status }}</span>
</template>
