<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal-content">
      <div class="modal-header">
        <h3 class="text-lg font-medium text-white">Create Subfolder</h3>
        <button class="text-gray-400 hover:text-white" @click="$emit('close')">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div class="modal-body">
        <div class="mb-4">
          <label class="block text-sm font-medium text-gray-300 mb-2">Subfolder Name</label>
          <input
            v-model="subfolderName"
            type="text"
            placeholder="Enter subfolder name"
            class="w-full px-3 py-2 bg-gray-800 border border-gray-600 rounded text-white focus:outline-none focus:border-blue-500"
            @keyup.enter="createSubfolder"
          >
        </div>

        <div v-if="error" class="mb-4 p-3 bg-red-900 bg-opacity-50 border border-red-700 rounded text-red-300 text-sm">
          {{ error }}
        </div>
      </div>

      <div class="modal-footer">
        <button class="btn-secondary" @click="$emit('close')">Cancel</button>
        <button class="btn-primary" :disabled="!subfolderName || loading" @click="createSubfolder">
          {{ loading ? 'Creating...' : 'Create' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useSharedStore } from '../../stores/shared'

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
    error.value = err.response?.data?.detail || 'Failed to create subfolder'
  } finally {
    loading.value = false
  }
}
</script>
