<template>
  <div class="file-browser">
    <FileBrowserToolbar
      :mode="mode"
      :breadcrumbs="currentBreadcrumbs"
      :root-label="currentRootLabel"
      :search-query="searchQuery"
      :search-placeholder="t('fileBrowser.search_files')"
      :refresh-title="t('fileBrowser.refresh')"
      :git-mode-label="t('fileBrowser.data_volumes')"
      :shared-mode-label="t('fileBrowser.shared_folders')"
      @mode-change="switchMode"
      @navigate="navigateTo"
      @search-change="handleSearch"
      @refresh="refreshFiles"
    />

    <div
      class="browser-main"
      :class="{ 'shared-mode': mode === 'shared', 'git-drag-over': showGitDropHint }"
      @dragenter="handleMainDragEnter"
      @dragover="handleMainDragOver"
      @dragleave="handleMainDragLeave"
      @drop="handleMainDrop"
    >
      <FileBrowserSidebar
        v-if="mode === 'git'"
        :files="displayedFiles"
        :loading="filesStore.loading"
        :selected-file="filesStore.currentFile || ''"
        :search-query="searchQuery"
        :loading-label="t('fileBrowser.loading')"
        :empty-label="t('fileBrowser.volume_empty')"
        @file-select="handleFileSelect"
      />

      <SharedFolders
        v-if="mode === 'shared'"
        class="shared-folders-full"
        @folder-select="handleFolderSelect"
      />

      <FileBrowserPreview
        v-if="mode === 'git'"
        :file-path="filesStore.currentFile || ''"
        :show-diff="showDiff"
        :diff-content="diffContent"
        :loading-diff="loadingDiff"
        :show-changes-title="t('fileBrowser.show_changes')"
        :show-file-label="t('fileBrowser.show_file')"
        :show-diff-label="t('fileBrowser.show_diff')"
        :open-in-explorer-label="t('fileBrowser.open_in_explorer')"
        :loading-changes-label="t('fileBrowser.loading_changes')"
        :select-file-label="t('fileBrowser.select_file_to_view')"
        @toggle-diff="toggleDiff"
        @open-in-explorer="openInExplorer"
      />

      <div v-if="mode === 'git' && showGitDropHint" class="git-drop-hint">
        <div class="hint-content">
          <svg viewBox="0 0 24 24"><path d="M14,2H6A2,2 0 0,0 4,4V20A2,2 0 0,0 6,22H18A2,2 0 0,0 20,20V8L14,2M12,18L8,14H10.5V10H13.5V14H16L12,18Z" /></svg>
          <p>{{ t('fileBrowser.drop_hint_shared') }}</p>
          <button class="btn btn-primary" @click="switchMode('shared')">{{ t('fileBrowser.open_shared_mode') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useFilesStore } from '@/stores/files'
import { useReposStore } from '@/stores/repos'
import axios from 'axios'
import SharedFolders from '@/components/SharedFolders.vue'
import FileBrowserToolbar from '@/components/file-browser/FileBrowserToolbar.vue'
import FileBrowserSidebar from '@/components/file-browser/FileBrowserSidebar.vue'
import FileBrowserPreview from '@/components/file-browser/FileBrowserPreview.vue'

const { t } = useI18n()
const filesStore = useFilesStore()
const reposStore = useReposStore()
const mode = ref('git') // 'git' | 'shared'
const searchQuery = ref('')
const showDiff = ref(false)
const diffContent = ref('')
const loadingDiff = ref(false)
const wsConnected = ref(false)
const sharedBreadcrumbs = ref([])
const showGitDropHint = ref(false)
const dragDepth = ref(0)
let ws = null
let refreshDebounceTimer = null

const breadcrumbs = computed(() => filesStore.breadcrumbs)
const currentRootLabel = computed(() => {
  return mode.value === 'git'
    ? (reposStore.currentRepo || t('fileBrowser.volume'))
    : t('fileBrowser.shared')
})

const currentBreadcrumbs = computed(() => {
  if (mode.value === 'git') {
    return breadcrumbs.value
  }

  if (sharedBreadcrumbs.value.length === 0) {
    return [{ name: 'Root', path: '/' }]
  }

  return [{ name: 'Root', path: '/' }, ...sharedBreadcrumbs.value]
})

const displayedFiles = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  if (!query) return filesStore.files

  return filesStore.files.filter((file) => {
    const name = (file.name || '').toLowerCase()
    const path = (file.path || '').toLowerCase()
    return name.includes(query) || path.includes(query)
  })
})

// Load mode from localStorage
onMounted(() => {
  const savedMode = localStorage.getItem('fileBrowserMode')
  if (savedMode === 'shared') {
    mode.value = 'shared'
  }
})

