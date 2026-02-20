<template>
  <Transition name="modal">
    <div v-if="visible" class="modal-overlay" @click.self="handleClose">
      <div class="modal-container">
        <!-- Header -->
        <div class="modal-header">
          <h2 class="modal-title">Manage Tags</h2>
          <button class="close-button" @click="handleClose" aria-label="Close">
            <span>×</span>
          </button>
        </div>

        <!-- File Info -->
        <div class="file-info">
          <span class="file-icon">🏷️</span>
          <div class="file-details">
            <div class="file-path">{{ filePath }}</div>
            <div class="file-folder">{{ folder }}</div>
          </div>
        </div>

        <!-- Current Tags -->
        <div class="tags-section">
          <label class="section-label">Current Tags ({{ tags.length }}/{{ MAX_TAGS }})</label>
          <div v-if="tags.length > 0" class="tags-list">
            <TransitionGroup name="tag">
              <div
                v-for="tag in tags"
                :key="tag"
                class="tag-badge"
                :style="{ backgroundColor: getTagColor(tag) }"
              >
                <span class="tag-name">{{ tag }}</span>
                <button
                  class="tag-remove"
                  @click="removeTag(tag)"
                  aria-label="Remove tag"
                >
                  ×
                </button>
              </div>
            </TransitionGroup>
          </div>
          <div v-else class="empty-state">
            No tags yet. Add some below!
          </div>
        </div>

        <!-- Add Tag Input -->
        <div class="add-tag-section">
          <label class="section-label">Add New Tag</label>
          <div class="input-group">
            <input
              ref="tagInput"
              v-model="newTag"
              type="text"
              class="tag-input"
              placeholder="Enter tag name..."
              :disabled="tags.length >= MAX_TAGS"
              @keydown.enter.prevent="addTag"
              @input="handleInput"
              @keydown.down.prevent="navigateSuggestions('down')"
              @keydown.up.prevent="navigateSuggestions('up')"
              @keydown.esc="closeSuggestions"
            />
            <button
              class="add-button"
              :disabled="!canAddTag"
              @click="addTag"
            >
              Add
            </button>
          </div>
          
          <!-- Validation Error -->
          <div v-if="validationError" class="validation-error">
            {{ validationError }}
          </div>

          <!-- Autocomplete Suggestions -->
          <Transition name="suggestions">
            <div v-if="showSuggestions && filteredSuggestions.length > 0" class="suggestions-list">
              <div
                v-for="(suggestion, index) in filteredSuggestions"
                :key="suggestion"
                class="suggestion-item"
                :class="{ active: index === selectedSuggestionIndex }"
                @click="selectSuggestion(suggestion)"
                @mouseenter="selectedSuggestionIndex = index"
              >
                <span
                  class="suggestion-badge"
                  :style="{ backgroundColor: getTagColor(suggestion) }"
                >
                  {{ suggestion }}
                </span>
              </div>
            </div>
          </Transition>
        </div>

        <!-- Actions -->
        <div class="modal-actions">
          <button class="button button-secondary" @click="handleClose">
            Cancel
          </button>
          <button class="button button-primary" @click="handleSave">
            Save Changes
          </button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'

