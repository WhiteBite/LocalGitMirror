import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import axios from 'axios'

export const useSystemStore = defineStore('system', () => {
  // State
  const gitRunning = ref(false)
  const status = ref({
    version: '',
    uptime: 0,
    repos_count: 0,
    storage_used: 0,
    storage_available: 0
  })
  const logs = ref([])
  const settings = ref({
    git_path: '',
    storage_path: '',
    max_repos: 100,
    auto_sync: true,
    sync_interval: 3600,
    theme: 'dark'
  })
  const loading = ref(false)
  const error = ref(null)
  const wsConnection = ref(null)
  const wsReconnectTimer = ref(null)
  const notifications = ref([])
  const apiKey = ref('')

  // Getters
  const storagePercentage = computed(() => {
    const total = status.value.storage_used + status.value.storage_available
    if (total === 0) return 0
    return Math.round((status.value.storage_used / total) * 100)
  })

  const recentLogs = computed(() => {
    return logs.value.slice(-100).reverse()
  })

  const unreadNotifications = computed(() => {
    return notifications.value.filter(n => !n.read).length
  })

  // Actions
  async function fetchStatus() {
    loading.value = true
    error.value = null
    
    try {
      const response = await axios.get('/api/status')
      status.value = response.data
      gitRunning.value = response.data.git_running || false
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to fetch system status'
      console.error('Error fetching status:', err)
    } finally {
      loading.value = false
    }
  }

  async function fetchLogs(limit = 100) {
    loading.value = true
    error.value = null
    
    try {
      const response = await axios.get('/api/logs', {
        params: { limit }
      })
      logs.value = response.data.logs || []
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to fetch logs'
      console.error('Error fetching logs:', err)
    } finally {
      loading.value = false
    }
  }

  async function fetchSettings() {
    loading.value = true
    error.value = null
    
    try {
      const response = await axios.get('/api/settings')
      settings.value = { ...settings.value, ...response.data }
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to fetch settings'
      console.error('Error fetching settings:', err)
    } finally {
      loading.value = false
    }
  }

  async function updateSettings(newSettings) {
    loading.value = true
    error.value = null
    
    try {
      const response = await axios.put('/api/settings', newSettings)
      settings.value = { ...settings.value, ...response.data }
      addNotification('Settings updated successfully', 'success')
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to update settings'
      console.error('Error updating settings:', err)
      addNotification('Failed to update settings', 'error')
      throw err
    } finally {
      loading.value = false
    }
  }

  async function startGit() {
    loading.value = true
    error.value = null
    
    try {
      const response = await axios.post('/api/git/start')
      gitRunning.value = true
      addNotification('Git service started', 'success')
      return response.data
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to start Git service'
      console.error('Error starting Git:', err)
      addNotification('Failed to start Git service', 'error')
      throw err
    } finally {
      loading.value = false
    }
  }

  async function stopGit() {
    loading.value = true
    error.value = null
    
    try {
      const response = await axios.post('/api/git/stop')
      gitRunning.value = false
      addNotification('Git service stopped', 'success')
      return response.data
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to stop Git service'
      console.error('Error stopping Git:', err)
      addNotification('Failed to stop Git service', 'error')
      throw err
    } finally {
      loading.value = false
    }
  }

  async function connectWebSocket() {
    if (wsConnection.value) {
      return
    }

    // Fetch API key if not cached
    if (!apiKey.value) {
      await fetchApiKey()
    }

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const keyParam = apiKey.value ? `?key=${encodeURIComponent(apiKey.value)}` : ''
    const wsUrl = `${protocol}//${window.location.host}/ws/logs${keyParam}`
    
    try {
      wsConnection.value = new WebSocket(wsUrl)
      
      wsConnection.value.onopen = () => {
        console.log('WebSocket connected')
      }
      
      wsConnection.value.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data)
          handleWebSocketMessage(data)
        } catch (err) {
          console.error('Error parsing WebSocket message:', err)
        }
      }
      
      wsConnection.value.onerror = (error) => {
        console.error('WebSocket error:', error)
      }
      
      wsConnection.value.onclose = (event) => {
        console.log('WebSocket disconnected', event.code)
        wsConnection.value = null
        // Only reconnect if not an auth failure (1008 = policy violation)
        if (event.code !== 1008) {
          clearTimeout(wsReconnectTimer.value)
          wsReconnectTimer.value = setTimeout(() => {
            connectWebSocket()
          }, 5000)
        }
      }
    } catch (err) {
      console.error('Error connecting WebSocket:', err)
    }
  }

  function disconnectWebSocket() {
    if (wsConnection.value) {
      wsConnection.value.close()
      wsConnection.value = null
    }
  }

  function handleWebSocketMessage(data) {
    switch (data.type) {
      case 'log':
        logs.value.push(data.payload)
        break
      case 'status':
        status.value = { ...status.value, ...data.payload }
        gitRunning.value = data.payload.git_running || false
        break
      case 'notification':
        addNotification(data.payload.message, data.payload.type)
        break
      default:
        console.log('Unknown WebSocket message type:', data.type)
    }
  }

  function addNotification(message, type = 'info') {
    const notification = {
      id: Date.now(),
      message,
      type,
      read: false,
      timestamp: new Date().toISOString()
    }
    notifications.value.push(notification)
    
    // Auto-remove after 5 seconds
    setTimeout(() => {
      removeNotification(notification.id)
    }, 5000)
  }

  function removeNotification(id) {
    notifications.value = notifications.value.filter(n => n.id !== id)
  }

  function markNotificationRead(id) {
    const notification = notifications.value.find(n => n.id === id)
    if (notification) {
      notification.read = true
    }
  }

  function clearAllNotifications() {
    notifications.value = []
  }

  async function fetchApiKey() {
    if (apiKey.value) return apiKey.value
    try {
      const response = await axios.get('/api/connection-info')
      apiKey.value = response.data.api_key || ''
    } catch (err) {
      console.error('Error fetching API key:', err)
    }
    return apiKey.value
  }

  function clearError() {
    error.value = null
  }

  return {
    // State
    gitRunning,
    status,
    logs,
    settings,
    loading,
    error,
    wsConnection,
    notifications,
    
    // Getters
    storagePercentage,
    recentLogs,
    unreadNotifications,
    
    // Actions
    fetchStatus,
    fetchLogs,
    fetchSettings,
    updateSettings,
    startGit,
    stopGit,
    connectWebSocket,
    disconnectWebSocket,
    fetchApiKey,
    addNotification,
    removeNotification,
    markNotificationRead,
    clearAllNotifications,
    clearError
  }
})
