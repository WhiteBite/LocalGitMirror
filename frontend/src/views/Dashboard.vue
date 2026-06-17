<template>
  <div class="dashboard-view">
    <header class="view-header flex justify-between items-center">
      <h1>{{ t('dashboard.title') }}</h1>
      <button 
        @click="panicMode" 
        v-tippy="{ content: t('dashboard.panic_tooltip'), placement: 'bottom' }"
        class="bg-red-600 hover:bg-red-700 text-white px-4 py-2 rounded text-sm font-bold flex items-center gap-2 transition-all shadow-lg hover:shadow-red-900/50"
      >
        <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
        </svg>
        {{ t('dashboard.panic') }}
      </button>
    </header>

    <div class="view-content">
      <div v-if="statusError" class="alert-card">
        <div class="alert-title">{{ t('common.error') }}</div>
        <div class="alert-text">{{ statusError }}</div>
        <button class="btn btn-secondary" @click="fetchStatus">{{ t('common.refresh') }}</button>
      </div>

      <!-- Метрики -->
      <div class="metrics-grid">
        <div class="metric-card">
          <div class="metric-icon active">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M3 15v4c0 1.1.9 2 2 2h14a2 2 0 0 0 2-2v-4M17 9l-5 5-5-5M12 12.8V2.5"/>
            </svg>
          </div>
          <div class="metric-info">
            <label>{{ t('dashboard.git_server') }}</label>
            <div class="value-row">
              <span class="status-text text-success">{{ t('dashboard.status_active_https') }}</span>
            </div>
            <div class="sub-text warning">{{ t('dashboard.traffic_encrypted') }}</div>
          </div>
        </div>

        <div class="metric-card">
          <div class="metric-icon" :class="{ 'warn': metrics.disk_percent > 85 }">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <ellipse cx="12" cy="5" rx="9" ry="3"></ellipse>
              <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path>
              <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path>
            </svg>
          </div>
          <div class="metric-info">
            <label>{{ t('dashboard.metrics.disk') }}</label>
            <div class="value-row">
              <span class="value">{{ metrics.disk_percent }}%</span>
              <span class="unit">{{ t('dashboard.disk_used') }}</span>
            </div>
              <div class="sub-text">{{ metrics.disk_used_gb }} {{ t('dashboard.gb') }} / {{ metrics.disk_total_gb }} {{ t('dashboard.gb') }}</div>
          </div>
        </div>

        <div class="metric-card">
          <div class="metric-icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="2" y="7" width="20" height="14" rx="2" ry="2"></rect>
              <path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"></path>
            </svg>
          </div>
          <div class="metric-info">
            <label>{{ t('dashboard.metrics.memory') }}</label>
            <div class="value-row">
              <span class="value">{{ metrics.memory_percent }}%</span>
            </div>
            <div class="sub-text">{{ metrics.memory_used_gb }} {{ t('dashboard.gb') }} {{ t('dashboard.memory_used_suffix') }}</div>
          </div>
        </div>
      </div>

      <!-- Активный том -->
      <section class="active-project-card border-l-4 border-blue-500">
        <div class="card-header">
          <div class="project-title">
            <span class="icon healthy">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <ellipse cx="12" cy="5" rx="9" ry="3"></ellipse>
                <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path>
                <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path>
              </svg>
              <span class="health-dot"></span>
            </span>
            <div>
              <h2 class="text-2xl font-bold text-white">{{ currentRepo }}</h2>
              <p class="activity-text">
                {{ t('dashboard.activity') }}: 
                <span :class="{ 'text-yellow-500': lastActivity === 'Никогда' }">
                  {{ lastActivity }}
                  <span v-if="lastActivity === 'Никогда'" class="text-xs italic opacity-70">
                    ({{ t('dashboard.no_sync_hint') }})
                  </span>
                </span>
              </p>
               <div class="git-url-row">
                 <input 
                   readonly 
                   :value="`https://${localIP}:${webPort}/git/${currentRepo}`" 
                   class="git-url-input"
                   @click="$event.target.select()"
                 />
                 <button class="btn-icon" :title="t('dashboard.copy_command')" @click="copyGitCommand">
                  <svg v-if="!copied" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                  <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"></polyline></svg>
                </button>
              </div>
            </div>
          </div>
          <div class="actions flex items-center gap-3">
            <button 
              class="bg-gray-700 hover:bg-gray-600 text-white px-4 py-2 rounded flex items-center gap-2 transition-colors border border-gray-600" 
              @click="openExplorer"
            >
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2z" /></svg>
              {{ t('dashboard.open_btn') }}
            </button>
            <button 
              class="bg-blue-600 hover:bg-blue-500 text-white px-5 py-2 rounded font-bold flex items-center gap-2 transition-all shadow-md active:scale-95" 
              :disabled="syncing" 
              @click="prepareForWork"
            >
              <svg v-if="syncing" class="animate-spin h-4 w-4 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              <svg v-else xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
              </svg>
              <span v-if="!syncing">{{ t('dashboard.prepare_btn') }}</span>
              <span v-else>{{ t('settings.actions.saving') }}</span>
            </button>
          </div>
        </div>
      </section>

      <!-- Plugin Connection Info -->
      <section class="connection-info-card border-l-4 border-emerald-500">
        <details>
          <summary class="conn-summary">
            <div class="flex items-center gap-3">
              <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/>
                <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>
              </svg>
              <span class="summary-title">{{ t('dashboard.plugin_setup.title') }}</span>
            </div>
            <span class="chevron">▼</span>
          </summary>
          <div class="conn-body">
            <p class="conn-desc">{{ t('dashboard.plugin_setup.desc') }}</p>

            <div class="conn-fields">
              <!-- Mirror URL -->
              <div class="conn-field">
                <label class="conn-label">{{ t('dashboard.plugin_setup.mirror_url') }}</label>
                <div class="conn-input-row">
                  <input readonly :value="connInfo.mirror_url" class="conn-input" @click="$event.target.select()" />
                  <button class="btn-icon" :title="t('common.copy')" @click="copyField('mirror_url')">
                    <svg v-if="copiedField !== 'mirror_url'" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                    <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#4ade80" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
                  </button>
                </div>
              </div>

              <!-- API Key -->
              <div class="conn-field">
                <label class="conn-label">{{ t('dashboard.plugin_setup.api_key') }}</label>
                <div class="conn-input-row">
                  <input readonly :value="showApiKey ? connInfo.api_key : maskValue(connInfo.api_key)" class="conn-input font-mono" @click="$event.target.select()" />
                  <button class="btn-icon" :title="showApiKey ? t('common.hide') : t('common.show')" @click="showApiKey = !showApiKey">
                    <svg v-if="!showApiKey" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                    <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                  </button>
                  <button class="btn-icon" :title="t('common.copy')" @click="copyField('api_key')">
                    <svg v-if="copiedField !== 'api_key'" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                    <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#4ade80" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
                  </button>
                </div>
              </div>

              <!-- Sync Password -->
              <div class="conn-field">
                <label class="conn-label">{{ t('dashboard.plugin_setup.sync_password') }}</label>
                <div class="conn-input-row">
                  <input readonly :value="showPassword ? connInfo.sync_password : maskValue(connInfo.sync_password)" class="conn-input font-mono" @click="$event.target.select()" />
                  <button class="btn-icon" :title="showPassword ? t('common.hide') : t('common.show')" @click="showPassword = !showPassword">
                    <svg v-if="!showPassword" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                    <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"/><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"/><line x1="1" y1="1" x2="23" y2="23"/></svg>
                  </button>
                  <button class="btn-icon" :title="t('common.copy')" @click="copyField('sync_password')">
                    <svg v-if="copiedField !== 'sync_password'" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                    <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#4ade80" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
                  </button>
                </div>
              </div>

              <!-- Default Repo -->
              <div class="conn-field">
                <label class="conn-label">{{ t('dashboard.plugin_setup.default_repo') }}</label>
                <div class="conn-input-row">
                  <input readonly :value="connInfo.default_repo" class="conn-input" @click="$event.target.select()" />
                  <button class="btn-icon" :title="t('common.copy')" @click="copyField('default_repo')">
                    <svg v-if="copiedField !== 'default_repo'" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                    <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#4ade80" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
                  </button>
                </div>
              </div>
            </div>

            <!-- Copy Full Config -->
            <div class="conn-full-config">
              <button class="btn-copy-config" @click="copyFullConfig">
                <svg v-if="copiedField !== 'full'" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#4ade80" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
                <span>{{ copiedField === 'full' ? t('dashboard.plugin_setup.copied') : t('dashboard.plugin_setup.copy_full_config') }}</span>
              </button>
              <p class="conn-hint">{{ t('dashboard.plugin_setup.paste_hint') }}</p>
            </div>
          </div>
        </details>
      </section>

      <!-- Sync Wizard -->
      <SyncWizard />

      <!-- Инструкция -->
      <section class="sync-guide-section">
        <details>
          <summary>
            <span class="summary-title">{{ t('dashboard.sync_guide') }}</span>
            <span class="chevron">▼</span>
          </summary>
          <div class="guide-content">
            <div class="steps-grid">
              <div class="guide-step">
                <div class="step-badge">1</div>
                <div class="step-content">
                  <strong>{{ t('dashboard.steps.work_pc') }}</strong>
                  <div class="code-block">git config http.sslVerify false</div>
                  <div class="code-block" style="margin-top:4px">git remote add home https://...</div>
                </div>
              </div>
              <div class="guide-step arrow">→</div>
              <div class="guide-step">
                <div class="step-badge">2</div>
                <div class="step-content">
                  <strong>{{ t('dashboard.steps.here_home') }}</strong>
                  <div class="step-desc">{{ t('dashboard.steps.step2_desc') }} <span class="highlight">{{ t('dashboard.prepare_btn') }}</span></div>
                </div>
              </div>
              <div class="guide-step arrow">→</div>
              <div class="guide-step">
                <div class="step-badge">3</div>
                <div class="step-content">
                  <strong>{{ t('dashboard.steps.work_pc') }}</strong>
                  <div class="code-block">git pull home main</div>
                </div>
              </div>
            </div>
          </div>
        </details>
      </section>

      <section class="log-section">
        <SystemLog />
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import { useSystemStore } from '@/stores/system'
import { useReposStore } from '@/stores/repos'
import { useI18n } from 'vue-i18n'
import SystemLog from '@/components/SystemLog.vue'
import SyncWizard from '@/components/SyncWizard.vue'
import axios from 'axios'

