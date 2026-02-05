<template>
  <div v-if="file" class="file-info-panel">
    <div class="info-header">
      <h3 class="text-sm font-semibold text-gray-300">File Information</h3>
    </div>
    
    <div class="info-content">
      <div class="info-item">
        <span class="label">Name:</span>
        <span class="value">{{ fileName }}</span>
      </div>
      
      <div class="info-item">
        <span class="label">Path:</span>
        <span class="value truncate" :title="file">{{ file }}</span>
      </div>
      
      <div v-if="metadata?.size" class="info-item">
        <span class="label">Size:</span>
        <span class="value">{{ formatSize(metadata.size) }}</span>
      </div>
      
      <div v-if="metadata?.modified" class="info-item">
        <span class="label">Modified:</span>
        <span class="value">{{ formatDate(metadata.modified) }}</span>
      </div>
      
      <div v-if="metadata?.type" class="info-item">
        <span class="label">Type:</span>
        <span class="value">{{ metadata.type }}</span>
      </div>
      
      <div v-if="metadata?.lines" class="info-item">
        <span class="label">Lines:</span>
        <span class="value">{{ metadata.lines }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  file: String,
  metadata: Object
})

const fileName = computed(() => {
  if (!props.file) return ''
  return props.file.split('/').pop()
})

const formatSize = (bytes) => {
  if (!bytes) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i]
}

const formatDate = (dateString) => {
  if (!dateString) return ''
  const date = new Date(dateString)
  return date.toLocaleString()
}
</script>

<style scoped>
.file-info-panel {
  @apply bg-gray-800 border-t border-gray-700;
}

.info-header {
  @apply px-4 py-2 border-b border-gray-700;
}

.info-content {
  @apply p-4 space-y-2;
}

.info-item {
  @apply flex text-sm;
}

.label {
  @apply w-20 text-gray-500 flex-shrink-0;
}

.value {
  @apply text-gray-300 flex-1;
}
</style>
