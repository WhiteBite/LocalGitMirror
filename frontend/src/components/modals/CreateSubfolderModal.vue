<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal-content">
      <div class="modal-header">
        <h3 class="text-lg font-medium text-white">{{ t('modals.createSubfolder.title') }}</h3>
        <button class="text-gray-400 hover:text-white" @click="$emit('close')">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div class="modal-body">
        <div class="mb-4">
          <label class="block text-sm font-medium text-gray-300 mb-2">{{ t('modals.createSubfolder.subfolder_name') }}</label>
          <input
            v-model="subfolderName"
            type="text"
            :placeholder="t('modals.createSubfolder.enter_subfolder_name')"
            class="w-full px-3 py-2 bg-gray-800 border border-gray-600 rounded text-white focus:outline-none focus:border-blue-500"
            @keyup.enter="createSubfolder"
          >
        </div>

        <div v-if="error" class="mb-4 p-3 bg-red-900 bg-opacity-50 border border-red-700 rounded text-red-300 text-sm">
          {{ error }}
        </div>
      </div>

      <div class="modal-footer">
        <button class="btn-secondary" @click="$emit('close')">{{ t('modals.createSubfolder.cancel') }}</button>
        <button class="btn-primary" :disabled="!subfolderName || loading" @click="createSubfolder">
          {{ loading ? t('modals.createSubfolder.creating') : t('modals.createSubfolder.create') }}
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
  folderId: {
    type: String,
    required: true
  },
  path: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['close', 'created'])
const store = useSharedStore()

const subfolderName = ref('')
const loading = ref(false)
const error = ref(null)

async function createSubfolder() {
  if (!subfolderName.value) return

  loading.value = true
  error.value = null

  try {
    await store.createSubfolder(props.folderId, props.path, subfolderName.value)
    emit('created')
  } catch (err) {
    console.error('Create subfolder failed:', err)
    error.value = err.response?.data?.detail || t('modals.createSubfolder.failed_to_create')
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