const props = defineProps({
  visible: {
    type: Boolean,
    required: true
  },
  folder: {
    type: String,
    required: true
  },
  filePath: {
    type: String,
    required: true
  },
  currentTags: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['close', 'save'])

// Constants
const MAX_TAGS = 10
const TAG_PATTERN = /^[a-zA-Z0-9-]+$/

// Popular tags for autocomplete
const POPULAR_TAGS = [
  'important',
  'todo',
  'bug',
  'feature',
  'documentation',
  'refactor',
  'test',
  'review',
  'urgent',
  'archived',
  'draft',
  'approved',
  'deprecated',
  'experimental',
  'production',
  'development'
]

// State
const tags = ref([])
const newTag = ref('')
const validationError = ref('')
const tagInput = ref(null)
const showSuggestions = ref(false)
const selectedSuggestionIndex = ref(0)

// Computed
const canAddTag = computed(() => {
  return newTag.value.trim() !== '' && 
         tags.value.length < MAX_TAGS && 
         !validationError.value
})

const filteredSuggestions = computed(() => {
  const query = newTag.value.toLowerCase().trim()
  if (!query) return []
  
  return POPULAR_TAGS
    .filter(tag => 
      tag.toLowerCase().includes(query) && 
      !tags.value.includes(tag)
    )
    .slice(0, 5)
})

// Watch for visibility changes
watch(() => props.visible, (isVisible) => {
  if (isVisible) {
    // Reset state when modal opens
    tags.value = [...props.currentTags]
    newTag.value = ''
    validationError.value = ''
    showSuggestions.value = false
    selectedSuggestionIndex.value = 0
    
    // Focus input
    nextTick(() => {
      tagInput.value?.focus()
    })
  }
})

// Methods
function validateTag(tag) {
  const trimmed = tag.trim()
  
  if (!trimmed) {
    return 'Tag cannot be empty'
  }
  
  if (trimmed.length < 2) {
    return 'Tag must be at least 2 characters'
  }
  
  if (trimmed.length > 30) {
    return 'Tag must be less than 30 characters'
  }
  
  if (!TAG_PATTERN.test(trimmed)) {
    return 'Tag can only contain letters, numbers, and hyphens'
  }
  
  if (tags.value.includes(trimmed)) {
    return 'Tag already exists'
  }
  
  if (tags.value.length >= MAX_TAGS) {
    return `Maximum ${MAX_TAGS} tags allowed`
  }
  
  return null
}

function handleInput() {
  const error = validateTag(newTag.value)
  validationError.value = error || ''
  
  // Show suggestions if input is valid and not empty
  showSuggestions.value = newTag.value.trim() !== '' && !error
  selectedSuggestionIndex.value = 0
}

function addTag() {
  const trimmed = newTag.value.trim()
  const error = validateTag(trimmed)
  
  if (error) {
    validationError.value = error
    return
  }
  
  tags.value.push(trimmed)
  newTag.value = ''
  validationError.value = ''
  showSuggestions.value = false
  
  // Focus back to input
  nextTick(() => {
    tagInput.value?.focus()
  })
}

function removeTag(tag) {
  const index = tags.value.indexOf(tag)
  if (index > -1) {
    tags.value.splice(index, 1)
  }
}

function navigateSuggestions(direction) {
  if (!showSuggestions.value || filteredSuggestions.value.length === 0) {
    return
  }
  
  if (direction === 'down') {
    selectedSuggestionIndex.value = 
      (selectedSuggestionIndex.value + 1) % filteredSuggestions.value.length
  } else {
    selectedSuggestionIndex.value = 
      (selectedSuggestionIndex.value - 1 + filteredSuggestions.value.length) % 
      filteredSuggestions.value.length
  }
}

function selectSuggestion(suggestion) {
  newTag.value = suggestion
  showSuggestions.value = false
  addTag()
}

function closeSuggestions() {
  showSuggestions.value = false
  selectedSuggestionIndex.value = 0
}

function getTagColor(tag) {
  // Generate consistent color from tag name using hash
  let hash = 0
  for (let i = 0; i < tag.length; i++) {
    hash = tag.charCodeAt(i) + ((hash << 5) - hash)
  }
  
  // Convert to HSL for better color distribution
  const hue = Math.abs(hash % 360)
  const saturation = 60 + (Math.abs(hash) % 20) // 60-80%
  const lightness = 45 + (Math.abs(hash >> 8) % 15) // 45-60%
  
  return `hsl(${hue}, ${saturation}%, ${lightness}%)`
}

function handleClose() {
  emit('close')
}

function handleSave() {
  emit('save', { tags: tags.value })
}
</script>

<style scoped>
/* Modal Overlay */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(2px);
}

.modal-container {
  background: #1e1e1e;
  border-radius: 8px;
  width: 90%;
  max-width: 600px;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.5);
  border: 1px solid #333;
}

/* Header */
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid #333;
}

.modal-title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #e0e0e0;
}

.close-button {
  background: none;
  border: none;
  color: #888;
  font-size: 32px;
  line-height: 1;
  cursor: pointer;
  padding: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  transition: all 0.2s;
}

