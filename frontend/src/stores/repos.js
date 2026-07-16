import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import axios from 'axios'

export const useReposStore = defineStore('repos', () => {
  // State
  const repos = ref([])
  const currentRepo = ref(null)
  const loading = ref(false)
  const error = ref(null)

  // Getters
  // NOTE: /api/repos returns an array of repo-name strings, so the count is
  // simply the array length. (Older object-shaped getters were removed.)
  const repoCount = computed(() => repos.value.length)

  // Actions
  async function fetchRepos() {
    loading.value = true
    error.value = null

    try {
      const response = await axios.get('/api/repos')
      repos.value = response.data.repos || []
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to fetch repositories'
      console.error('Error fetching repos:', err)
    } finally {
      loading.value = false
    }
  }

  async function createRepo(name) {
    loading.value = true
    error.value = null

    try {
      await axios.post('/api/repos/create', { name })
      await fetchRepos() // Refresh list
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to create repository'
      console.error('Error creating repo:', err)
      throw err
    } finally {
      loading.value = false
    }
  }

  async function deleteRepo(name) {
    loading.value = true
    error.value = null

    try {
      await axios.post('/api/repos/delete', { repo: name })
      await fetchRepos() // Refresh list
      // If we deleted the current repo, clear selection
      if (currentRepo.value === name) {
        currentRepo.value = null
      }
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to delete repository'
      console.error('Error deleting repo:', err)
      throw err
    } finally {
      loading.value = false
    }
  }

  function setCurrentRepo(repo) {
    currentRepo.value = repo
  }

  function clearError() {
    error.value = null
  }

  return {
    // State
    repos,
    currentRepo,
    loading,
    error,

    // Getters
    repoCount,

    // Actions
    fetchRepos,
    createRepo,
    deleteRepo,
    setCurrentRepo,
    clearError
  }
})
