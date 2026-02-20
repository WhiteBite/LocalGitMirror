import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import axios from 'axios'

export const useSharedStore = defineStore('shared', () => {
  // State
  const folders = ref([])
  const currentFolder = ref(null)
  const currentPath = ref([])
  const files = ref([])
  const loading = ref(false)
  const error = ref(null)
  const selectedFiles = ref(new Set())

  // Computed
  const currentFolderData = computed(() => {
    if (!currentFolder.value) return null
    return folders.value.find(f => f.id === currentFolder.value)
  })

  const breadcrumbs = computed(() => {
    const crumbs = [{ name: 'Shared', path: [] }]
    currentPath.value.forEach((folder, index) => {
      crumbs.push({
        name: folder.name,
        path: currentPath.value.slice(0, index + 1)
      })
    })
    return crumbs
  })

  const selectedFilesArray = computed(() => {
    return files.value.filter(f => selectedFiles.value.has(f.id))
  })

  // Actions
  async function fetchFolders() {
    loading.value = true
    error.value = null
    try {
      const response = await axios.get('/api/shared/folders')
      folders.value = response.data.folders || []
    } catch (err) {
      console.error('Failed to fetch folders:', err)
      error.value = err.response?.data?.detail || 'Failed to load folders'
    } finally {
      loading.value = false
    }
  }

  async function fetchFiles(folderId, subPath = []) {
    loading.value = true
    error.value = null
    try {
      const response = await axios.get('/api/shared/files', {
        params: {
          folder_id: folderId,
          path: subPath.join('/')
        }
      })
      files.value = response.data.files || []
      currentFolder.value = folderId
      currentPath.value = subPath
    } catch (err) {
      console.error('Failed to fetch files:', err)
      error.value = err.response?.data?.detail || 'Failed to load files'
    } finally {
      loading.value = false
    }
  }

  async function createFolder(name, tags = []) {
    try {
      const response = await axios.post('/api/shared/folders', {
        name,
        tags
      })
      await fetchFolders()
      return response.data
    } catch (err) {
      console.error('Failed to create folder:', err)
      throw err
    }
  }

  async function createSubfolder(folderId, path, name) {
    try {
      const response = await axios.post('/api/shared/subfolders', {
        folder_id: folderId,
        path: path.join('/'),
        name
      })
      await fetchFiles(folderId, path)
      return response.data
    } catch (err) {
      console.error('Failed to create subfolder:', err)
      throw err
    }
  }

  async function deleteFolder(folderId) {
    try {
      await axios.delete(`/api/shared/folders/${folderId}`)
      await fetchFolders()
    } catch (err) {
      console.error('Failed to delete folder:', err)
      throw err
    }
  }

  async function uploadFiles(folderId, path, files) {
    const formData = new FormData()
    formData.append('folder_id', folderId)
    formData.append('path', path.join('/'))
    
    for (const file of files) {
      formData.append('files', file)
    }

    try {
      const response = await axios.post('/api/shared/upload', formData, {
        headers: {
          'Content-Type': 'multipart/form-data'
        }
      })
      await fetchFiles(folderId, path)
      return response.data
    } catch (err) {
      console.error('Failed to upload files:', err)
      throw err
    }
  }

  async function deleteFiles(fileIds) {
    try {
      await axios.delete('/api/shared/files', {
        data: { file_ids: fileIds }
      })
      await fetchFiles(currentFolder.value, currentPath.value)
    } catch (err) {
      console.error('Failed to delete files:', err)
      throw err
    }
  }

  async function updateFileTags(fileId, tags) {
    try {
      await axios.patch(`/api/shared/files/${fileId}/tags`, { tags })
      await fetchFiles(currentFolder.value, currentPath.value)
    } catch (err) {
      console.error('Failed to update tags:', err)
      throw err
    }
  }

  async function getFileHistory(fileId) {
    try {
      const response = await axios.get(`/api/shared/files/${fileId}/history`)
      return response.data.history || []
    } catch (err) {
      console.error('Failed to fetch file history:', err)
      throw err
    }
  }

  function selectFile(fileId) {
    selectedFiles.value.add(fileId)
  }

  function deselectFile(fileId) {
    selectedFiles.value.delete(fileId)
  }

  function toggleFileSelection(fileId) {
    if (selectedFiles.value.has(fileId)) {
      selectedFiles.value.delete(fileId)
    } else {
      selectedFiles.value.add(fileId)
    }
  }

  function clearSelection() {
    selectedFiles.value.clear()
  }

  function selectAll() {
    files.value.forEach(f => selectedFiles.value.add(f.id))
  }

  return {
    // State
    folders,
    currentFolder,
    currentPath,
    files,
    loading,
    error,
    selectedFiles,
    
    // Computed
    currentFolderData,
    breadcrumbs,
    selectedFilesArray,
    
    // Actions
    fetchFolders,
    fetchFiles,
    createFolder,
    createSubfolder,
    deleteFolder,
    uploadFiles,
    deleteFiles,
    updateFileTags,
    getFileHistory,
    selectFile,
    deselectFile,
    toggleFileSelection,
    clearSelection,
    selectAll
  }
})