const { t } = useI18n()
const systemStore = useSystemStore()
const reposStore = useReposStore()

const localIP = ref('localhost')
const webPort = ref(window.location.port || '8443')
const storagePath = ref('storage')
const metrics = ref({
  disk_percent: 0,
  disk_used_gb: 0,
  disk_total_gb: 0,
  memory_percent: 0,
  memory_used_gb: 0
})

const currentRepo = computed(() => reposStore.currentRepo || 'default')
const syncing = ref(false)
const copied = ref(false)
const lastActivity = ref('Никогда')
const statusError = ref('')

// Connection info state
const connInfo = ref({ mirror_url: '', api_key: '', sync_password: '', default_repo: '', config_line: '' })
const showApiKey = ref(false)
const showPassword = ref(false)
const copiedField = ref('')

// Обновлять данные при смене тома в сайдбаре
watch(() => reposStore.currentRepo, () => {
  fetchStatus()
})

onMounted(async () => {
  if (!reposStore.currentRepo) {
    await reposStore.fetchRepos()
  }
  await fetchStatus()
  await fetchMetrics()
  await fetchConnectionInfo()
  setInterval(fetchMetrics, 30000)
})

async function fetchStatus() {
  try {
    statusError.value = ''
    const response = await axios.get('/api/status')
    const data = response.data
    
    if (!reposStore.currentRepo && data.current_repo) {
        reposStore.currentRepo = data.current_repo
    }
    
    localIP.value = data.local_ip || window.location.hostname
    // ПРИОРИТЕТ: берем порт Web UI из статуса бэкенда
    webPort.value = data.web_port || window.location.port || 443
    storagePath.value = data.storage_path || 'storage'
    
    if (data.last_sync_time) {
      lastActivity.value = new Date(data.last_sync_time).toLocaleString('ru-RU')
    } else {
      lastActivity.value = 'Никогда'
    }
  } catch (error) {
    console.error('Ошибка получения статуса:', error)
    statusError.value = error?.response?.data?.detail || t('common.error')
  }
}

