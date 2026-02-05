<template>
  <div class="settings">
    <h1 class="text-3xl font-bold mb-8">Settings</h1>

    <!-- Success/Error Notifications -->
    <div
      v-if="notification.show" 
      class="mb-6 p-4 rounded-lg flex items-center justify-between"
      :class="notification.type === 'success' ? 'bg-green-900 text-green-300' : 'bg-red-900 text-red-300'"
    >
      <div class="flex items-center space-x-3">
        <svg v-if="notification.type === 'success'" class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
        </svg>
        <svg v-else class="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
        </svg>
        <span>{{ notification.message }}</span>
      </div>
      <button class="text-gray-400 hover:text-white" @click="notification.show = false">
        <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
        </svg>
      </button>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-4 gap-6">
      <!-- Settings Navigation -->
      <div class="lg:col-span-1">
        <div class="card">
          <nav class="space-y-2">
            <button
              v-for="tab in tabs"
              :key="tab.id"
              class="w-full text-left px-4 py-3 rounded-lg transition-colors"
              :class="activeTab === tab.id ? 'bg-primary-600 text-white' : 'text-gray-400 hover:bg-gray-700'"
              @click="activeTab = tab.id"
            >
              <div class="flex items-center space-x-3">
                <span class="text-xl">{{ tab.icon }}</span>
                <span>{{ tab.name }}</span>
              </div>
            </button>
          </nav>
        </div>
      </div>

      <!-- Settings Content -->
      <div class="lg:col-span-3">
        <!-- General Settings -->
        <div v-if="activeTab === 'general'" class="card">
          <h2 class="text-xl font-bold mb-6">General Settings</h2>
          
          <div class="space-y-8">
            <div>
              <label class="block text-sm font-medium mb-2 text-gray-300">
                {{ $t('settings.general.default_repo') }}
                <span v-tippy="{ content: $t('settings.general.default_repo_hint') }" class="ml-1 cursor-help text-gray-500">ⓘ</span>
              </label>
              <input 
                v-model="localSettings.general.default_repo" 
                type="text" 
                class="input"
                placeholder="default"
              />
            </div>

            <div>
              <label class="block text-sm font-medium mb-2 text-gray-300">
                {{ $t('settings.general.storage_path') }}
                <span v-tippy="{ content: $t('settings.general.storage_path_hint') }" class="ml-1 cursor-help text-gray-500">ⓘ</span>
              </label>
              <div class="flex gap-2">
                <input 
                  v-model="localSettings.general.storage_path" 
                  type="text" 
                  class="input flex-1 border-l-4"
                  :class="localSettings.general.storage_path !== systemStore.status.storage_path ? 'border-yellow-500' : 'border-transparent'"
                  placeholder="e.g. D:/Projects"
                />
                <button @click="browseStoragePath" class="btn-secondary whitespace-nowrap">
                  {{ $t('settings.general.browse') }}
                </button>
              </div>
              <p v-if="localSettings.general.storage_path !== systemStore.status.storage_path" class="text-xs text-yellow-500 mt-2 flex items-center gap-1">
                <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" /></svg>
                {{ $t('settings.general.restart_required') }}
              </p>
            </div>

            <div class="flex items-center justify-between p-4 bg-gray-700 rounded-lg">
              <div>
                <p class="font-medium">{{ $t('settings.general.auto_sync') }}</p>
                <p class="text-sm text-gray-400">{{ $t('settings.general.auto_sync_hint') }}</p>
              </div>
              <button 
                class="relative inline-flex h-6 w-11 items-center rounded-full transition-colors"
                :class="localSettings.general.auto_sync ? 'bg-primary-600' : 'bg-gray-600'"
                @click="localSettings.general.auto_sync = !localSettings.general.auto_sync"
              >
                <span 
                  class="inline-block h-4 w-4 transform rounded-full bg-white transition-transform"
                  :class="localSettings.general.auto_sync ? 'translate-x-6' : 'translate-x-1'"
                />
              </button>
            </div>

            <div>
              <label class="block text-sm font-medium mb-2">Refresh Interval (seconds)</label>
              <input 
                v-model.number="localSettings.general.refresh_interval" 
                type="number" 
                class="input"
                min="1"
                max="60"
              />
              <p class="text-sm text-gray-400 mt-1">How often to refresh status (1-60 seconds)</p>
            </div>
          </div>

          <div class="flex justify-end space-x-4 mt-8">
            <button class="btn-secondary" @click="resetToDefaults">Reset to Defaults</button>
            <button class="btn-primary" :disabled="loading" @click="saveSettings">
              <span v-if="loading">Saving...</span>
              <span v-else>Save Changes</span>
            </button>
          </div>
        </div>

        <!-- Git Settings -->
        <div v-if="activeTab === 'git'" class="card">
          <h2 class="text-xl font-bold mb-6">Git Service Settings</h2>
          
          <div class="space-y-8">
            <div>
              <label class="block text-sm font-medium mb-2 text-gray-300">Git Server Port</label>
              <input 
                v-model.number="localSettings.git.port" 
                type="number" 
                class="input"
                min="1024"
                max="65535"
              />
              <p class="text-sm text-gray-400 mt-1">Port for Git server (1024-65535)</p>
            </div>

            <div class="flex items-center justify-between p-4 bg-gray-700 rounded-lg">
              <div>
                <p class="font-medium">Auto Start Git Server</p>
                <p class="text-sm text-gray-400">Start Git server automatically on launch</p>
              </div>
              <button 
                class="relative inline-flex h-6 w-11 items-center rounded-full transition-colors"
                :class="localSettings.git.auto_start ? 'bg-primary-600' : 'bg-gray-600'"
                @click="localSettings.git.auto_start = !localSettings.git.auto_start"
              >
                <span 
                  class="inline-block h-4 w-4 transform rounded-full bg-white transition-transform"
                  :class="localSettings.git.auto_start ? 'translate-x-6' : 'translate-x-1'"
                />
              </button>
            </div>

            <div class="flex items-center justify-between p-4 bg-gray-700 rounded-lg">
              <div>
                <p class="font-medium">Auto Commit</p>
                <p class="text-sm text-gray-400">Automatically commit changes on save</p>
              </div>
              <button 
                class="relative inline-flex h-6 w-11 items-center rounded-full transition-colors"
                :class="localSettings.git.auto_commit ? 'bg-primary-600' : 'bg-gray-600'"
                @click="localSettings.git.auto_commit = !localSettings.git.auto_commit"
              >
                <span 
                  class="inline-block h-4 w-4 transform rounded-full bg-white transition-transform"
                  :class="localSettings.git.auto_commit ? 'translate-x-6' : 'translate-x-1'"
                />
              </button>
            </div>

            <div class="p-6 bg-gray-700 rounded-lg">
              <h3 class="font-medium mb-4">Current Git Status</h3>
              <div class="space-y-2 text-sm">
                <div class="flex justify-between">
                  <span class="text-gray-400">Status:</span>
                  <span :class="systemStore.gitRunning ? 'text-green-400' : 'text-red-400'">
                    {{ systemStore.gitRunning ? 'Running' : 'Stopped' }}
                  </span>
                </div>
                <div class="flex justify-between">
                  <span class="text-gray-400">Port:</span>
                  <span>{{ systemStore.status.git_port || localSettings.git.port }}</span>
                </div>
                <div class="flex justify-between">
                  <span class="text-gray-400">Local IP:</span>
                  <span>{{ systemStore.status.local_ip || 'N/A' }}</span>
                </div>
              </div>
            </div>
          </div>

          <div class="flex justify-end space-x-4 mt-8">
            <button class="btn-secondary" @click="resetToDefaults">Reset to Defaults</button>
            <button class="btn-primary" :disabled="loading" @click="saveSettings">
              <span v-if="loading">Saving...</span>
              <span v-else>Save Changes</span>
            </button>
          </div>
        </div>

        <!-- Editor Settings -->
        <div v-if="activeTab === 'editor'" class="card">
          <h2 class="text-xl font-bold mb-6">Editor Settings</h2>
          
          <div class="space-y-6">
            <div class="p-4 bg-blue-900/20 border border-blue-700/50 rounded-lg">
              <div class="flex items-start space-x-3">
                <svg class="w-5 h-5 text-blue-400 mt-0.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                <div class="text-sm text-blue-300">
                  <p class="font-medium mb-1">Explorer Access</p>
                  <p>The "Open in Explorer" button will open the current project folder in your system file manager.</p>
                </div>
              </div>
            </div>
          </div>

          <div class="flex justify-end space-x-4 mt-8">
            <button class="btn-secondary" @click="resetToDefaults">Reset to Defaults</button>
            <button class="btn-primary" :disabled="loading" @click="saveSettings">
              <span v-if="loading">Saving...</span>
              <span v-else>Save Changes</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>



