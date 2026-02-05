import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import axios from 'axios'

export const useReposStore = defineStore('repos', () => {
  // State
  const repos = ref([])
  const currentRepo = ref(null)
  const branches = ref([])
  const currentBranch = ref(null)
  const commits = ref([])
  const loading = ref(false)
  const error = ref(null)

  // Getters
  const sortedRepos = computed(() => {
    return [...repos.value].sort((a, b) => {
      return new Date(b.last_updated) - new Date(a.last_updated)
    })
  })

  const activeRepos = computed(() => {
    return repos.value.filter(repo => repo.status === 'active')
  })

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

  async function fetchRepoDetails(repoId) {
    loading.value = true
    error.value = null
    
    try {
      const response = await axios.get(`/api/repos/${repoId}`)
      currentRepo.value = response.data
      return response.data
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to fetch repository details'
      console.error('Error fetching repo details:', err)
      return null
    } finally {
      loading.value = false
    }
  }

  async function fetchBranches(repoId) {
    loading.value = true
    error.value = null
    
    try {
      const response = await axios.get(`/api/repos/${repoId}/branches`)
      branches.value = response.data.branches || []
      return branches.value
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to fetch branches'
      console.error('Error fetching branches:', err)
      return []
    } finally {
      loading.value = false
    }
  }

  async function fetchCommits(repoId, branch = 'main', limit = 50) {
    loading.value = true
    error.value = null
    
    try {
      const response = await axios.get(`/api/repos/${repoId}/commits`, {
        params: { branch, limit }
      })
      commits.value = response.data.commits || []
      return commits.value
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to fetch commits'
      console.error('Error fetching commits:', err)
      return []
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
        commits.value = []
        branches.value = []
      }
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to delete repository'
      console.error('Error deleting repo:', err)
      throw err
    } finally {
      loading.value = false
    }
  }

  async function syncRepo(repoId) {
    loading.value = true
    error.value = null
    
    try {
      const response = await axios.post(`/api/repos/${repoId}/sync`)
      return response.data
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to sync repository'
      console.error('Error syncing repo:', err)
      throw err
    } finally {
      loading.value = false
    }
  }

  function setCurrentRepo(repo) {
    currentRepo.value = repo
  }

  function setCurrentBranch(branch) {
    currentBranch.value = branch
  }

  function clearError() {
    error.value = null
  }

  return {
    // State
    repos,
    currentRepo,
    branches,
    currentBranch,
    commits,
    loading,
    error,
    
    // Getters
    sortedRepos,
    activeRepos,
    repoCount,
    
    // Actions
    fetchRepos,
    fetchRepoDetails,
    fetchBranches,
    fetchCommits,
    createRepo,
    deleteRepo,
    syncRepo,
    setCurrentRepo,
    setCurrentBranch,
    clearError
  }
})