async function fetchMetrics() {
  try {
    const response = await axios.get('/api/metrics')
    metrics.value = response.data
  } catch (error) {
    console.error('Ошибка получения метрик:', error)
  }
}

function copyGitCommand() {
  const url = `https://${localIP.value}:${webPort.value}/git/${currentRepo.value}`
  navigator.clipboard.writeText(url)
  copied.value = true
  setTimeout(() => copied.value = false, 2000)
}

async function openExplorer() {
  try {
    await axios.post('/api/system/open-editor')
  } catch (error) {
    console.error('Ошибка открытия проводника:', error)
  }
}

async function prepareForWork() {
  if (syncing.value) return
  syncing.value = true
  try {
    const minTime = new Promise(resolve => setTimeout(resolve, 800))
    const request = axios.post('/api/git/save-and-sync')
    
    const [_, response] = await Promise.all([minTime, request])
    if (response.data.success) {
      systemStore.addNotification(t('settings.notifications.save_success'), 'success')
      await fetchStatus()
    } else {
      systemStore.addNotification(t('settings.notifications.save_error') + ': ' + response.data.message, 'error')
    }
  } catch (error) {
    systemStore.addNotification(t('settings.notifications.save_error'), 'error')
  } finally {
    syncing.value = false
  }
}

async function fetchConnectionInfo() {
  try {
    const response = await axios.get('/api/connection-info')
    connInfo.value = response.data
  } catch (error) {
    console.error('Failed to fetch connection info:', error)
  }
}

