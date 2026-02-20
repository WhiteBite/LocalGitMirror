<template>
  <div class="example-container">
    <h1>TagsManager Example</h1>
    
    <!-- File Card -->
    <div class="file-card">
      <div class="file-header">
        <span class="file-icon">📄</span>
        <div class="file-info">
          <div class="file-name">{{ currentFile }}</div>
          <div class="file-path">{{ currentFolder }}</div>
        </div>
        <button class="manage-tags-btn" @click="showTagsModal = true">
          🏷️ Manage Tags
        </button>
      </div>
      
      <!-- Current Tags Display -->
      <div v-if="fileTags.length > 0" class="tags-display">
        <span class="tags-label">Tags:</span>
        <div class="tags-list">
          <span
            v-for="tag in fileTags"
            :key="tag"
            class="tag"
            :style="{ backgroundColor: getTagColor(tag) }"
          >
            {{ tag }}
          </span>
        </div>
      </div>
      <div v-else class="no-tags">
        No tags yet
      </div>
    </div>

    <!-- TagsManager Modal -->
    <TagsManager
      :visible="showTagsModal"
      :folder="currentFolder"
      :file-path="currentFile"
      :current-tags="fileTags"
      @close="showTagsModal = false"
      @save="handleSaveTags"
    />

    <!-- Log -->
    <div v-if="lastAction" class="log">
      <strong>Last Action:</strong> {{ lastAction }}
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import TagsManager from './TagsManager.vue'

const showTagsModal = ref(false)
const currentFolder = ref('/projects/LocalGitMirror')
const currentFile = ref('README.md')
const fileTags = ref(['important', 'documentation', 'todo'])
const lastAction = ref('')

function handleSaveTags({ tags }) {
  fileTags.value = tags
  showTagsModal.value = false
  lastAction.value = `Saved ${tags.length} tags: ${tags.join(', ')}`
  
  // In real app, call API here:
  // await axios.post('/api/files/tags', {
  //   path: currentFile.value,
  //   tags: tags
  // })
}

function getTagColor(tag) {
  let hash = 0
  for (let i = 0; i < tag.length; i++) {
    hash = tag.charCodeAt(i) + ((hash << 5) - hash)
  }
  const hue = Math.abs(hash % 360)
  const saturation = 60 + (Math.abs(hash) % 20)
  const lightness = 45 + (Math.abs(hash >> 8) % 15)
  return `hsl(${hue}, ${saturation}%, ${lightness}%)`
}
</script>

<style scoped>
.example-container {
  padding: 40px;
  max-width: 800px;
  margin: 0 auto;
  background: #1e1e1e;
  min-height: 100vh;
  color: #e0e0e0;
}

h1 {
  margin-bottom: 32px;
  color: #fff;
}

.file-card {
  background: #252525;
  border: 1px solid #333;
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 24px;
}

.file-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.file-icon {
  font-size: 32px;
}

.file-info {
  flex: 1;
}

.file-name {
  font-size: 18px;
  font-weight: 600;
  color: #fff;
}

.file-path {
  font-size: 13px;
  color: #888;
  margin-top: 4px;
}

.manage-tags-btn {
  padding: 8px 16px;
  background: #007acc;
  border: none;
  border-radius: 6px;
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s;
}

.manage-tags-btn:hover {
  background: #005a9e;
}

.tags-display {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid #333;
}

.tags-label {
  font-size: 13px;
  color: #888;
  font-weight: 600;
}

.tags-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag {
  padding: 4px 10px;
  border-radius: 4px;
  font-size: 13px;
  font-weight: 500;
  color: #fff;
}

.no-tags {
  padding-top: 16px;
  border-top: 1px solid #333;
  color: #666;
  font-style: italic;
  font-size: 14px;
}

.log {
  padding: 16px;
  background: #2a2a2a;
  border: 1px solid #444;
  border-radius: 6px;
  font-size: 14px;
  color: #aaa;
}

.log strong {
  color: #fff;
}
</style>
