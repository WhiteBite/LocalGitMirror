<template>
  <div class="settings">
    <h1 class="text-3xl font-bold mb-8 text-white">{{ t('settings.title') }}</h1>

    <!-- Уведомления -->
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
      <!-- Навигация -->
      <div class="lg:col-span-1">
        <div class="card bg-gray-800 p-2 rounded-lg border border-gray-700">
          <nav class="space-y-1">
            <button
              v-for="tab in tabs"
              :key="tab.id"
              class="w-full text-left px-4 py-3 rounded-lg transition-colors flex items-center space-x-3"
              :class="activeTab === tab.id ? 'bg-blue-600 text-white' : 'text-gray-400 hover:bg-gray-700'"
              @click="activeTab = tab.id"
            >
              <span class="text-xl">{{ tab.icon }}</span>
              <span class="font-medium">{{ t(tab.name) }}</span>
            </button>
          </nav>
        </div>
      </div>

      <!-- Контент -->
      <div class="lg:col-span-3">
        <!-- Общие -->
        <div v-if="activeTab === 'general'" class="card bg-gray-800 p-6 rounded-lg border border-gray-700">
          <h2 class="text-xl font-bold mb-6 text-white border-b border-gray-700 pb-4">{{ t('settings.tabs.general') }}</h2>
          
          <div class="space-y-6">
            <div>
              <label class="block text-sm font-medium mb-2 text-gray-300">{{ t('settings.general.default_repo') }}</label>
              <input 
                v-model="localSettings.general.default_repo" 
                type="text" 
                class="input bg-gray-900 border-gray-700 text-white w-full p-2 rounded border focus:border-blue-500 outline-none"
                placeholder="default"
              />
            </div>

            <div>
              <label class="block text-sm font-medium mb-2 text-gray-300">{{ t('settings.general.storage_path') }}</label>
              <div class="flex gap-2">
                <input 
                  v-model="localSettings.general.storage_path" 
                  type="text" 
                  class="input bg-gray-900 border-gray-700 text-white flex-1 p-2 rounded border focus:border-blue-500 outline-none"
                  :placeholder="t('settings.general.storage_placeholder')"
                />
                <button @click="browseStoragePath" class="px-4 py-2 bg-gray-700 text-white rounded hover:bg-gray-600 transition-colors">
                  {{ t('settings.general.browse') }}
                </button>
              </div>
              <p v-if="localSettings.general.storage_path !== systemStore.status.storage_path" class="text-xs text-yellow-500 mt-2 flex items-center gap-1">
                 ⚠️ {{ t('settings.general.restart_warning') }}
              </p>
            </div>

            <div class="flex items-center justify-between p-4 bg-gray-700/30 rounded-lg border border-gray-700">
              <div>
                <p class="font-medium text-white">{{ t('settings.general.auto_sync') }}</p>
                <p class="text-sm text-gray-400">{{ t('settings.general.auto_sync_desc') }}</p>
              </div>
              <button 
                class="relative inline-flex h-6 w-11 items-center rounded-full transition-colors"
                :class="localSettings.general.auto_sync ? 'bg-blue-600' : 'bg-gray-600'"
                @click="localSettings.general.auto_sync = !localSettings.general.auto_sync"
              >
                <span 
                  class="inline-block h-4 w-4 transform rounded-full bg-white transition-transform"
                  :class="localSettings.general.auto_sync ? 'translate-x-6' : 'translate-x-1'"
                />
              </button>
            </div>

            <div>
              <label class="block text-sm font-medium mb-2 text-gray-300">{{ t('settings.general.refresh_interval') }}</label>
              <input 
                v-model.number="localSettings.general.refresh_interval" 
                type="number" 
                class="input bg-gray-900 border-gray-700 text-white w-full p-2 rounded border focus:border-blue-500 outline-none"
                min="1"
                max="60"
              />
              <p class="text-xs text-gray-500 mt-1">{{ t('settings.general.refresh_desc') }}</p>
            </div>
          </div>

          <div class="flex justify-end space-x-3 mt-8 pt-6 border-t border-gray-700">
            <button class="px-4 py-2 text-gray-400 hover:text-white transition-colors" @click="resetToDefaults">{{ t('settings.actions.reset') }}</button>
            <button class="px-6 py-2 bg-blue-600 text-white rounded font-bold hover:bg-blue-700 transition-colors disabled:opacity-50" :disabled="loading" @click="saveSettings">
              <span v-if="loading">{{ t('settings.actions.saving') }}</span>
              <span v-else>{{ t('settings.actions.save') }}</span>
            </button>
          </div>
        </div>

        <!-- Git -->
        <div v-if="activeTab === 'git'" class="card bg-gray-800 p-6 rounded-lg border border-gray-700">
          <h2 class="text-xl font-bold mb-6 text-white border-b border-gray-700 pb-4">{{ t('settings.tabs.git') }}</h2>
          
          <div class="space-y-8">
            <div>
              <label class="block text-sm font-medium mb-2 text-gray-300">{{ t('settings.git.port') }}</label>
              <input 
                v-model.number="localSettings.git.port" 
                type="number" 
                class="input bg-gray-900 border-gray-700 text-white w-full p-2 rounded border focus:border-blue-500 outline-none"
                min="1024"
                max="65535"
              />
              <p class="text-xs text-gray-500 mt-1">{{ t('settings.git.port_desc') }}</p>
            </div>

            <div class="p-6 bg-gray-900/50 rounded-lg border border-gray-700">
              <h3 class="font-medium mb-4 text-gray-300">{{ t('settings.git.identity_title') }}</h3>
              <div class="space-y-4">
                <div>
                  <label class="block text-sm font-medium mb-1 text-gray-400">{{ t('settings.git.user_name') }}</label>
                  <input 
                    v-model="localSettings.git.user_name" 
                    type="text" 
                    class="input bg-gray-800 border-gray-600 text-white w-full p-2 rounded border focus:border-blue-500 outline-none"
                    placeholder="Напр. d.minkin"
                  />
                </div>
                <div>
                  <label class="block text-sm font-medium mb-1 text-gray-400">{{ t('settings.git.user_email') }}</label>
                  <input 
                    v-model="localSettings.git.user_email" 
                    type="text" 
                    class="input bg-gray-800 border-gray-600 text-white w-full p-2 rounded border focus:border-blue-500 outline-none"
                    placeholder="Напр. d.minkin@corp.com"
                  />
                </div>
                <p class="text-xs text-gray-500">{{ t('settings.git.identity_desc') }}</p>
              </div>
            </div>

            <div class="p-6 bg-gray-900/50 rounded-lg border border-gray-700">
              <h3 class="font-medium mb-4 text-gray-300">{{ t('settings.git.network_status') }}</h3>
              <div class="space-y-3 text-sm">
                <div class="flex justify-between">
                  <span class="text-gray-500">{{ t('settings.git.security_mode') }}:</span>
                  <span class="text-green-400 font-bold">HTTPS / SSL Активен</span>
                </div>
                <div class="flex justify-between">
                  <span class="text-gray-500">{{ t('settings.git.local_ip') }}:</span>
                  <span class="text-white font-mono">{{ systemStore.status.local_ip || 'Неизвестно' }}</span>
                </div>
                <div class="flex justify-between">
                  <span class="text-gray-500">{{ t('settings.git.server_port') }}:</span>
                  <span class="text-white font-mono">{{ webPort }}</span>
                </div>
              </div>
            </div>
          </div>

          <div class="flex justify-end mt-8 pt-6 border-t border-gray-700">
            <button class="px-6 py-2 bg-blue-600 text-white rounded font-bold hover:bg-blue-700 transition-colors" :disabled="loading" @click="saveSettings">
              {{ t('settings.actions.save') }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { useSystemStore } from '@/stores/system'
import { useI18n } from 'vue-i18n'
import axios from 'axios'

const { t } = useI18n()
const systemStore = useSystemStore()
const activeTab = ref('general')
const loading = ref(false)
const notification = reactive({
  show: false,
  message: '',
  type: 'success'
})

const webPort = computed(() => systemStore.status.web_port || 8443)

const tabs = [
  { id: 'general', name: 'settings.tabs.general', icon: '⚙️' },
  { id: 'git', name: 'settings.tabs.git', icon: '🌐' }
]

const localSettings = reactive({
  general: {
    default_repo: 'default',
    storage_path: '',
    auto_sync: true,
    refresh_interval: 5
  },
  git: {
    port: 8443,
    auto_start: true,
    user_name: '',
    user_email: ''
  }
})

onMounted(async () => {
  await loadSettings()
  await systemStore.fetchStatus()
})

async function browseStoragePath() {
  try {
    const response = await axios.post('/api/config/browse')
    if (response.data.path) {
      localSettings.general.storage_path = response.data.path
    }
  } catch (error) {
    showNotification(t('settings.notifications.browse_error'), 'error')
  }
}

async function loadSettings() {
  loading.value = true
  try {
    const response = await axios.get('/api/settings')
    Object.assign(localSettings, response.data)
    
    // Подтягиваем актуальный порт из статуса
    const statusResp = await axios.get('/api/status')
    if (statusResp.data.web_port) {
      localSettings.git.port = statusResp.data.web_port
    }
  } catch (error) {
    showNotification(t('settings.notifications.load_error'), 'error')
  } finally {
    loading.value = false
  }
}

async function saveSettings() {
  loading.value = true
  try {
    const response = await axios.post('/api/settings', localSettings)
    if (response.data.success) {
      showNotification(t('settings.notifications.save_success'), 'success')
      await systemStore.fetchStatus()
    }
  } catch (error) {
    showNotification(t('settings.notifications.save_error'), 'error')
  } finally {
    loading.value = false
  }
}

async function resetToDefaults() {
  if (!confirm(t('settings.notifications.reset_confirm'))) return
  loading.value = true
  try {
    const response = await axios.get('/api/settings/defaults')
    Object.assign(localSettings, response.data)
    showNotification(t('settings.notifications.reset_success'), 'success')
  } catch (error) {
    showNotification(t('settings.notifications.reset_error'), 'error')
  } finally {
    loading.value = false
  }
}

function showNotification(message, type = 'success') {
  notification.message = message
  notification.type = type
  notification.show = true
  setTimeout(() => notification.show = false, 5000)
}
</script>

<style scoped>
.settings { padding: 2rem; max-width: 1200px; margin: 0 auto; }
.card { box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06); }
</style>