function maskValue(val) {
  if (!val) return ''
  return '•'.repeat(Math.min(val.length, 16))
}

function copyField(field) {
  const value = connInfo.value[field] || ''
  navigator.clipboard.writeText(value)
  copiedField.value = field
  setTimeout(() => copiedField.value = '', 2000)
}

function copyFullConfig() {
  navigator.clipboard.writeText(connInfo.value.config_line || '')
  copiedField.value = 'full'
  setTimeout(() => copiedField.value = '', 3000)
}

async function panicMode() {
  if (confirm(t('dashboard.panic_confirm'))) {
    try {
      await axios.post('/api/system/panic')
    } catch (e) {}
    window.close()
    document.body.innerHTML = `<h1 style='color:red;text-align:center;margin-top:20%'>${t('dashboard.server_stopped')}</h1>`
  }
}
</script>

<style scoped>
.dashboard-view { display: flex; flex-direction: column; height: 100%; color: var(--text-main); }
.view-header { padding: 20px 30px; border-bottom: 1px solid var(--border-color); background: var(--bg-primary); }
.view-header h1 { margin: 0; font-size: 20px; font-weight: 500; color: var(--text-bright); }
.view-content { padding: 30px; max-width: 1200px; margin: 0 auto; width: 100%; }

.alert-card {
  background: rgba(239, 68, 68, 0.08);
  border: 1px solid rgba(239, 68, 68, 0.25);
  border-radius: 10px;
  padding: 14px 16px;
  margin-bottom: 18px;
}

.alert-title {
  font-weight: 700;
  color: #fecaca;
  margin-bottom: 4px;
}

