<template>
  <div class="shared-folders-container">
    <!-- Folder List View -->
    <div v-if="!sharedStore.currentFolder" class="folders-view">
      <div class="sidebar-header">
        <h3>{{ t('sharedFolders.title') }}</h3>
        <button class="btn-icon" @click="showCreateFolderModal = true" :title="t('sharedFolders.create_folder')">
          <svg viewBox="0 0 24 24" width="16" height="16"><path fill="currentColor" d="M19,13H13V19H11V13H5V11H11V5H13V11H19V13Z" /></svg>
        </button>
      </div>
      
      <div v-if="sharedStore.loading && !sharedStore.folders.length" class="sidebar-msg">{{ t('sharedFolders.loading') }}</div>
      <div v-else-if="!sharedStore.loading && sharedStore.folders.length === 0" class="sidebar-msg">
        <p>{{ t('sharedFolders.no_folders') }}</p>
        <button class="btn-create" @click="showCreateFolderModal = true">{{ t('sharedFolders.create') }}</button>
      </div>
      <div v-else class="folders-list">
        <div 
          v-for="folder in sharedStore.folders" 
          :key="folder.name"
          class="folder-item"
          @click="selectFolder(folder.name)"
          @contextmenu.prevent="showFolderContextMenu($event, folder)"
        >
          <div class="folder-icon">📁</div>
          <div class="folder-info">
            <div class="folder-name">{{ folder.name }}</div>
            <div class="folder-meta">{{ folder.file_count }} {{ t('sharedFolders.files') }} · {{ formatSize(folder.size) }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- Files View -->
    <div v-else class="files-view">
      <!-- Toolbar -->
      <div class="files-toolbar">
        <div class="toolbar-left">
          <button class="btn-back" @click="sharedStore.currentFolder = null">
            <svg viewBox="0 0 24 24" width="16" height="16"><path fill="currentColor" d="M20,11V13H8L13.5,18.5L12.08,19.92L4.16,12L12.08,4.08L13.5,5.5L8,11H20Z" /></svg>
          </button>
          <span class="folder-title">{{ sharedStore.currentFolder }}</span>
          <label class="btn-upload-toolbar">
            <input type="file" multiple @change="handleFileSelect" style="display: none" />
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
              <polyline points="17 8 12 3 7 8"/>
              <line x1="12" y1="3" x2="12" y2="15"/>
            </svg>
            {{ t('sharedFolders.upload_files') }}
          </label>
          <button 
            v-if="sharedStore.selectedFiles.length > 0" 
            class="btn-danger btn-sm"
            @click="bulkDelete"
          >
            🗑️ {{ t('sharedFolders.delete') }} ({{ sharedStore.selectedFiles.length }})
          </button>
        </div>
        <div class="toolbar-right">
          <input 
            v-model="sharedStore.searchQuery" 
            type="text" 
            :placeholder="t('sharedFolders.search')" 
            class="search-input"
          />
          <button class="btn-icon" @click="refreshFiles" :title="t('sharedFolders.refresh')">
            <svg viewBox="0 0 24 24" width="16" height="16"><path fill="currentColor" d="M17.65,6.35C16.2,4.9 14.21,4 12,4A8,8 0 0,0 4,12A8,8 0 0,0 12,20C15.73,20 18.84,17.45 19.73,14H17.65C16.83,16.33 14.61,18 12,18A6,6 0 0,1 6,12A6,6 0 0,1 12,6C13.66,6 15.14,6.69 16.22,7.78L13,11H20V4L17.65,6.35Z" /></svg>
          </button>
        </div>
      </div>

      <!-- Drag & Drop Zone -->
      <div 
        class="drop-zone"
        :class="{ 'drag-over': isDragging }"
        @drop.prevent.stop="handleDrop"
        @dragenter.prevent.stop="handleDragEnter"
        @dragover.prevent.stop="handleDragOver"
        @dragleave.prevent.stop="handleDragLeave"
      >
        <div v-if="sharedStore.files.length === 0 && !sharedStore.loading" class="drop-zone-content">
          <svg viewBox="0 0 24 24" width="48" height="48"><path fill="currentColor" d="M14,2H6A2,2 0 0,0 4,4V20A2,2 0 0,0 6,22H18A2,2 0 0,0 20,20V8L14,2M18,20H6V4H13V9H18V20M12,19L8,15H10.5V12H13.5V15H16L12,19Z" /></svg>
          <p>{{ t('sharedFolders.drop_files') }}</p>
          <label class="btn-upload">
            <input type="file" multiple @change="handleFileSelect" style="display: none" />
            {{ t('sharedFolders.select_files') }}
          </label>
        </div>

        <!-- Files List -->
        <div v-else class="files-list">
          <div 
            v-for="file in sharedStore.filteredFiles" 
            :key="file.path"
            class="file-item"
            :class="{ selected: sharedStore.selectedFiles.includes(file.path) }"
            @click="handleFileClick(file, $event)"
            @dblclick="viewFile(file)"
            @contextmenu.prevent="showFileContextMenu($event, file)"
          >
            <input 
              type="checkbox" 
              :checked="sharedStore.selectedFiles.includes(file.path)"
              @click.stop="sharedStore.toggleFileSelection(file.path)"
              class="file-checkbox"
            />
            <div class="file-icon">{{ getFileIcon(file) }}</div>
            <div class="file-info">
              <div class="file-name">{{ file.name }}</div>
              <div class="file-meta">
                {{ formatSize(file.size) }} · {{ formatDate(file.modified) }}
                <span v-if="file.tags && file.tags.length > 0" class="file-tags">
                  <span v-for="tag in file.tags.slice(0, 2)" :key="tag" class="tag-badge">{{ tag }}</span>
                  <span v-if="file.tags.length > 2" class="tag-more">+{{ file.tags.length - 2 }}</span>
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Upload Progress -->
      <div v-if="sharedStore.uploadProgress > 0" class="upload-progress">
        <div class="progress-bar">
          <div class="progress-fill" :style="{ width: sharedStore.uploadProgress + '%' }"></div>
        </div>
        <span class="progress-text">{{ sharedStore.uploadProgress }}%</span>
      </div>
    </div>

    <!-- Modals -->
    <CreateFolderModal 
      :visible="showCreateFolderModal"
      @close="showCreateFolderModal = false"
      @created="handleFolderCreated"
    />
    
    <TagsModal
      v-if="selectedFileForTags"
      :visible="showTagsModal"
      :file="selectedFileForTags"
      @close="showTagsModal = false"
      @updated="handleTagsUpdated"
    />
    
    <HistoryModal
      v-if="selectedFileForHistory"
      :visible="showHistoryModal"
      :file="selectedFileForHistory"
      @close="showHistoryModal = false"
    />

    <!-- Context Menu -->
    <SharedContextMenu
      :visible="contextMenu.visible"
      :x="contextMenu.x"
      :y="contextMenu.y"
      :item="contextMenu.item"
      :selected-count="sharedStore.selectedFiles.length"
      @close="contextMenu.visible = false"
      @action="handleContextAction"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSharedStore } from '@/stores/shared'

