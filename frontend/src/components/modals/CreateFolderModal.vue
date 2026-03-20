<template>
  <div v-if="visible" class="modal-overlay" @click.self="close">
    <div class="modal-content">
      <div class="modal-header">
        <h3 class="text-lg font-medium text-white">{{ t('modals.createFolder.title') }}</h3>
        <button class="text-gray-400 hover:text-white" @click="close">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div class="modal-body">
        <div class="mb-4">
          <label class="block text-sm font-medium text-gray-300 mb-2">{{ t('modals.createFolder.folder_name') }}</label>
          <input
            v-model="folderName"
            type="text"
            :placeholder="t('modals.createFolder.enter_folder_name')"
            class="w-full px-3 py-2 bg-gray-800 border border-gray-600 rounded text-white focus:outline-none focus:border-blue-500"
            @keyup.enter="createFolder"
          >
        </div>

        <div class="mb-4">
          <label class="block text-sm font-medium text-gray-300 mb-2">{{ t('modals.createFolder.tags_optional') }}</label>
          <input
            v-model="tagsInput"
            type="text"
            :placeholder="t('modals.createFolder.enter_tags')"
            class="w-full px-3 py-2 bg-gray-800 border border-gray-600 rounded text-white focus:outline-none focus:border-blue-500"
          >
          <p class="text-xs text-gray-500 mt-1">{{ t('modals.createFolder.tags_example') }}</p>
        </div>

        <div v-if="error" class="mb-4 p-3 bg-red-900 bg-opacity-50 border border-red-700 rounded text-red-300 text-sm">
          {{ error }}
        </div>
      </div>

      <div class="modal-footer">
        <button class="btn-secondary" @click="close">{{ t('modals.createFolder.cancel') }}</button>
        <button class="btn-primary" :disabled="!folderName || loading" @click="createFolder">
          {{ loading ? t('modals.createFolder.creating') : t('modals.createFolder.create') }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSharedStore } from '../../stores/shared'

const { t } = useI18n()

const props = defineProps({
  visible: Boolean
})

const emit = defineEmits(['close', 'created'])
const store = useSharedStore()

const folderName = ref('')
const tagsInput = ref('')
const loading = ref(false)
const error = ref(null)

function close() {
  folderName.value = ''
  tagsInput.value = ''
  error.value = null
  emit('close')
}

async function createFolder() {
  if (!folderName.value) return

  loading.value = true
  error.value = null

  try {
    const tags = tagsInput.value
      .split(',')
      .map(t => t.trim())
      .filter(t => t.length > 0)

    await store.createFolder(folderName.value, tags)
    emit('created')
    close()
  } catch (err) {
    console.error('Create folder failed:', err)
    error.value = err.response?.data?.detail || t('modals.createFolder.failed_to_create')
  } finally {
    loading.value = false
  }
}
</script>


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
