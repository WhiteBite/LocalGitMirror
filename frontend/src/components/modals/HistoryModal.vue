<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal-content max-w-2xl">
      <div class="modal-header">
        <h3 class="text-lg font-medium text-white">{{ t('modals.history.title') }}</h3>
        <button class="text-gray-400 hover:text-white" @click="$emit('close')">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div class="modal-body">
        <div class="mb-4">
          <p class="text-sm text-gray-400">{{ t('modals.history.file', { name: file.name }) }}</p>
        </div>

        <div v-if="loading" class="text-center py-8">
          <div class="spinner mx-auto mb-4"></div>
          <p class="text-gray-400">{{ t('modals.history.loading_history') }}</p>
        </div>

        <div v-else-if="error" class="p-4 bg-red-900 bg-opacity-50 border border-red-700 rounded text-red-300">
          {{ error }}
        </div>

        <div v-else-if="history.length === 0" class="text-center py-8 text-gray-400">
          {{ t('modals.history.no_history_available') }}
        </div>

        <div v-else class="space-y-3 max-h-96 overflow-y-auto">
          <div
            v-for="entry in history"
            :key="entry.id"
            class="p-4 bg-gray-800 rounded border border-gray-700"
          >
            <div class="flex items-start justify-between mb-2">
              <div class="flex items-center space-x-2">
                <svg class="w-5 h-5 text-blue-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                <span class="text-sm font-medium text-white">{{ entry.action }}</span>
              </div>
              <span class="text-xs text-gray-500">{{ formatDate(entry.timestamp) }}</span>
            </div>
            <p v-if="entry.details" class="text-sm text-gray-400">{{ entry.details }}</p>
            <div v-if="entry.size" class="text-xs text-gray-500 mt-1">
              {{ t('modals.history.size', { size: formatSize(entry.size) }) }}
            </div>
          </div>
        </div>
      </div>

      <div class="modal-footer">
        <button class="btn-secondary" @click="$emit('close')">{{ t('modals.history.close') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSharedStore } from '../../stores/shared'

const { t } = useI18n()

const props = defineProps({
  file: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['close'])
const store = useSharedStore()

const history = ref([])
const loading = ref(false)
const error = ref(null)

async function loadHistory() {
  loading.value = true
  error.value = null

  try {
    history.value = await store.getFileHistory(props.file.id)
  } catch (err) {
    console.error('Load history failed:', err)
    error.value = err.response?.data?.detail || t('modals.history.failed_to_load')
  } finally {
    loading.value = false
  }
}

function formatSize(bytes) {
  if (!bytes) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i]
}

function formatDate(dateString) {
  if (!dateString) return 'Unknown'
  const date = new Date(dateString)
  return date.toLocaleDateString() + ' ' + date.toLocaleTimeString()
}

onMounted(() => {
  loadHistory()
})
</script>

<style scoped>
.spinner {
  @apply inline-block animate-spin rounded-full h-8 w-8 border-b-2 border-blue-500;
}
</style>


<style scoped>
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.75);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: #1e1e1e;
  border-radius: 8px;
  max-width: 500px;
  width: 90%;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.3);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1rem 1.5rem;
  border-bottom: 1px solid #333;
}

.modal-body {
  padding: 1.5rem;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
  padding: 1rem 1.5rem;
  border-top: 1px solid #333;
}
</style>
