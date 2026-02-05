<template>
  <div class="diff-viewer">
    <div v-for="(line, index) in lines" :key="index" class="diff-line" :class="getLineClass(line)">
      <div class="line-number">{{ index + 1 }}</div>
      <pre class="line-content">{{ line }}</pre>
    </div>
    <div v-if="lines.length === 0" class="empty-diff">
      No changes detected (or binary file)
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  diff: {
    type: String,
    required: true,
    default: ''
  }
})

const lines = computed(() => {
  if (!props.diff) return []
  return props.diff.split('\n')
})

function getLineClass(line) {
  if (line.startsWith('+') && !line.startsWith('+++')) return 'line-added'
  if (line.startsWith('-') && !line.startsWith('---')) return 'line-removed'
  if (line.startsWith('@@')) return 'line-header'
  return 'line-context'
}
</script>

<style scoped>
.diff-viewer {
  @apply font-mono text-sm overflow-auto h-full bg-gray-900 rounded-lg p-4 border border-gray-700;
}

.diff-line {
  @apply flex;
}

.line-number {
  @apply w-8 text-right pr-3 text-gray-600 select-none flex-shrink-0;
}

.line-content {
  @apply flex-1 whitespace-pre-wrap break-all;
}

.line-added {
  @apply bg-green-900 bg-opacity-30 text-green-300;
}

.line-removed {
  @apply bg-red-900 bg-opacity-30 text-red-300;
}

.line-header {
  @apply text-blue-400 font-bold bg-gray-800;
}

.line-context {
  @apply text-gray-400;
}

.empty-diff {
  @apply text-center text-gray-500 italic mt-10;
}
</style>