const { t } = useI18n()
import CreateFolderModal from './modals/CreateFolderModal.vue'
import TagsModal from './modals/TagsModal.vue'
import HistoryModal from './modals/HistoryModal.vue'
import SharedContextMenu from './SharedContextMenu.vue'

const sharedStore = useSharedStore()

const showCreateFolderModal = ref(false)
const showTagsModal = ref(false)
const showHistoryModal = ref(false)
const selectedFileForTags = ref(null)
const selectedFileForHistory = ref(null)
const isDragging = ref(false)
const dragDepth = ref(0)
const contextMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  item: null
})

onMounted(async () => {
  await sharedStore.fetchFolders()
})

async function selectFolder(name) {
  await sharedStore.selectFolder(name)
}

async function refreshFiles() {
  if (sharedStore.currentFolder) {
    await sharedStore.fetchFiles(sharedStore.currentFolder)
  }
}

function handleFileClick(file, event) {
  if (event.ctrlKey || event.metaKey) {
    sharedStore.toggleFileSelection(file.path)
  } else if (event.shiftKey && sharedStore.selectedFiles.length > 0) {
    // TODO: Range selection
    sharedStore.toggleFileSelection(file.path)
  } else {
    sharedStore.clearSelection()
    sharedStore.toggleFileSelection(file.path)
  }
}

function viewFile(file) {
  // TODO: Open file viewer
  console.log('View file:', file)
}

