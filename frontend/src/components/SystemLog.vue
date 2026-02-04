<template>
  <div class="system-log-container">
    <!-- Header -->
    <div class="log-header" @click="toggleExpanded">
      <div class="flex items-center gap-2">
        <svg 
          class="w-5 h-5 transition-transform duration-200" 
          :class="{ 'rotate-180': isExpanded }"
          fill="none" 
          stroke="currentColor" 
          viewBox="0 0 24 24"
        >
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
        </svg>
        <span class="font-semibold">System Log</span>
        <span class="text-xs text-gray-400">({{ logs.length }} entries)</span>
        <div v-if="wsConnected" class="flex items-center gap-1 text-green-400 text-xs">
          <div class="w-2 h-2 bg-green-400 rounded-full animate-pulse"></div>
          Live
        </div>
        <div v-else class="flex items-center gap-1 text-red-400 text-xs">
          <div class="w-2 h-2 bg-red-400 rounded-full"></div>
          Disconnected
        </div>
      </div>
      
      <div class="flex items-center gap-2" @click.stop>
        <!-- Filter buttons -->
        <button
          v-for="level in ['All', 'INFO', 'WARNING', 'ERROR']"
          :key="level"
          @click="filterLevel = level"
          :class="[
            'px-2 py-1 text-xs rounded transition-colors',
            filterLevel === level 
              ? 'bg-blue-600 text-white' 
              : 'bg-gray-700 text-gray-300 hover:bg-gray-600'
          ]"
        >
          {{ level }}
        </button>
        
        <!-- Export button -->
        <button
          @click="exportLogs"
          class="px-2 py-1 text-xs bg-gray-700 text-gray-300 rounded hover:bg-gray-600 transition-colors"
          title="Export logs to file"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
          </svg>
        </button>
        
        <!-- Clear button -->
        <button
          @click="clearLogs"
          class="px-2 py-1 text-xs bg-red-600 text-white rounded hover:bg-red-700 transition-colors"
          title="Clear all logs"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
          </svg>
        </button>
      </div>
    </div>
    
    <!-- Log content -->
    <transition name="slide">
      <div v-if="isExpanded" class="log-content">
        <div ref="logContainer" class="log-entries">
          <div
            v-for="(log, index) in filteredLogs"
            :key="index"
            :class="[
              'log-entry',
              `log-${log.level.toLowerCase()}`
            ]"
          >
            <div class="log-timestamp">{{ formatTime(log.timestamp) }}</div>
            <div :class="['log-level', `level-${log.level.toLowerCase()}`]">
              {{ log.level }}
            </div>
            <div class="log-message">{{ log.message }}</div>
            <div v-if="log.details && Object.keys(log.details).length > 0" class="log-details">
              {{ formatDetails(log.details) }}
            </div>
          </div>
          
          <div v-if="filteredLogs.length === 0" class="text-center text-gray-500 py-8">
            No logs to display
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'

const logs = ref([])
const filterLevel = ref('All')
const isExpanded = ref(true)
const wsConnected = ref(false)
const logContainer = ref(null)
const autoScroll = ref(true)

let ws = null
const MAX_LOGS = 100

// Filtered logs based on level
const filteredLogs = computed(() => {
  if (filterLevel.value === 'All') {
    return logs.value
  }
  return logs.value.filter(log => log.level === filterLevel.value)
})

// Connect to WebSocket
const connectWebSocket = () => {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  // Use port 8000 for backend API if we are on dev port 5173
  const host = window.location.hostname
  const port = window.location.port === '5173' ? '8000' : (window.location.port || (window.location.protocol === 'https:' ? '443' : '80'))
  const wsUrl = `${protocol}//${host}:${port}/ws/logs`
  
  ws = new WebSocket(wsUrl)

  
  ws.onopen = () => {
    wsConnected.value = true
    console.log('WebSocket connected to system logs')
  }
  
  ws.onmessage = (event) => {
    try {
      const logEntry = JSON.parse(event.data)
      addLog(logEntry)
    } catch (error) {
      console.error('Failed to parse log entry:', error)
    }
  }
  
  ws.onerror = (error) => {
    console.error('WebSocket error:', error)
    wsConnected.value = false
  }
  
  ws.onclose = () => {
    wsConnected.value = false
    console.log('WebSocket disconnected, reconnecting in 3s...')
    
    // Reconnect after 3 seconds
    setTimeout(() => {
      if (!wsConnected.value) {
        connectWebSocket()
      }
    }, 3000)
  }
  
  // Send ping every 30 seconds to keep connection alive
  setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send('ping')
    }
  }, 30000)
}

