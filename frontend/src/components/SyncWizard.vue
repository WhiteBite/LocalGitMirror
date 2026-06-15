<template>
  <section class="sync-wizard">
    <div class="wizard-header">
      <div class="header-icon">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M12 2L2 7l10 5 10-5-10-5z"/>
          <path d="M2 17l10 5 10-5"/>
          <path d="M2 12l10 5 10-5"/>
        </svg>
      </div>
      <div>
        <h3>{{ t('syncWizard.title') }}</h3>
        <p class="subtitle">{{ t('syncWizard.subtitle') }}</p>
      </div>
    </div>

    <div class="sync-status">
      <div class="status-item">
        <label>{{ t('syncWizard.status.current') }}</label>
        <div class="status-value">
          <span class="status-indicator" :class="syncState.status"></span>
          {{ syncState.lastSync || t('syncWizard.status.no_backups') }}
        </div>
      </div>
      <div class="status-item">
        <label>{{ t('syncWizard.status.last_size') }}</label>
        <div class="status-value">{{ syncState.lastSize || t('syncWizard.status.na') }}</div>
      </div>
      <div v-if="syncState.backupCount > 0" class="status-item">
        <label>Backups in archive</label>
        <div class="status-value">{{ syncState.backupCount }} files</div>
      </div>
    </div>

    <div class="procedures-grid">
      <div class="procedure-card">
        <div class="procedure-header">
          <div class="step-number">1</div>
          <h4>{{ t('syncWizard.steps.step1.title') }}</h4>
        </div>
        <p class="procedure-desc">{{ t('syncWizard.steps.step1.desc') }}</p>
        <div class="script-downloads">
          <button @click="downloadScript('stealth')" class="download-btn">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
              <polyline points="7 10 12 15 17 10"/>
              <line x1="12" y1="15" x2="12" y2="3"/>
            </svg>
            backup_work_stealth.bat
          </button>
        </div>
      </div>

      <div class="procedure-card">
        <div class="procedure-header">
          <div class="step-number">2</div>
          <h4>{{ t('syncWizard.steps.step2.title') }}</h4>
        </div>
        <p class="procedure-desc">{{ t('syncWizard.steps.step2.desc') }}</p>
        <div class="code-example">
          <code>backup_work_stealth.bat [password]</code>
        </div>
        <div class="note">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="16" x2="12" y2="12"/>
            <line x1="12" y1="8" x2="12.01" y2="8"/>
          </svg>
          {{ t('syncWizard.steps.step2.output') }}
        </div>
      </div>

      <div class="procedure-card">
        <div class="procedure-header">
          <div class="step-number">3</div>
          <h4>{{ t('syncWizard.steps.step3.title') }}</h4>
        </div>
        <p class="procedure-desc">{{ t('syncWizard.steps.step3.desc') }}</p>
        <button @click="openFileManager" class="action-btn">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/>
          </svg>
          {{ t('syncWizard.steps.step3.button') }}
        </button>
      </div>

      <div class="procedure-card">
        <div class="procedure-header">
          <div class="step-number">4</div>
          <h4>{{ t('syncWizard.steps.step4.title') }}</h4>
        </div>
        <p class="procedure-desc">{{ t('syncWizard.steps.step4.desc') }}</p>
        <button @click="applyStealthSync" class="action-btn" :disabled="syncLoading">
          <svg v-if="!syncLoading" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="23 4 23 10 17 10"/>
            <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
          </svg>
          <svg v-else class="spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 12a9 9 0 1 1-6.219-8.56"/>
          </svg>
          {{ syncLoading ? t('syncWizard.status.syncing') : t('syncWizard.steps.step4.button') }}
        </button>
        <div v-if="syncResult" class="note" :class="{ success: syncResult.success }">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline v-if="syncResult.success" points="20 6 9 17 4 12"/>
            <circle v-else cx="12" cy="12" r="10"/>
            <line v-if="!syncResult.success" x1="12" y1="16" x2="12" y2="12"/>
            <line v-if="!syncResult.success" x1="12" y1="8" x2="12.01" y2="8"/>
          </svg>
          {{ syncResult.message }}
          <div v-if="!syncResult.success && syncResult.scanned_folders" class="debug-details">
            <div class="debug-title">{{ t('syncWizard.debug.scanned') }}</div>
            <div class="debug-text">
              {{ syncResult.scanned_folders.join(', ') }}
            </div>
            <div v-if="syncResult.expected_pattern" class="debug-text">
              {{ t('syncWizard.debug.expected') }}: <span class="mono">{{ syncResult.expected_pattern }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="technical-notes">
      <details>
        <summary>{{ t('syncWizard.technical.title') }}</summary>
        <div class="notes-content">
          <h5>{{ t('syncWizard.technical.snapshot.title') }}</h5>
          <ul>
            <li><strong>{{ t('syncWizard.technical.snapshot.first_run') }}:</strong> {{ t('syncWizard.technical.snapshot.first_run_desc') }}</li>
            <li><strong>{{ t('syncWizard.technical.snapshot.subsequent') }}:</strong> {{ t('syncWizard.technical.snapshot.subsequent_desc') }}</li>
            <li><strong>{{ t('syncWizard.technical.snapshot.encryption') }}:</strong> {{ t('syncWizard.technical.snapshot.encryption_desc') }}</li>
            <li><strong>{{ t('syncWizard.technical.snapshot.compression') }}:</strong> {{ t('syncWizard.technical.snapshot.compression_desc') }}</li>
            <li><strong>{{ t('syncWizard.technical.snapshot.tracking') }}:</strong> {{ t('syncWizard.technical.snapshot.tracking_desc') }}</li>
          </ul>
          <h5>{{ t('syncWizard.technical.benefits.title') }}</h5>
          <ul>
            <li>{{ t('syncWizard.technical.benefits.traffic') }}</li>
            <li>{{ t('syncWizard.technical.benefits.state') }}</li>
            <li>{{ t('syncWizard.technical.benefits.secure') }}</li>
            <li>{{ t('syncWizard.technical.benefits.naming') }}</li>
          </ul>
          <h5>{{ t('syncWizard.technical.sizes.title') }}</h5>
          <ul>
            <li><strong>{{ t('syncWizard.technical.sizes.full') }}:</strong> {{ t('syncWizard.technical.sizes.full_desc') }}</li>
            <li><strong>{{ t('syncWizard.technical.sizes.daily') }}:</strong> {{ t('syncWizard.technical.sizes.daily_desc') }}</li>
            <li><strong>{{ t('syncWizard.technical.sizes.weekly') }}:</strong> {{ t('syncWizard.technical.sizes.weekly_desc') }}</li>
          </ul>
          <h5>{{ t('syncWizard.technical.troubleshooting.title') }}</h5>
          <ul>
            <li><strong>{{ t('syncWizard.technical.troubleshooting.no_changes') }}:</strong> {{ t('syncWizard.technical.troubleshooting.no_changes_desc') }}</li>
            <li><strong>{{ t('syncWizard.technical.troubleshooting.invalid') }}:</strong> {{ t('syncWizard.technical.troubleshooting.invalid_desc') }}</li>
            <li><strong>{{ t('syncWizard.technical.troubleshooting.failed') }}:</strong> {{ t('syncWizard.technical.troubleshooting.failed_desc') }}</li>
          </ul>
        </div>
      </details>
    </div>
  </section>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import axios from 'axios'

const { t } = useI18n()

const syncState = ref({
  status: 'idle',
  lastSync: null,
  lastSize: null,
  backupCount: 0,
  latestFile: null
})

const syncLoading = ref(false)
const syncResult = ref(null)

onMounted(async () => {
  await fetchSyncState()
})

async function fetchSyncState() {
  try {
    const response = await axios.get('/api/session/state')
    if (response.data.success) {
      syncState.value = response.data.state
    }
  } catch (error) {
    console.error('Failed to fetch sync state:', error)
  }
}

async function downloadScript(type) {
  try {
    const filename = 'backup_work_stealth.bat'
    const response = await axios.get(`/api/scripts/${filename}`, {
      responseType: 'blob'
    })
    
    const url = window.URL.createObjectURL(new Blob([response.data]))
    const link = document.createElement('a')
    link.href = url
    link.setAttribute('download', filename)
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.URL.revokeObjectURL(url)
  } catch (error) {
    console.error('Failed to download script:', error)
  }
}

async function applyStealthSync() {
  syncLoading.value = true
  syncResult.value = null
  
  // OpSec: Random delay 3-5 seconds before sending request
  const delay = Math.random() * 2000 + 3000
  const seconds = Math.ceil(delay / 1000)
  
  // Show countdown
  for (let i = seconds; i > 0; i--) {
    syncResult.value = {
      success: false,
      message: t('syncWizard.status.preparing', { seconds: i })
    }
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  
  try {
    const response = await axios.post('/api/documents/apply')
    syncResult.value = response.data
    
    if (response.data.success) {
      // Refresh sync state
      await fetchSyncState()
    }
  } catch (error) {
    console.error('Failed to apply sync:', error)
    syncResult.value = {
      success: false,
      message: error.response?.data?.detail || t('syncWizard.error.sync_failed')
    }
  } finally {
    syncLoading.value = false
  }
}

function openFileManager() {
  // Set mode to shared and navigate to files page
  localStorage.setItem('fileBrowserMode', 'shared')
  window.location.href = '/files'
}
</script>

<style scoped>
.sync-wizard {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 24px;
  margin-bottom: 30px;
}

.wizard-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--border-color);
}

