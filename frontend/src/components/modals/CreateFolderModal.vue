<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal-content">
      <div class="modal-header">
        <h3 class="text-lg font-medium text-white">Create Folder</h3>
        <button class="text-gray-400 hover:text-white" @click="$emit('close')">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div class="modal-body">
        <div class="mb-4">
          <label class="block text-sm font-medium text-gray-300 mb-2">Folder Name</label>
          <input
            v-model="folderName"
            type="text"
            placeholder="Enter folder name"
            class="w-full px-3 py-2 bg-gray-800 border border-gray-600 rounded text-white focus:outline-none focus:border-blue-500"
            @keyup.enter="createFolder"
          >
        </div>

        <div class="mb-4">
          <label class="block text-sm font-medium text-gray-300 mb-2">Tags (optional)</label>
          <input
            v-model="tagsInput"
            type="text"
            placeholder="Enter tags separated by commas"
            class="w-full px-3 py-2 bg-gray-800 border border-gray-600 rounded text-white focus:outline-none focus:border-blue-500"
          >
          <p class="text-xs text-gray-500 mt-1">Example: work, documents, important</p>
        </div>

        <div v-if="error" class="mb-4 p-3 bg-red-900 bg-opacity-50 border border-red-700 rounded text-red-300 text-sm">
          {{ error }}
        </div>
      </div>

      <div class="modal-footer">
        <button class="btn-secondary" @click="$emit('close')">Cancel</button>
        <button class="btn-primary" :disabled="!folderName || loading" @click="createFolder">
          {{ loading ? 'Creating...' : 'Create' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useSharedStore } from '../../stores/shared'

const emit = defineEmits(['close', 'created'])
const store = useSharedStore()

const folderName = ref('')
const tagsInput = ref('')
const loading = ref(false)
const error = ref(null)

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
  } catch (err) {
    console.error('Create folder failed:', err)
    error.value = err.response?.data?.detail || 'Failed to create folder'
  } finally {
    loading.value = false
  }
}
</script>
