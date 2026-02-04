<template>
  <div class="file-browser">
    <!-- Browser Toolbar -->
    <div class="browser-toolbar">
      <div class="breadcrumb-nav">
        <span class="root-label">{{ reposStore.currentRepo }}</span>
        <span class="separator">/</span>
        <div v-for="(crumb, index) in breadcrumbs" :key="index" class="crumb-item">
          <span @click="navigateTo(crumb.path)" class="crumb-link">{{ crumb.name }}</span>
          <span v-if="index < breadcrumbs.length - 1" class="separator">/</span>
        </div>
      </div>
      <div class="toolbar-actions">
        <input 
          type="text" 
          v-model="searchQuery"
          @input="handleSearch"
          placeholder="Search files..." 
          class="search-input"
        />
        <button @click="refreshFiles" class="icon-btn" title="Refresh">
          <svg viewBox="0 0 24 24"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/></svg>
        </button>
      </div>
    </div>

    <!-- Main Content Area -->
    <div class="browser-main">
      <!-- File Tree (Sidebar) -->
      <div class="tree-sidebar">
        <div v-if="filesStore.loading" class="sidebar-msg">Loading...</div>
        <FileTree 
          v-else
          :files="filesStore.files"
          :selected-file="filesStore.currentFile || ''"
          @file-select="handleFileSelect"
        />
      </div>

      <!-- Preview Area -->
      <div class="preview-area">
        <div v-if="filesStore.currentFile" class="preview-container">
          <div class="preview-header">
            <span class="file-path">{{ filesStore.currentFile }}</span>
            <button @click="openInExplorer" class="btn mini">Open in Explorer</button>
          </div>
          <div class="preview-body">
            <FileViewer :file-path="filesStore.currentFile" :content="filesStore.fileContent" />
          </div>
        </div>
        <div v-else class="empty-preview">
          <div class="msg">
            <svg viewBox="0 0 24 24"><path d="M13 9h5.5L13 3.5V9M6 2h8l6 6v12a2 2 0 01-2 2H6a2 2 0 01-2-2V4c0-1.1.9-2 2-2m0 18h12V10h-7V3H6v17z"/></svg>
            <p>Select a file to preview</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useFilesStore } from '@/stores/files'
import { useReposStore } from '@/stores/repos'
import FileTree from '@/components/FileTree.vue'
import FileViewer from '@/components/FileViewer.vue'

const filesStore = useFilesStore()
const reposStore = useReposStore()
const searchQuery = ref('')

const breadcrumbs = computed(() => {
  if (!filesStore.currentFolder || filesStore.currentFolder === '/') return []
  const parts = filesStore.currentFolder.split('/').filter(Boolean)
  let path = ''
  return parts.map(p => {
    path += '/' + p
    return { name: p, path }
  })
})

onMounted(() => {
  filesStore.fetchFiles('/')
})

function refreshFiles() {
  filesStore.fetchFiles(filesStore.currentFolder)
}

function navigateTo(path) {
  filesStore.fetchFiles(path)
}

async function handleFileSelect(path) {
  await filesStore.fetchFileContent(path)
}

async function openInExplorer() {
  if (!filesStore.currentFile) return
  await fetch(`/api/open?file=${encodeURIComponent(filesStore.currentFile)}`)
}

async function handleSearch() {
  if (searchQuery.value.trim()) {
    await filesStore.searchFiles(searchQuery.value)
  } else {
    refreshFiles()
  }
}
</script>

<style scoped>
.file-browser {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
}

.browser-toolbar {
  height: 35px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 15px;
  background: var(--bg-sidebar);
  border-bottom: 1px solid var(--border-color);
}

.breadcrumb-nav {
  display: flex;
  align-items: center;
  font-size: 12px;
  color: #858585;
}

.root-label { font-weight: bold; color: var(--text-bright); }
.separator { margin: 0 8px; color: #555; }
.crumb-link { cursor: pointer; }
.crumb-link:hover { color: var(--text-bright); text-decoration: underline; }

.toolbar-actions { display: flex; align-items: center; gap: 10px; }

.search-input {
  background: #3c3c3c;
  border: 1px solid var(--border-color);
  color: white;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 3px;
  width: 180px;
}

.icon-btn {
  background: none;
  border: none;
  color: #858585;
  width: 20px;
  height: 20px;
  cursor: pointer;
}
.icon-btn svg { fill: currentColor; }
.icon-btn:hover { color: white; }

.browser-main {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.tree-sidebar {
  width: 250px;
  border-right: 1px solid var(--border-color);
  overflow-y: auto;
}

.preview-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.preview-container {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.preview-header {
  padding: 8px 15px;
  background: var(--bg-sidebar);
  border-bottom: 1px solid var(--border-color);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.file-path { font-size: 11px; color: #858585; font-family: monospace; }

.preview-body {
  flex: 1;
  overflow: auto;
}

.empty-preview {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #3c3c3c;
}

.empty-preview .msg { text-align: center; }
.empty-preview svg { width: 64px; height: 64px; fill: currentColor; margin-bottom: 10px; }

.btn.mini {
  font-size: 10px;
  padding: 2px 8px;
  background: #3a3d41;
  color: white;
  border: none;
  border-radius: 2px;
  cursor: pointer;
}
</style>
