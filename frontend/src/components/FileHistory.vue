<template>
  <div v-if="visible" class="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-50" @click.self="close">
    <div class="bg-gray-800 rounded-lg shadow-xl w-full max-w-3xl max-h-[80vh] flex flex-col">
      <!-- Header -->
      <div class="flex items-center justify-between p-4 border-b border-gray-700">
        <div class="flex-1 min-w-0">
          <h2 class="text-lg font-semibold text-white truncate">File History</h2>
          <p class="text-sm text-gray-400 truncate">{{ filePath }}</p>
        </div>
        <button @click="close" class="ml-4 text-gray-400 hover:text-white transition-colors">
          <svg class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <!-- Filters -->
      <div class="flex items-center space-x-2 p-4 border-b border-gray-700">
        <button
          v-for="filter in filters"
          :key="filter.value"
          @click="activeFilter = filter.value"
          :class="[
            'px-3 py-1.5 rounded-md text-sm font-medium transition-colors',
            activeFilter === filter.value
              ? 'bg-blue-600 text-white'
              : 'bg-gray-700 text-gray-300 hover:bg-gray-600'
          ]"
        >
          {{ filter.icon }} {{ filter.label }}
        </button>
      </div>

      <!-- History Timeline -->
      <div class="flex-1 overflow-y-auto p-6">
        <div v-if="loading" class="flex items-center justify-center py-12">
          <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-500"></div>
        </div>

        <div v-else-if="error" class="text-center py-12">
          <p class="text-red-400">{{ error }}</p>
        </div>

        <div v-else-if="filteredHistory.length === 0" class="text-center py-12">
          <p class="text-gray-400">No history found</p>
        </div>

        <div v-else class="relative">
          <!-- Timeline line -->
          <div class="absolute left-6 top-0 bottom-0 w-0.5 bg-gray-700"></div>

          <!-- Timeline items -->
          <div
            v-for="(item, index) in filteredHistory"
            :key="index"
            class="relative pl-16 pb-8 last:pb-0"
          >
            <!-- Timeline dot -->
            <div
              :class="[
                'absolute left-4 w-4 h-4 rounded-full border-2 border-gray-800',
                getActionColor(item.action)
              ]"
            ></div>

            <!-- Content card -->
            <div class="bg-gray-700 rounded-lg p-4 hover:bg-gray-650 transition-colors">
              <div class="flex items-start justify-between">
                <div class="flex-1 min-w-0">
                  <!-- Action and message -->
                  <div class="flex items-center space-x-2 mb-2">
                    <span :class="['text-sm font-semibold', getActionTextColor(item.action)]">
                      {{ getActionLabel(item.action) }}
                    </span>
                    <span class="text-gray-400">•</span>
                    <span class="text-sm text-gray-300 truncate">{{ item.message }}</span>
                  </div>

                  <!-- Metadata -->
                  <div class="flex items-center space-x-4 text-xs text-gray-400">
                    <span class="flex items-center space-x-1">
                      <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
                      </svg>
                      <span>{{ item.author }}</span>
                    </span>
                    <span class="flex items-center space-x-1">
                      <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                      </svg>
                      <span>{{ formatDate(item.date) }}</span>
                    </span>
                    <span class="font-mono text-gray-500">{{ item.hash.substring(0, 7) }}</span>
                  </div>
                </div>

                <!-- Restore button for deleted files -->
                <button
                  v-if="item.action === 'deleted'"
                  @click="restore(item.hash)"
                  class="ml-4 px-3 py-1.5 bg-green-600 hover:bg-green-700 text-white text-sm rounded-md transition-colors flex items-center space-x-1"
                >
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                  </svg>
                  <span>Restore</span>
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useSharedStore } from '@/stores/shared'

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  folder: {
    type: String,
    required: true
  },
  filePath: {
    type: String,
    required: true
  }
})

const emit = defineEmits(['close', 'restore'])

const sharedStore = useSharedStore()

const history = ref([])
const loading = ref(false)
const error = ref(null)
const activeFilter = ref('all')

const filters = [
  { value: 'all', label: 'All Changes', icon: '📋' },
  { value: 'added', label: 'Added', icon: '➕' },
  { value: 'deleted', label: 'Deleted', icon: '🗑️' }
]

const filteredHistory = computed(() => {
  if (activeFilter.value === 'all') {
    return history.value
  }
  return history.value.filter(item => item.action === activeFilter.value)
})

watch(() => props.visible, async (newValue) => {
  if (newValue) {
    await loadHistory()
  }
})

async function loadHistory() {
  loading.value = true
  error.value = null
  
  try {
    const fileId = `${props.folder}/${props.filePath}`
    const data = await sharedStore.getFileHistory(fileId)
    history.value = data
  } catch (err) {
    error.value = 'Failed to load file history'
    console.error('Failed to load history:', err)
  } finally {
    loading.value = false
  }
}

function getActionColor(action) {
  switch (action) {
    case 'added':
      return 'bg-green-500'
    case 'deleted':
      return 'bg-red-500'
    case 'modified':
      return 'bg-blue-500'
    default:
      return 'bg-gray-500'
  }
}

function getActionTextColor(action) {
  switch (action) {
    case 'added':
      return 'text-green-400'
    case 'deleted':
      return 'text-red-400'
    case 'modified':
      return 'text-blue-400'
    default:
      return 'text-gray-400'
  }
}

function getActionLabel(action) {
  switch (action) {
    case 'added':
      return 'Added'
    case 'deleted':
      return 'Deleted'
    case 'modified':
      return 'Modified'
    default:
      return 'Changed'
  }
}

function formatDate(dateString) {
  const date = new Date(dateString)
  const now = new Date()
  const diff = now - date
  
  // Less than 1 minute
  if (diff < 60000) {
    return 'just now'
  }
  
  // Less than 1 hour
  if (diff < 3600000) {
    const minutes = Math.floor(diff / 60000)
    return `${minutes} minute${minutes > 1 ? 's' : ''} ago`
  }
  
  // Less than 1 day
  if (diff < 86400000) {
    const hours = Math.floor(diff / 3600000)
    return `${hours} hour${hours > 1 ? 's' : ''} ago`
  }
  
  // Less than 1 week
  if (diff < 604800000) {
    const days = Math.floor(diff / 86400000)
    return `${days} day${days > 1 ? 's' : ''} ago`
  }
  
  // Format as date
  return date.toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function close() {
  emit('close')
}

function restore(commitHash) {
  emit('restore', { commitHash })
}
</script>