.alert-text {
  color: var(--text-secondary);
  font-size: 13px;
  margin-bottom: 10px;
}
.metrics-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px; margin-bottom: 30px; }
.metric-card { background: var(--bg-card); border: 1px solid var(--border-color); border-radius: 6px; padding: 20px; display: flex; align-items: center; gap: 15px; }
.metric-icon { width: 40px; height: 40px; border-radius: 8px; background: rgba(255, 255, 255, 0.05); display: flex; align-items: center; justify-content: center; color: var(--text-secondary); }
.metric-icon svg { width: 20px; height: 20px; }
.metric-icon.active { color: var(--success); background: rgba(137, 209, 133, 0.1); }
.metric-info { flex: 1; }
.metric-info label { font-size: 11px; text-transform: uppercase; color: var(--text-secondary); display: block; margin-bottom: 4px; letter-spacing: 0.5px; }
.status-text { font-weight: 600; font-size: 15px; }
.text-success { color: var(--success); }
.value { font-size: 20px; font-weight: 600; color: var(--text-bright); }
.unit { font-size: 12px; color: var(--text-secondary); margin-left: 4px; }
.active-project-card { background: var(--bg-card); border: 1px solid var(--border-color); border-radius: 6px; padding: 25px; margin-bottom: 30px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.project-title { display: flex; align-items: center; gap: 15px; }
.project-title .icon { width: 48px; height: 48px; background: var(--accent); color: white; border-radius: 8px; display: flex; align-items: center; justify-content: center; position: relative; }
.health-dot { position: absolute; bottom: -2px; right: -2px; width: 12px; height: 12px; border-radius: 50%; background: var(--success); border: 2px solid var(--bg-card); }
.git-url-row { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.git-url-input { background: rgba(0,0,0,0.2); border: 1px solid var(--border-color); border-radius: 4px; color: var(--accent); font-family: monospace; font-size: 12px; padding: 4px 8px; width: 350px; outline: none; }
.sync-guide-section { margin-bottom: 30px; border: 1px solid var(--border-color); border-radius: 6px; background: var(--bg-sidebar); overflow: hidden; }
details summary { padding: 15px 20px; cursor: pointer; display: flex; justify-content: space-between; align-items: center; font-weight: 500; color: var(--text-secondary); }
.summary-title { font-size: 14px; text-transform: uppercase; letter-spacing: 0.5px; }
.guide-content { padding: 20px; background: var(--bg-card); }
.steps-grid { display: flex; align-items: center; justify-content: space-between; }
.guide-step { display: flex; align-items: flex-start; gap: 12px; flex: 1; }
.guide-step.arrow { flex: 0; padding: 0 20px; color: var(--text-secondary); font-size: 20px; }
.step-badge { background: var(--bg-primary); border: 1px solid var(--border-color); color: var(--text-secondary); width: 24px; height: 24px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: bold; flex-shrink: 0; }
.code-block { background: #111; padding: 4px 8px; border-radius: 4px; font-family: monospace; font-size: 12px; color: var(--success); display: inline-block; }
.highlight { color: var(--accent); font-weight: 500; }

@media (max-width: 1020px) {
  .metrics-grid { grid-template-columns: 1fr; }
  .card-header { flex-direction: column; align-items: flex-start; gap: 16px; }
  .git-url-input { width: min(520px, 100%); }
  .steps-grid { flex-direction: column; align-items: stretch; gap: 10px; }
  .guide-step.arrow { display: none; }
}

@media (max-width: 640px) {
  .view-header { padding: 16px 16px; }
  .view-content { padding: 16px; }
}

/* Connection Info Card */
.connection-info-card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  margin-bottom: 30px;
  overflow: hidden;
}
.conn-summary {
  padding: 15px 20px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 500;
  color: var(--text-secondary);
  list-style: none;
}
.conn-summary::-webkit-details-marker { display: none; }
.conn-summary .summary-title {
  font-size: 14px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.conn-body {
  padding: 0 20px 20px;
}
.conn-desc {
  color: var(--text-secondary);
  font-size: 13px;
  margin-bottom: 16px;
}
.conn-fields {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 16px;
}
.conn-field { }
.conn-label {
  display: block;
  font-size: 11px;
  text-transform: uppercase;
  color: var(--text-secondary);
  margin-bottom: 4px;
  letter-spacing: 0.5px;
}
.conn-input-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.conn-input {
  flex: 1;
  background: rgba(0,0,0,0.2);
  border: 1px solid var(--border-color);
  border-radius: 4px;
  color: var(--text-main);
  font-size: 13px;
  padding: 6px 10px;
  outline: none;
}
.conn-input:focus {
  border-color: var(--accent);
}
.conn-full-config {
  border-top: 1px solid var(--border-color);
  padding-top: 14px;
}
.btn-copy-config {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: var(--accent);
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 8px 16px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
}
.btn-copy-config:hover {
  filter: brightness(1.15);
}
.conn-hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-secondary);
}
</style>
