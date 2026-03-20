<template>
  <aside class="file-browser-sidebar">
    <div v-if="loading && !files.length" class="sidebar-msg">{{ loadingLabel }}</div>
    <div v-else-if="!loading && files.length === 0" class="sidebar-msg empty-project">
      {{ emptyLabel }}
    </div>

    <FileTree
      v-else
      :files="files"
      :selected-file="selectedFile"
      :search-query="searchQuery"
      @file-select="emit('file-select', $event)"
    />
  </aside>
</template>

<script setup>
import FileTree from '@/components/FileTree.vue'

defineProps({
  files: {
    type: Array,
    default: () => []
  },
  loading: {
    type: Boolean,
    default: false
  },
  selectedFile: {
    type: String,
    default: ''
  },
  searchQuery: {
    type: String,
    default: ''
  },
  loadingLabel: {
    type: String,
    default: ''
  },
  emptyLabel: {
    type: String,
    default: ''
  }
})

const emit = defineEmits(['file-select'])
</script>

<style scoped>
.file-browser-sidebar {
  width: 300px;
  min-width: 260px;
  max-width: 380px;
  border-right: 1px solid var(--border-color);
  overflow-y: auto;
  background: var(--bg-sidebar);
}

.sidebar-msg {
  padding: 24px 16px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 13px;
}

.empty-project {
  color: var(--text-secondary);
}

@media (max-width: 860px) {
  .file-browser-sidebar {
    width: 260px;
    min-width: 220px;
  }
}
</style>