async function handleDrop(event) {
  if (!isFileDragEvent(event)) return

  event.preventDefault()
  event.stopPropagation()

  dragDepth.value = 0
  isDragging.value = false
  const files = Array.from(event?.dataTransfer?.files || [])

  if (files.length === 0 || !sharedStore.currentFolder) return
  
  for (const file of files) {
    try {
      await sharedStore.uploadFile(sharedStore.currentFolder, file)
    } catch (error) {
      console.error('Upload failed:', error)
    }
  }
}

function handleDragEnter(event) {
  if (!isFileDragEvent(event)) return
  dragDepth.value += 1
  isDragging.value = true
}

function handleDragOver(event) {
  if (!isFileDragEvent(event)) return
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'copy'
  }
  isDragging.value = true
}

function handleDragLeave(event) {
  if (!isFileDragEvent(event)) return

  dragDepth.value = Math.max(0, dragDepth.value - 1)
  if (dragDepth.value === 0) {
    isDragging.value = false
  }
}

function isFileDragEvent(event) {
  const types = Array.from(event?.dataTransfer?.types || [])
  return types.includes('Files')
}

async function handleFileSelect(event) {
  const files = Array.from(event.target.files)
  
  for (const file of files) {
    try {
      await sharedStore.uploadFile(sharedStore.currentFolder, file)
    } catch (error) {
      console.error('Upload failed:', error)
    }
  }
  
  event.target.value = ''
}

async function bulkDelete() {
  if (!confirm(t('sharedFolders.confirm_delete', { count: sharedStore.selectedFiles.length }))) return
  
  try {
    await sharedStore.bulkDelete(sharedStore.currentFolder, sharedStore.selectedFiles)
  } catch (error) {
    console.error('Bulk delete failed:', error)
  }
}

function showFolderContextMenu(event, folder) {
  contextMenu.value = {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    item: { ...folder, type: 'folder' }
  }
}

function showFileContextMenu(event, file) {
  contextMenu.value = {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    item: { ...file, type: 'file' }
  }
}

async function handleContextAction({ type, item }) {
  switch (type) {
    case 'delete':
      if (confirm(t('sharedFolders.confirm_delete_item', { name: item.name }))) {
        if (item.type === 'folder') {
          await sharedStore.deleteFolder(item.name)
        } else {
          await sharedStore.deleteFile(sharedStore.currentFolder, item.path)
        }
      }
      break
    case 'tags':
      selectedFileForTags.value = item
      showTagsModal.value = true
      break
    case 'history':
      selectedFileForHistory.value = item
      showHistoryModal.value = true
      break
    case 'download':
      downloadFile(item)
      break
    case 'delete-multiple':
      await bulkDelete()
      break
  }
}

function downloadFile(file) {
  const url = `/api/shared/download?folder=${sharedStore.currentFolder}&path=${file.path}`
  window.open(url, '_blank')
}

function handleFolderCreated() {
  sharedStore.fetchFolders()
}

function handleTagsUpdated() {
  refreshFiles()
}

function getFileIcon(file) {
  if (file.is_dir) return '📁'
  const ext = file.name.split('.').pop().toLowerCase()
  const icons = {
    'md': '📝', 'txt': '📄', 'pdf': '📕',
    'jpg': '🖼️', 'png': '🖼️', 'gif': '🖼️',
    'zip': '📦', 'rar': '📦',
    'js': '📜', 'py': '🐍', 'vue': '💚'
  }
  return icons[ext] || '📄'
}

function formatSize(bytes) {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i]
}

function formatDate(dateString) {
  const date = new Date(dateString)
  const now = new Date()
  const diff = now - date
  
  if (diff < 60000) return 'только что'
  if (diff < 3600000) return Math.floor(diff / 60000) + ' мин назад'
  if (diff < 86400000) return Math.floor(diff / 3600000) + ' ч назад'
  if (diff < 604800000) return Math.floor(diff / 86400000) + ' дн назад'
  
  return date.toLocaleDateString('ru-RU')
}
</script>