function switchMode(newMode) {
  mode.value = newMode
  localStorage.setItem('fileBrowserMode', newMode)
  
  // Reset breadcrumbs when switching
  if (newMode === 'shared') {
    sharedBreadcrumbs.value = []
  }
}

function handleFolderSelect(folderName) {
  sharedBreadcrumbs.value = [{ name: folderName, path: `/${folderName}` }]
}

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
    }
  } catch (error) {
    console.error('Ошибка загрузки diff:', error)
  } finally {
    loadingDiff.value = false
  }
}

const connectWebSocket = () => {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = window.location.hostname
  const wsUrl = `${protocol}//${host}${window.location.port ? `:${window.location.port}` : ''}/ws/files`
  
  if (ws) ws.close()
  ws = new WebSocket(wsUrl)
  
  ws.onopen = () => {
    wsConnected.value = true
  }
  
  ws.onmessage = (event) => {
    try {
      if (event.data === 'pong') return
      const message = JSON.parse(event.data)
      if (message.type === 'file_change') {
        handleFileChange(message.data)
      }
    } catch (error) {
      console.warn('WebSocket parse error:', error)
    }
  }
  
  ws.onclose = () => {
    wsConnected.value = false
    setTimeout(() => { if (ws) connectWebSocket() }, 3000)
  }
}

const handleFileChange = (_data) => {
  if (refreshDebounceTimer) clearTimeout(refreshDebounceTimer)
  refreshDebounceTimer = setTimeout(() => {
    refreshFiles()
  }, 300)
}

onMounted(async () => {
  if (!reposStore.currentRepo) {
    await reposStore.fetchRepos()
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
  if (ws) { ws.close(); ws = null }
  if (refreshDebounceTimer) clearTimeout(refreshDebounceTimer)
})

watch(() => filesStore.files, () => {
  if (!filesStore.currentFile) checkAndOpenReadme()
})

function checkAndOpenReadme() {
  if (!filesStore.files || filesStore.files.length === 0) return
  const readme = filesStore.files.find(f => 
    f.name.toLowerCase() === 'readme.md' && f.type === 'file'
  )
  if (readme) handleFileSelect(readme.path)
}

function refreshFiles() {
  filesStore.fetchFiles(filesStore.currentFolder || '/')
}

function navigateTo(path) {
  if (mode.value === 'git') {
    filesStore.fetchFiles(path)
    return
  }

  if (path === '/') {
    sharedBreadcrumbs.value = []
  }
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
    console.error('Не удалось открыть файл в проводнике:', error)
  }
}

function handleSearch(value) {
  searchQuery.value = value
}

function isFileDragEvent(event) {
  const types = Array.from(event?.dataTransfer?.types || [])
  return types.includes('Files')
}

function handleMainDragEnter(event) {
  if (mode.value !== 'git' || !isFileDragEvent(event)) return
  event.preventDefault()
  dragDepth.value += 1
  showGitDropHint.value = true
}

function handleMainDragOver(event) {
  if (mode.value !== 'git' || !isFileDragEvent(event)) return
  event.preventDefault()
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'none'
  }
  showGitDropHint.value = true
}

function handleMainDragLeave(event) {
  if (mode.value !== 'git' || !isFileDragEvent(event)) return
  event.preventDefault()
  dragDepth.value = Math.max(0, dragDepth.value - 1)
  if (dragDepth.value === 0) {
    showGitDropHint.value = false
  }
}

function handleMainDrop(event) {
  if (mode.value !== 'git' || !isFileDragEvent(event)) return
  event.preventDefault()
  dragDepth.value = 0
  showGitDropHint.value = false
}
</script>

<style scoped>
.file-browser {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
}

.browser-main {
  position: relative;
  flex: 1;
  display: flex;
  overflow: hidden;
  min-height: 0;
}

.browser-main.shared-mode {
  display: block;
}

.shared-folders-full {
  width: 100%;
  height: 100%;
}

.git-drop-hint {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 20;
}

.hint-content {
  width: min(420px, 90%);
  border: 1px dashed var(--accent);
  border-radius: 12px;
  background: var(--bg-card);
  padding: 22px 18px;
  text-align: center;
  box-shadow: 0 12px 35px rgba(0, 0, 0, 0.35);
}

.hint-content svg {
  width: 54px;
  height: 54px;
  margin: 0 auto 10px;
  fill: var(--accent);
}

.hint-content p {
  color: var(--text-primary);
  margin: 0 0 14px;
  font-size: 13px;
}

.hint-content .btn {
  margin: 0 auto;
}
</style>