<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useSystemStore } from '@/stores/system'
import axios from 'axios'

const systemStore = useSystemStore()
const activeTab = ref('general')
const loading = ref(false)
const notification = reactive({
  show: false,
  message: '',
  type: 'success' // success | error
})

const tabs = [
  { id: 'general', name: 'General', icon: '⚙️' },
  { id: 'git', name: 'Git', icon: '🔧' },
  { id: 'ui', name: 'UI', icon: '🎨' }
]


const localSettings = reactive({
  general: {
    default_repo: 'default',
    default_folder: '',
    auto_sync: true,
    refresh_interval: 5
  },
  git: {
    port: 8080,
    auto_start: false,
    auto_commit: false
  },
  editor: {
    type: 'default',
    custom_path: ''
  },
  ui: {
    theme: 'dark',
    font_size: 14,
    show_system_log: true
  }
})


onMounted(async () => {
  await loadSettings()
  await systemStore.fetchStatus()
})

async function browseStoragePath() {
  try {
    const response = await axios.post('/api/settings/browse')
    if (response.data.path) {
      localSettings.general.storage_path = response.data.path
    }
  } catch (error) {
    console.error('Failed to browse path:', error)
    showNotification('Browser not available on this system', 'error')
  }
}

async function loadSettings() {
  loading.value = true
  try {
    const response = await axios.get('/api/settings')
    Object.assign(localSettings, response.data)
  } catch (error) {
    console.error('Failed to load settings:', error)
    showNotification('Failed to load settings', 'error')
  } finally {
    loading.value = false
  }
}