<style scoped>
.shared-folders-container { display: flex; flex-direction: column; height: 100%; background: var(--bg-primary); }
.folders-view { display: flex; flex-direction: column; height: 100%; }
.files-view { display: flex; flex-direction: column; height: 100%; }
.sidebar-header { display: flex; justify-content: space-between; align-items: center; padding: 12px 15px; border-bottom: 1px solid var(--border-color); }
.sidebar-header h3 { margin: 0; font-size: 13px; font-weight: 600; color: var(--text-bright); text-transform: uppercase; }
.btn-back { background: none; border: none; color: #858585; cursor: pointer; padding: 4px 8px; display: flex; align-items: center; border-radius: 3px; transition: all 0.2s; }
.btn-back:hover { background: #3c3c3c; color: white; }
.btn-upload-toolbar { display: flex; align-items: center; gap: 6px; padding: 5px 12px; background: var(--accent); color: white; border-radius: 4px; cursor: pointer; font-size: 12px; font-weight: 500; transition: all 0.2s; }
.btn-upload-toolbar:hover { background: var(--accent-hover); }
.btn-upload-toolbar svg { flex-shrink: 0; }
.btn-icon { background: none; border: none; color: #858585; cursor: pointer; padding: 4px; display: flex; align-items: center; justify-content: center; border-radius: 3px; }
.btn-icon:hover { background: #3c3c3c; color: white; }
.sidebar-msg { padding: 20px; text-align: center; color: #858585; font-size: 13px; }
.btn-create { background: var(--accent); color: white; border: none; padding: 6px 12px; border-radius: 4px; cursor: pointer; font-size: 12px; margin-top: 10px; }
.folders-list { flex: 1; overflow-y: auto; padding: 8px; }
.folder-item { display: flex; align-items: center; padding: 8px 10px; background: var(--bg-sidebar); border: 1px solid var(--border-color); border-radius: 4px; cursor: pointer; transition: background 0.2s; margin-bottom: 6px; }
.folder-item:hover { background: #3c3c3c; }
.folder-icon { font-size: 20px; margin-right: 10px; }
.folder-info { flex: 1; min-width: 0; }
.folder-name { font-size: 13px; font-weight: 500; color: var(--text-bright); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.folder-meta { font-size: 10px; color: #858585; margin-top: 2px; }
.drop-zone { flex: 1; overflow-y: auto; position: relative; }
.drop-zone.drag-over { background: rgba(0, 122, 204, 0.1); border: 2px dashed var(--accent); }
.drop-zone-content { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; color: #858585; padding: 20px; }
.drop-zone-content svg { margin-bottom: 15px; }
.drop-zone-content p { margin-bottom: 15px; font-size: 14px; }
.btn-upload { background: var(--accent); color: white; padding: 8px 16px; border-radius: 4px; cursor: pointer; font-size: 13px; border: none; }
.files-list { padding: 10px; }
.files-toolbar { display: flex; justify-content: space-between; align-items: center; padding: 10px 15px; border-bottom: 1px solid var(--border-color); background: var(--bg-sidebar); }
.toolbar-left { display: flex; align-items: center; gap: 12px; }
.folder-title { font-size: 14px; font-weight: 600; color: var(--text-bright); }
.btn-sm { font-size: 11px; padding: 4px 10px; border-radius: 3px; border: none; cursor: pointer; }
.btn-danger { background: #dc2626; color: white; }
.toolbar-right { display: flex; align-items: center; gap: 8px; }
.search-input { background: #3c3c3c; border: 1px solid var(--border-color); color: white; font-size: 11px; padding: 4px 8px; border-radius: 3px; width: 150px; }
.file-item { display: flex; align-items: center; padding: 8px 10px; border: 1px solid var(--border-color); border-radius: 4px; margin-bottom: 6px; cursor: pointer; transition: all 0.2s; }
.file-item:hover { background: #3c3c3c; }
.file-item.selected { background: rgba(0, 122, 204, 0.2); border-color: var(--accent); }
.file-checkbox { margin-right: 10px; }
.file-icon { font-size: 20px; margin-right: 10px; }
.file-info { flex: 1; min-width: 0; }
.file-name { font-size: 13px; color: var(--text-bright); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.file-meta { font-size: 10px; color: #858585; margin-top: 2px; display: flex; align-items: center; gap: 8px; }
.file-tags { display: flex; gap: 4px; }
.tag-badge { background: var(--accent); color: white; padding: 2px 6px; border-radius: 3px; font-size: 9px; }
.tag-more { color: #858585; font-size: 9px; }
.upload-progress { padding: 10px 15px; border-top: 1px solid var(--border-color); background: var(--bg-sidebar); }
.progress-bar { height: 4px; background: #3c3c3c; border-radius: 2px; overflow: hidden; margin-bottom: 5px; }
.progress-fill { height: 100%; background: var(--accent); transition: width 0.3s; }
.progress-text { font-size: 11px; color: #858585; }
</style>