.header-icon {
  width: 48px;
  height: 48px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
}

.header-icon svg {
  width: 24px;
  height: 24px;
}

.wizard-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-bright);
}

.subtitle {
  margin: 4px 0 0 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.sync-status {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
  padding: 16px;
  background: var(--bg-primary);
  border-radius: 6px;
}

.status-item label {
  display: block;
  font-size: 11px;
  text-transform: uppercase;
  color: var(--text-secondary);
  margin-bottom: 6px;
  letter-spacing: 0.5px;
}

.status-value {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-bright);
  display: flex;
  align-items: center;
  gap: 8px;
}

.status-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-secondary);
}

.status-indicator.active {
  background: var(--success);
  box-shadow: 0 0 8px var(--success);
}

.procedures-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.procedure-card {
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 20px;
}

.procedure-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.step-number {
  width: 28px;
  height: 28px;
  background: var(--accent);
  color: white;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 600;
  flex-shrink: 0;
}

.procedure-card h4 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-bright);
}

.procedure-desc {
  margin: 0 0 16px 0;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.5;
}

.script-downloads {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.download-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 4px;
  color: var(--text-main);
  font-size: 12px;
  font-family: monospace;
  cursor: pointer;
  transition: all 0.2s;
}

.download-btn:hover {
  background: var(--bg-hover);
  border-color: var(--accent);
}

