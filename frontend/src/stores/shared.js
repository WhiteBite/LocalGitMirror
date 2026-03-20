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
  const selectedFiles = ref([])
  const searchQuery = ref('')
  const uploadProgress = ref(0)

  // Computed
  const currentFolderData = computed(() => {
    if (!currentFolder.value) return null
    return folders.value.find(f => f.name === currentFolder.value)
  })

  const filteredFiles = computed(() => {
    if (!searchQuery.value) return files.value
    const query = searchQuery.value.toLowerCase()
    return files.value.filter(f => f.name.toLowerCase().includes(query))
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
    return files.value.filter(f => selectedFiles.value.includes(f.path))
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

  async function selectFolder(folderName) {
    currentFolder.value = folderName
    await fetchFiles(folderName)
  }

  async function fetchFiles(folderName, subPath = '') {
    loading.value = true
    error.value = null
    try {
      const params = { folder: folderName }
      if (subPath) params.path = subPath
      
      const response = await axios.get('/api/shared/files', { params })
      files.value = response.data.files || []
    } catch (err) {
      console.error('Failed to fetch files:', err)
      error.value = err.response?.data?.detail || 'Failed to load files'
      files.value = []
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

  async function deleteFolder(folderName) {
    try {
      await axios.delete('/api/shared/folders', {
        params: { folder: folderName }
      })
      await fetchFolders()
      if (currentFolder.value === folderName) {
        currentFolder.value = null
      }
    } catch (err) {
      console.error('Failed to delete folder:', err)
      throw err
    }
  }

  async function uploadFile(folderName, file, path = '') {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('folder', folderName)
    if (path) formData.append('subfolder', path)
    
    uploadProgress.value = 0

    try {
      const response = await axios.post('/api/shared/upload', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: (progressEvent) => {
          uploadProgress.value = Math.round((progressEvent.loaded * 100) / progressEvent.total)
        }
      })
      
      await fetchFiles(folderName, path)
      uploadProgress.value = 0
      return response.data
    } catch (err) {
      console.error('Failed to upload file:', err)
      uploadProgress.value = 0
      throw err
    }
  }

  async function deleteFile(folderName, filePath) {
    try {
      await axios.delete('/api/shared/files', {
        params: { folder: folderName, path: filePath }
      })
      await fetchFiles(folderName)
    } catch (err) {
      console.error('Failed to delete file:', err)
      throw err
    }
  }

  async function bulkDelete(folderName, filePaths) {
    try {
      await axios.post('/api/shared/bulk-delete', {
        folder: folderName,
        paths: filePaths
      })
      await fetchFiles(folderName)
      clearSelection()
    } catch (err) {
      console.error('Failed to bulk delete:', err)
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

  function toggleFileSelection(filePath) {
    const index = selectedFiles.value.indexOf(filePath)
    if (index > -1) {
      selectedFiles.value.splice(index, 1)
    } else {
      selectedFiles.value.push(filePath)
    }
  }

  function clearSelection() {
    selectedFiles.value = []
  }

  function selectAll() {
    selectedFiles.value = files.value.map(f => f.path)
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
    searchQuery,
    uploadProgress,
    
    // Computed
    currentFolderData,
    breadcrumbs,
    selectedFilesArray,
    filteredFiles,
    
    // Actions
    fetchFolders,
    selectFolder,
    fetchFiles,
    createFolder,
    createSubfolder,
    deleteFolder,
    uploadFile,
    deleteFile,
    bulkDelete,
    updateFileTags,
    getFileHistory,
    toggleFileSelection,
    clearSelection,
    selectAll
  }
})
