import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import axios from 'axios'

export const useFilesStore = defineStore('files', () => {
  // State
  const files = ref([])
  const currentFile = ref(null)
  const currentFolder = ref('/')
  const loading = ref(false)
  const error = ref(null)
  const fileContent = ref(null)
  const fileMetadata = ref(null)

  // Getters
  const sortedFiles = computed(() => {
    return [...files.value].sort((a, b) => {
      // Folders first
      if (a.type === 'directory' && b.type !== 'directory') return -1
      if (a.type !== 'directory' && b.type === 'directory') return 1
      // Then alphabetically
      return a.name.localeCompare(b.name)
    })
  })

  const breadcrumbs = computed(() => {
    if (currentFolder.value === '/') return [{ name: 'Root', path: '/' }]
    
    const parts = currentFolder.value.split('/').filter(Boolean)
    const crumbs = [{ name: 'Root', path: '/' }]
    
    let path = ''
    parts.forEach(part => {
      path += `/${part}`
      crumbs.push({ name: part, path })
    })
    
    return crumbs
  })

  // Actions
  async function fetchFiles(path = '/') {
    loading.value = true
    error.value = null
    
    try {
      console.log('Fetching files from:', path)
      const response = await axios.get('/api/files', {
        params: { path }
      })
      console.log('Files response:', response.data)
      files.value = response.data.files || []
      console.log('Files loaded:', files.value.length)
      currentFolder.value = path
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to fetch files'
      console.error('Error fetching files:', err)
    } finally {
      loading.value = false
    }
  }

  async function fetchFileContent(path) {
    loading.value = true
    error.value = null
    
    try {
      const response = await axios.get('/api/files/content', {
        params: { path }
      })
      fileContent.value = response.data.content
      fileMetadata.value = response.data.metadata
      currentFile.value = path
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to fetch file content'
      console.error('Error fetching file content:', err)
    } finally {
      loading.value = false
    }
  }

  async function searchFiles(query) {
    loading.value = true
    error.value = null
    
    try {
      const response = await axios.get('/api/files/search', {
        params: { query }
      })
      return response.data.results || []
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to search files'
      console.error('Error searching files:', err)
      return []
    } finally {
      loading.value = false
    }
  }

  function clearCurrentFile() {
    currentFile.value = null
    fileContent.value = null
    fileMetadata.value = null
  }

  function navigateToFolder(path) {
    fetchFiles(path)
  }

  return {
    // State
    files,
    currentFile,
    currentFolder,
    loading,
    error,
    fileContent,
    fileMetadata,
    
    // Getters
    sortedFiles,
    breadcrumbs,
    
    // Actions
    fetchFiles,
    fetchFileContent,
    searchFiles,
    clearCurrentFile,
    navigateToFolder
  }
})