.close-button:hover {
  background: #333;
  color: #fff;
}

/* File Info */
.file-info {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 24px;
  background: #252525;
  border-bottom: 1px solid #333;
}

.file-icon {
  font-size: 24px;
}

.file-details {
  flex: 1;
  min-width: 0;
}

.file-path {
  font-size: 14px;
  font-weight: 500;
  color: #e0e0e0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.file-folder {
  font-size: 12px;
  color: #888;
  margin-top: 2px;
}

/* Tags Section */
.tags-section {
  padding: 20px 24px;
}

.section-label {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: #aaa;
  margin-bottom: 12px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.tags-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  color: #fff;
  transition: all 0.2s;
}

.tag-name {
  user-select: none;
}

.tag-remove {
  background: rgba(0, 0, 0, 0.2);
  border: none;
  color: #fff;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  padding: 0;
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 3px;
  transition: all 0.2s;
}

.tag-remove:hover {
  background: rgba(0, 0, 0, 0.4);
}

.empty-state {
  padding: 24px;
  text-align: center;
  color: #666;
  font-size: 14px;
  font-style: italic;
}

/* Add Tag Section */
.add-tag-section {
  padding: 0 24px 20px;
  position: relative;
}

.input-group {
  display: flex;
  gap: 8px;
}

.tag-input {
  flex: 1;
  padding: 10px 12px;
  background: #2a2a2a;
  border: 1px solid #444;
  border-radius: 6px;
  color: #e0e0e0;
  font-size: 14px;
  outline: none;
  transition: all 0.2s;
}

.tag-input:focus {
  border-color: #007acc;
  background: #2d2d2d;
}

.tag-input:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.add-button {
  padding: 10px 20px;
  background: #007acc;
  border: none;
  border-radius: 6px;
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.add-button:hover:not(:disabled) {
  background: #005a9e;
}

.add-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Validation Error */
.validation-error {
  margin-top: 8px;
  padding: 8px 12px;
  background: rgba(220, 38, 38, 0.1);
  border: 1px solid rgba(220, 38, 38, 0.3);
  border-radius: 4px;
  color: #ef4444;
  font-size: 13px;
}

/* Suggestions */
.suggestions-list {
  position: absolute;
  top: 100%;
  left: 24px;
  right: 24px;
  margin-top: 4px;
  background: #2a2a2a;
  border: 1px solid #444;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
  z-index: 10;
  overflow: hidden;
}

.suggestion-item {
  padding: 10px 12px;
  cursor: pointer;
  transition: background 0.15s;
}

.suggestion-item:hover,
.suggestion-item.active {
  background: #333;
}

.suggestion-badge {
  display: inline-block;
  padding: 4px 8px;
  border-radius: 4px;
  font-size: 13px;
  font-weight: 500;
  color: #fff;
}

/* Modal Actions */
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid #333;
}

.button {
  padding: 10px 20px;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.button-secondary {
  background: #333;
  color: #e0e0e0;
}

.button-secondary:hover {
  background: #404040;
}

.button-primary {
  background: #007acc;
  color: #fff;
}

.button-primary:hover {
  background: #005a9e;
}

/* Transitions */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.2s, opacity 0.2s;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.95);
  opacity: 0;
}

.tag-enter-active,
.tag-leave-active {
  transition: all 0.3s;
}

.tag-enter-from {
  opacity: 0;
  transform: scale(0.8);
}

.tag-leave-to {
  opacity: 0;
  transform: scale(0.8);
}

.tag-move {
  transition: transform 0.3s;
}

.suggestions-enter-active,
.suggestions-leave-active {
  transition: all 0.2s;
}

.suggestions-enter-from,
.suggestions-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* Scrollbar */
.modal-container::-webkit-scrollbar {
  width: 8px;
}

.modal-container::-webkit-scrollbar-track {
  background: #1e1e1e;
}

.modal-container::-webkit-scrollbar-thumb {
  background: #444;
  border-radius: 4px;
}

.modal-container::-webkit-scrollbar-thumb:hover {
  background: #555;
}
</style>