.download-btn svg {
  width: 14px;
  height: 14px;
  color: var(--accent);
}

.download-btn.secondary {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
}

.download-btn.secondary:hover {
  background: var(--bg-hover);
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.spin {
  animation: spin 1s linear infinite;
}

.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.code-example {
  background: #0d1117;
  border: 1px solid #30363d;
  border-radius: 4px;
  padding: 12px;
  margin-bottom: 12px;
}

.code-example code {
  font-family: monospace;
  font-size: 12px;
  color: #58a6ff;
}

.note {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px;
  background: rgba(255, 255, 255, 0.03);
  border-left: 3px solid var(--text-secondary);
  border-radius: 4px;
  font-size: 12px;
  color: var(--text-secondary);
}

.debug-details {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed rgba(255, 255, 255, 0.12);
}

.debug-title {
  font-size: 11px;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.6px;
  margin-bottom: 4px;
}

.debug-text {
  font-size: 12px;
  color: var(--text-secondary);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
  color: var(--text-primary);
}

.note.success {
  border-left-color: var(--success);
  color: var(--success);
}

.note svg {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  margin-top: 2px;
}

.action-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: var(--accent);
  color: white;
  border: none;
  border-radius: 4px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  width: 100%;
  justify-content: center;
}

.action-btn:hover {
  background: var(--accent-hover);
  transform: translateY(-1px);
}

.action-btn svg {
  width: 16px;
  height: 16px;
}

.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.spin {
  animation: spin 1s linear infinite;
}

.technical-notes {
  margin-top: 20px;
  border-top: 1px solid var(--border-color);
  padding-top: 20px;
}

.technical-notes details {
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  overflow: hidden;
}

.technical-notes summary {
  padding: 12px 16px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  user-select: none;
}

.technical-notes summary:hover {
  background: var(--bg-hover);
}

.notes-content {
  padding: 16px;
  border-top: 1px solid var(--border-color);
}

.notes-content h5 {
  margin: 0 0 12px 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-bright);
}

.notes-content ul {
  margin: 0 0 16px 0;
  padding-left: 20px;
}

.notes-content li {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.6;
  margin-bottom: 6px;
}

.notes-content strong {
  color: var(--text-main);
}
</style>
