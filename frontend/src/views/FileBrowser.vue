<template>
  <div class="file-browser">
    <!-- Browser Toolbar -->
    <div class="browser-toolbar">
      <div class="breadcrumb-nav">
        <span class="root-label">{{ reposStore.currentRepo }}</span>
        <span class="separator">/</span>
        <div v-for="(crumb, index) in breadcrumbs" :key="index" class="crumb-item">
          <span class="crumb-link" @click="navigateTo(crumb.path)">{{ crumb.name }}</span>
          <span v-if="index < breadcrumbs.length - 1" class="separator">/</span>
        </div>
      </div>
      <div class="toolbar-actions">
        <input 
          v-model="searchQuery" 
          type="text"
          placeholder="Search files..."
          class="search-input" 
          @input="handleSearch"
        />
        <button class="icon-btn" title="Refresh" @click="refreshFiles">
          <svg viewBox="0 0 24 24"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" /></svg>
        </button>
      </div>
    </div>

    <!-- Main Content Area -->
    <div class="browser-main">
      <!-- File Tree (Sidebar) -->
      <div class="tree-sidebar">
        <div v-if="filesStore.loading && !filesStore.files.length" class="sidebar-msg">Loading...</div>
        <div v-else-if="!filesStore.loading && filesStore.files.length === 0" class="sidebar-msg empty-project">
          Project is empty
        </div>
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
            <div class="actions">
              <button 
                class="btn mini" 
                :class="{ 'active': showDiff }" 
                title="Toggle Diff View"
                @click="toggleDiff"
              >
                {{ showDiff ? 'Show File' : 'Show Diff' }}
              </button>
              <button class="btn mini" @click="openInExplorer">Open in Explorer</button>
            </div>
          </div>
          <div class="preview-body">
            <div v-if="loadingDiff" class="loading-overlay">Loading diff...</div>
            <DiffViewer v-else-if="showDiff" :diff="diffContent" />
            <FileViewer v-else :file-path="filesStore.currentFile" />
          </div>
        </div>
        <div v-else class="empty-preview">
          <div class="msg">
            <svg viewBox="0 0 24 24"><path d="M13 9h5.5L13 3.5V9M6 2h8l6 6v12a2 2 0 01-2 2H6a2 2 0 01-2-2V4c0-1.1.9-2 2-2m0 18h12V10h-7V3H6v17z" /></svg>
            <p>Select a file to preview</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { useFilesStore } from '@/stores/files'
import { useReposStore } from '@/stores/repos'
import axios from 'axios'
import FileTree from '@/components/FileTree.vue'
import FileViewer from '@/components/FileViewer.vue'
import DiffViewer from '@/components/DiffViewer.vue'

const filesStore = useFilesStore()
const reposStore = useReposStore()
const searchQuery = ref('')
const showDiff = ref(false)
const diffContent = ref('')
const loadingDiff = ref(false)
const wsConnected = ref(false)
let ws = null
let refreshDebounceTimer = null

const breadcrumbs = computed(() => filesStore.breadcrumbs)

// Reset diff mode when file changes
watch(() => filesStore.currentFile, () => {
  showDiff.value = false
  diffContent.value = ''
})

async function toggleDiff() {
  if (showDiff.value) {
    showDiff.value = false
    return
  }
  
  if (!filesStore.currentFile) return
  
  loadingDiff.value = true
  try {
    const response = await axios.get('/api/git/diff', {
      params: { file: filesStore.currentFile }
    })
    
    if (response.data.success) {
      diffContent.value = response.data.diff
      showDiff.value = true
    } else {
      console.error('Failed to load diff:', response.data.message)
    }
  } catch (error) {
    console.error('Error loading diff:', error)
  } finally {
    loadingDiff.value = false
  }
}


// Connect to WebSocket
const connectWebSocket = () => {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  // Use port 8000 for backend API if we are on dev port 5173
  const host = window.location.hostname
  const port = window.location.port
  const wsUrl = `${protocol}//${host}:${port}/ws/files`
  
  if (ws) {
    ws.close()
  }

  ws = new WebSocket(wsUrl)
  
  ws.onopen = () => {
    wsConnected.value = true
    console.log('WebSocket connected to file watcher')
  }
  
  ws.onmessage = (event) => {
    try {
      if (event.data === 'pong') return
      
      const message = JSON.parse(event.data)
      if (message.type === 'file_change') {
        handleFileChange(message.data)
      }
    } catch (error) {
      console.error('Failed to parse WebSocket message:', error, event.data)
    }
  }
  
  ws.onerror = (error) => {
    console.error('WebSocket error:', error)
    wsConnected.value = false
  }
  
  ws.onclose = () => {
    wsConnected.value = false
    console.log('WebSocket disconnected, reconnecting in 3s...')
    
    // Reconnect after 3 seconds
    setTimeout(() => {
      // Only reconnect if component is still mounted (simple check via ws variable)
      if (ws) {
        connectWebSocket()
      }
    }, 3000)
  }
}

// Handle file change events with debounce
const handleFileChange = (_data) => {
  // Clear existing timer
  if (refreshDebounceTimer) {
    clearTimeout(refreshDebounceTimer)
  }

  // Set new timer (300ms debounce)
  refreshDebounceTimer = setTimeout(() => {
    // Check if the change is relevant to current view
    // Ideally we should check if changed file is in current folder or subfolder
    // For now, simpler to just refresh if we are looking at the file tree
    refreshFiles()
  }, 300)
}

onMounted(async () => {
  if (!reposStore.currentRepo) {
    await reposStore.fetchRepos()
    // Try to get status to see what's active on backend
    const statusResp = await axios.get('/api/status')
    if (statusResp.data.current_repo) {
        reposStore.currentRepo = statusResp.data.current_repo
    }
  }
  await filesStore.fetchFiles('/')
  checkAndOpenReadme()
  connectWebSocket()
})

onUnmounted(() => {
  if (ws) {
    ws.close()
    ws = null
  }
  if (refreshDebounceTimer) {
    clearTimeout(refreshDebounceTimer)
  }
})

// Watch for file list changes
watch(() => filesStore.files, () => {
  if (!filesStore.currentFile) {
    checkAndOpenReadme()
  }
})

function checkAndOpenReadme() {
  if (!filesStore.files || filesStore.files.length === 0) return
  
  const readme = filesStore.files.find(f => 
    f.name.toLowerCase() === 'readme.md' && f.type === 'file'
  )
  
  if (readme) {
    handleFileSelect(readme.path)
  }
}

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
  try {
    await axios.post('/api/editor/open', null, {
      params: { file: filesStore.currentFile }
    })
  } catch (error) {
    console.error('Failed to open explorer:', error)
  }
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
  width: 280px;
  border-right: 1px solid var(--border-color);
  overflow-y: auto;
}

.sidebar-msg {
  padding: 20px;
  text-align: center;
  color: #858585;
  font-size: 13px;
}

.empty-project {
  color: #a0a0a0;
  font-style: italic;
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

.preview-header .actions {
  display: flex;
  gap: 8px;
}

.btn.mini.active {
  background: var(--accent);
  color: white;
}

.loading-overlay {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #858585;
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
