<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal-content">
      <div class="modal-header">
        <h3 class="text-lg font-medium text-white">{{ t('modals.tags.title') }}</h3>
        <button class="text-gray-400 hover:text-white" @click="$emit('close')">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div class="modal-body">
        <div class="mb-4">
          <p class="text-sm text-gray-400 mb-2">{{ t('modals.tags.file', { name: file.name }) }}</p>
        </div>

        <div class="mb-4">
          <label class="block text-sm font-medium text-gray-300 mb-2">{{ t('modals.tags.tags') }}</label>
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
              :placeholder="t('modals.tags.add_new_tag')"
              class="flex-1 px-3 py-2 bg-gray-800 border border-gray-600 rounded text-white focus:outline-none focus:border-blue-500"
              @keyup.enter="addTag"
            >
            <button class="btn-primary" @click="addTag">{{ t('modals.tags.add') }}</button>
          </div>
        </div>

        <div v-if="error" class="mb-4 p-3 bg-red-900 bg-opacity-50 border border-red-700 rounded text-red-300 text-sm">
          {{ error }}
        </div>
      </div>

      <div class="modal-footer">
        <button class="btn-secondary" @click="$emit('close')">{{ t('modals.tags.cancel') }}</button>
        <button class="btn-primary" :disabled="loading" @click="saveTags">
          {{ loading ? t('modals.tags.saving') : t('modals.tags.save') }}
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
    error.value = err.response?.data?.detail || t('modals.tags.failed_to_update')
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