async function saveSettings() {
  loading.value = true
  try {
    const response = await axios.post('/api/settings', localSettings)
    
    if (response.data.success) {
      showNotification('Settings saved successfully', 'success')
      
      // Update local settings with response
      if (response.data.settings) {
        Object.assign(localSettings, response.data.settings)
      }
    } else {
      showNotification('Failed to save settings', 'error')
    }
  } catch (error) {
    console.error('Failed to save settings:', error)
    const errorMsg = error.response?.data?.detail || 'Failed to save settings'
    showNotification(errorMsg, 'error')
  } finally {
    loading.value = false
  }
}

async function resetToDefaults() {
  if (!confirm('Are you sure you want to reset all settings to defaults?')) {
    return
  }
  
  loading.value = true
  try {
    const response = await axios.get('/api/settings/defaults')
    Object.assign(localSettings, response.data)
    showNotification('Settings reset to defaults', 'success')
  } catch (error) {
    console.error('Failed to reset settings:', error)
    showNotification('Failed to reset settings', 'error')
  } finally {
    loading.value = false
  }
}

function showNotification(message, type = 'success') {
  notification.message = message
  notification.type = type
  notification.show = true
  
  // Auto-hide after 5 seconds
  setTimeout(() => {
    notification.show = false
  }, 5000)
}
</script>