// Add log entry
const addLog = (logEntry) => {
  logs.value.push(logEntry)
  
  // Keep only last MAX_LOGS entries
  if (logs.value.length > MAX_LOGS) {
    logs.value.shift()
  }
  
  // Auto-scroll to bottom if enabled
  if (autoScroll.value) {
    nextTick(() => {
      scrollToBottom()
    })
  }
}

// Scroll to bottom
const scrollToBottom = () => {
  if (logContainer.value) {
    logContainer.value.scrollTop = logContainer.value.scrollHeight
  }
}

// Format timestamp
const formatTime = (timestamp) => {
  const date = new Date(timestamp)
  return date.toLocaleTimeString('en-US', { 
    hour12: false,
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  })
}

// Format details object
const formatDetails = (details) => {
  return Object.entries(details)
    .map(([key, value]) => `${key}: ${JSON.stringify(value)}`)
    .join(', ')
}

// Toggle expanded state
const toggleExpanded = () => {
  isExpanded.value = !isExpanded.value
}

// Clear logs
const clearLogs = async () => {
  if (!confirm('Are you sure you want to clear all logs?')) {
    return
  }
  
  try {
    const response = await fetch('/api/logs', {
      method: 'DELETE'
    })
    
    if (response.ok) {
      logs.value = []
    }
  } catch (error) {
    console.error('Failed to clear logs:', error)
  }
}

// Export logs to file
const exportLogs = () => {
  const content = logs.value
    .map(log => {
      const details = log.details && Object.keys(log.details).length > 0
        ? ` | ${formatDetails(log.details)}`
        : ''
      return `[${log.timestamp}] ${log.level}: ${log.message}${details}`
    })
    .join('\n')
  
  const blob = new Blob([content], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `system-logs-${new Date().toISOString().slice(0, 19).replace(/:/g, '-')}.txt`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// Detect manual scroll (disable auto-scroll if user scrolls up)
const handleScroll = () => {
  if (!logContainer.value) return
  
  const { scrollTop, scrollHeight, clientHeight } = logContainer.value
  const isAtBottom = scrollHeight - scrollTop - clientHeight < 50
  
  autoScroll.value = isAtBottom
}

// Lifecycle
onMounted(() => {
  connectWebSocket()
  
  if (logContainer.value) {
    logContainer.value.addEventListener('scroll', handleScroll)
  }
})

onUnmounted(() => {
  if (ws) {
    ws.close()
  }
  
  if (logContainer.value) {
    logContainer.value.removeEventListener('scroll', handleScroll)
  }
})
</script>

<style scoped>
.system-log-container {
  @apply bg-gray-800 rounded-lg shadow-lg overflow-hidden;
}

.log-header {
  @apply flex items-center justify-between px-4 py-3 bg-gray-900 cursor-pointer select-none;
  @apply hover:bg-gray-800 transition-colors;
}

.log-content {
  @apply border-t border-gray-700;
}

.log-entries {
  @apply max-h-96 overflow-y-auto p-4 space-y-2 font-mono text-sm;
  scrollbar-width: thin;
  scrollbar-color: #4B5563 #1F2937;
}

.log-entries::-webkit-scrollbar {
  width: 8px;
}

.log-entries::-webkit-scrollbar-track {
  @apply bg-gray-800;
}

.log-entries::-webkit-scrollbar-thumb {
  @apply bg-gray-600 rounded;
}

.log-entries::-webkit-scrollbar-thumb:hover {
  @apply bg-gray-500;
}

.log-entry {
  @apply flex items-start gap-2 p-2 rounded bg-gray-900 bg-opacity-50;
  @apply border-l-2;
}

.log-entry.log-info {
  @apply border-blue-400;
}

.log-entry.log-warning {
  @apply border-yellow-400;
}

.log-entry.log-error {
  @apply border-red-400;
}

.log-timestamp {
  @apply text-gray-500 text-xs whitespace-nowrap;
  min-width: 70px;
}

.log-level {
  @apply text-xs font-bold px-2 py-0.5 rounded whitespace-nowrap;
  min-width: 70px;
  text-align: center;
}

.level-info {
  @apply bg-blue-900 text-blue-400;
}

.level-warning {
  @apply bg-yellow-900 text-yellow-400;
}

.level-error {
  @apply bg-red-900 text-red-400;
}

.log-message {
  @apply text-gray-200 flex-1;
}

.log-details {
  @apply text-gray-400 text-xs mt-1 pl-2 border-l border-gray-700;
}

/* Slide transition */
.slide-enter-active,
.slide-leave-active {
  transition: all 0.3s ease;
  max-height: 400px;
}

.slide-enter-from,
.slide-leave-to {
  max-height: 0;
  opacity: 0;
}
</style>
