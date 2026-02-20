<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal-content">
      <div class="modal-header">
        <h3 class="text-lg font-medium text-white">Manage Tags</h3>
        <button class="text-gray-400 hover:text-white" @click="$emit('close')">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div class="modal-body">
        <div class="mb-4">
          <p class="text-sm text-gray-400 mb-2">File: {{ file.name }}</p>
        </div>

        <div class="mb-4">
          <label class="block text-sm font-medium text-gray-300 mb-2">Tags</label>
          <div class="flex flex-wrap gap-2 mb-3">
            <span
              v-for="tag in tags"
              :key="tag"
              class="px-3 py-1 bg-blue-900 text-blue-300 rounded flex items-center space-x-2"
            >
              <span>{{ tag }}</span>
              <button class="hover:text-white" @click="removeTag(tag)">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </span>
          </div>

          <div class="flex space-x-2">
            <input
              v-model="newTag"
              type="text"
              placeholder="Add new tag"
              class="flex-1 px-3 py-2 bg-gray-800 border border-gray-600 rounded text-white focus:outline-none focus:border-blue-500"
              @keyup.enter="addTag"
            >
            <button class="btn-primary" @click="addTag">Add</button>
          </div>
        </div>

        <div v-if="error" class="mb-4 p-3 bg-red-900 bg-opacity-50 border border-red-700 rounded text-red-300 text-sm">
          {{ error }}
        </div>
      </div>

      <div class="modal-footer">
        <button class="btn-secondary" @click="$emit('close')">Cancel</button>
        <button class="btn-primary" :disabled="loading" @click="saveTags">
          {{ loading ? 'Saving...' : 'Save' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useSharedStore } from '../../stores/shared'

const props = defineProps({
  file: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['close', 'updated'])
const store = useSharedStore()

const tags = ref([...(props.file.tags || [])])
const newTag = ref('')
const loading = ref(false)
const error = ref(null)

function addTag() {
  const tag = newTag.value.trim()
  if (tag && !tags.value.includes(tag)) {
    tags.value.push(tag)
    newTag.value = ''
  }
}

function removeTag(tag) {
  tags.value = tags.value.filter(t => t !== tag)
}

async function saveTags() {
  loading.value = true
  error.value = null

  try {
    await store.updateFileTags(props.file.id, tags.value)
    emit('updated')
  } catch (err) {
    console.error('Update tags failed:', err)
    error.value = err.response?.data?.detail || 'Failed to update tags'
  } finally {
    loading.value = false
  }
}
</script>
