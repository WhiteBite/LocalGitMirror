<template>
  <div class="dashboard-view">
    <header class="view-header flex justify-between items-center">
      <h1>Dashboard</h1>
      <button @click="panicMode" class="bg-red-600 hover:bg-red-700 text-white px-3 py-1 rounded text-sm font-bold flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" />
        </svg>
        PANIC
      </button>
    </header>

    <div class="view-content">
      <!-- Metrics Row (Top) -->
      <div class="metrics-grid">
        <!-- Git Status -->
        <div class="metric-card">
          <div class="metric-icon" :class="{ 'active': gitRunning, 'inactive': !gitRunning }">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"></path>
              <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"></path>
            </svg>
          </div>
          <div class="metric-info">
            <label>Git Server</label>
            <div class="value-row">
              <span class="status-text" :class="{ 'text-success': gitRunning, 'text-error': !gitRunning }">
                {{ gitRunning ? 'Active' : 'Stopped' }}
              </span>
              <button v-if="!gitRunning" class="mini-btn" @click="startGitServer">Start</button>
            </div>
            <div v-if="gitRunning" class="sub-text warning">Unencrypted (LAN only)</div>
          </div>
        </div>

        <!-- Disk Usage -->
        <div class="metric-card">
          <div class="metric-icon" :class="{ 'warn': metrics.disk_percent > 85, 'inactive': metrics.disk_percent > 95 }">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M6 2h12a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z"></path>
              <line x1="6" y1="10" x2="18" y2="10"></line>
              <line x1="6" y1="18" x2="18" y2="18"></line>
            </svg>
          </div>
          <div class="metric-info">
            <label>Disk Usage</label>
            <div class="value-row">
              <span class="value">{{ metrics.disk_percent }}%</span>
              <span class="unit">used</span>
            </div>
            <div class="sub-text">{{ metrics.disk_used_gb }}GB / {{ metrics.disk_total_gb }}GB</div>
          </div>
        </div>

        <!-- Memory Usage -->
        <div class="metric-card">
          <div class="metric-icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z"></path>
              <line x1="12" y1="2" x2="12" y2="6"></line>
              <line x1="12" y1="18" x2="12" y2="22"></line>
              <line x1="6" y1="2" x2="6" y2="6"></line>
              <line x1="6" y1="18" x2="6" y2="22"></line>
              <line x1="18" y1="2" x2="18" y2="6"></line>
              <line x1="18" y1="18" x2="18" y2="22"></line>
            </svg>
          </div>
          <div class="metric-info">
            <label>Memory Usage</label>
            <div class="value-row">
              <span class="value">{{ metrics.memory_percent }}%</span>
            </div>
            <div class="sub-text">{{ metrics.memory_used_gb }}GB used</div>
          </div>
        </div>
      </div>

      <!-- Active Project (Middle) -->
      <section class="active-project-card">
        <div class="card-header">
          <div class="project-title">
            <span class="icon" :class="{ 'healthy': gitRunning && metrics.disk_percent < 90 }">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
              </svg>
              <span class="health-dot"></span>
            </span>
            <div>
              <h2 class="text-2xl font-bold">{{ currentRepo }}</h2>
              <p class="activity-text">Last Activity: {{ lastActivity }}</p>
              <div class="git-url-row">
                <input 
                  readonly 
                  :value="`git://${localIP}:${gitPort}/${currentRepo}`" 
                  class="git-url-input"
                  @click="$event.target.select()"
                />
                <button class="btn-icon" title="Copy setup command" @click="copyGitCommand">
                  <svg v-if="!copied" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                  <svg v-else viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"></polyline></svg>
                </button>
              </div>
            </div>
          </div>
          <div class="actions">
            <button class="btn secondary" @click="openExplorer">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2z" /></svg>
              Reveal
            </button>
            <button class="btn primary" :disabled="syncing" @click="prepareForWork">
              <svg v-if="syncing" class="animate-spin -ml-1 mr-2 h-4 w-4 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              <span v-if="!syncing">Prepare for Work</span>
              <span v-else>Preparing...</span>
            </button>
          </div>
        </div>
      </section>

      <!-- Sync Guide (Collapsible) -->
      <section class="sync-guide-section">
        <details>
          <summary>
            <span class="summary-title">Sync Workflow Guide</span>
            <span class="chevron">▼</span>
          </summary>
          <div class="guide-content">
            <div class="steps-grid">
              <div class="guide-step">
                <div class="step-badge">1</div>
                <div class="step-content">
                  <strong>On Work PC</strong>
                  <div class="code-block">git remote add home git://...</div>
                  <div class="code-block" style="margin-top:4px">git push home main</div>
                </div>
              </div>
              <div class="guide-step arrow">→</div>
              <div class="guide-step">
                <div class="step-badge">2</div>
                <div class="step-content">
                  <strong>On Home PC</strong>
                  <div class="step-desc">Edit files, then click <span class="highlight">Prepare for Work</span></div>
                </div>
              </div>
              <div class="guide-step arrow">→</div>
              <div class="guide-step">
                <div class="step-badge">3</div>
                <div class="step-content">
                  <strong>On Work PC</strong>
                  <div class="code-block">git pull home main</div>
                </div>
              </div>
            </div>
          </div>
        </details>
      </section>

      <!-- System Log -->
      <section class="log-section">
        <SystemLog />
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useSystemStore } from '@/stores/system'
import SystemLog from '@/components/SystemLog.vue'
import axios from 'axios'

const systemStore = useSystemStore()

const gitRunning = ref(false)
const gitStatus = ref({ modified: 0, untracked: 0 })
const currentRepo = ref('default')
const localIP = ref('localhost')
const gitPort = ref(8081)
const storagePath = ref('storage')
const metrics = ref({
  disk_percent: 0,
  disk_used_gb: 0,
  disk_total_gb: 0,
  memory_percent: 0,
  memory_used_gb: 0
})

const syncing = ref(false)
const copied = ref(false)
const lastActivity = ref('Never')

onMounted(async () => {
  await fetchStatus()
  await fetchMetrics()
  // Refresh metrics every 30s
  setInterval(fetchMetrics, 30000)
})

async function fetchStatus() {
  try {
    const response = await axios.get('/api/status')
    const data = response.data
    gitRunning.value = data.git_running
    currentRepo.value = data.current_repo
    localIP.value = data.local_ip || 'localhost'
    gitPort.value = data.git_port || 8081
    storagePath.value = data.storage_path || 'storage'
    gitStatus.value = {
      modified: data.change_count || 0
    }
    
    if (data.last_sync_time) {
      lastActivity.value = new Date(data.last_sync_time).toLocaleString()
    }
  } catch (error) {
    console.error('Failed to fetch status:', error)
  }
}

async function fetchMetrics() {
  try {
    const response = await axios.get('/api/metrics')
    metrics.value = response.data
  } catch (error) {
    console.error('Failed to fetch metrics:', error)
  }
}

function copyGitCommand() {
  const url = `git://${localIP.value}:${gitPort.value}/${currentRepo.value}`
  const command = `git remote add home ${url}`
  navigator.clipboard.writeText(command)
  copied.value = true
  setTimeout(() => copied.value = false, 2000)
}

async function openExplorer() {
  try {
    await axios.post('/api/system/open-editor')
  } catch (error) {
    console.error('Failed to open explorer:', error)
  }
}

async function startGitServer() {
  try {
    const response = await axios.post('/api/git/start')
    const data = response.data
    if (data.success) gitRunning.value = true
  } catch (error) {
    console.error('Failed to start git server:', error)
  }
}

async function prepareForWork() {
  if (syncing.value) return
  syncing.value = true
  try {
    // Add a small delay so user sees the spinner even if fast
    const minTime = new Promise(resolve => setTimeout(resolve, 800))
    const request = axios.post('/api/git/save-and-sync')
    
    const [_, response] = await Promise.all([minTime, request])
    const data = response.data
    
    if (data.success) {
      systemStore.addNotification('Changes prepared! You can now "git pull" on your work PC.', 'success')
      await fetchStatus()
    } else {
      systemStore.addNotification('Failed: ' + data.message, 'error')
    }
  } catch (error) {
    console.error('Failed to prepare for work:', error)
    systemStore.addNotification('Failed to prepare for work: ' + (error.response?.data?.detail || error.message), 'error')
  } finally {
    syncing.value = false
  }
}

async function panicMode() {
  if (confirm("🚨 PANIC MODE 🚨\n\nKill server immediately? This will stop all connections.")) {
    try {
      await axios.post('/api/system/panic')
    } catch (e) {
      // Ignore error as server dies
    }
    window.close()
    document.body.innerHTML = "<h1 style='color:red;text-align:center;margin-top:20%'>SERVER TERMINATED</h1>"
  }
}
</script>

<style scoped>
.dashboard-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  color: var(--text-main);
}

.view-header {
  padding: 20px 30px;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-primary);
}

.view-header h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 500;
  color: var(--text-bright);
}

.view-content {
  padding: 30px;
  max-width: 1200px;
  margin: 0 auto;
  width: 100%;
}

/* Metrics Grid */
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
  margin-bottom: 30px;
}

.metric-card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 15px;
}

.metric-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.05);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
}

.metric-icon svg { width: 20px; height: 20px; }
.metric-icon.active { color: var(--success); background: rgba(137, 209, 133, 0.1); }
.metric-icon.inactive { color: var(--error); background: rgba(244, 135, 113, 0.1); }
.metric-icon.warn { color: var(--warning); background: rgba(204, 167, 0, 0.1); }

.metric-info { flex: 1; }
.metric-info label {
  font-size: 11px;
  text-transform: uppercase;
  color: var(--text-secondary);
  display: block;
  margin-bottom: 4px;
  letter-spacing: 0.5px;
}

.value-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.status-text { font-weight: 600; font-size: 15px; }
.text-success { color: var(--success); }
.text-error { color: var(--error); }

.value { font-size: 20px; font-weight: 600; color: var(--text-bright); }
.unit { font-size: 12px; color: var(--text-secondary); margin-left: 4px; }
.ip-address { font-family: 'Monaco', monospace; font-size: 14px; color: var(--text-bright); }

.sub-text.warning { font-size: 11px; color: var(--warning); margin-top: 4px; }
.sub-text { font-size: 11px; color: var(--text-secondary); margin-top: 4px; }
.mini-btn { padding: 2px 8px; font-size: 11px; }

/* Active Project Card */
.active-project-card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 25px;
  margin-bottom: 30px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.project-title {
  display: flex;
  align-items: center;
  gap: 15px;
}

.project-title .icon {
  width: 48px;
  height: 48px;
  background: var(--accent);
  color: white;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}

.health-dot {
  position: absolute;
  bottom: -2px;
  right: -2px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--error);
  border: 2px solid var(--bg-card);
}

.healthy .health-dot {
  background: var(--success);
}

.activity-text {
  font-size: 11px;
  color: var(--text-secondary);
  margin-top: 2px;
}

.project-title h2 { margin: 0; font-size: 24px; color: var(--text-bright); font-weight: 700; }
.project-title .path { margin: 4px 0 0; color: var(--text-secondary); font-size: 13px; font-family: monospace; }

.git-url-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
}

.git-url-input {
  background: rgba(0,0,0,0.2);
  border: 1px solid var(--border-color);
  border-radius: 4px;
  color: var(--accent);
  font-family: monospace;
  font-size: 12px;
  padding: 4px 8px;
  width: 300px;
  outline: none;
}

.git-url-input:focus { border-color: var(--accent); }

.btn-icon {
  background: transparent;
  border: none;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.btn-icon:hover { color: var(--text-bright); background: rgba(255,255,255,0.05); }

.actions { display: flex; gap: 10px; }
.actions .btn { display: flex; align-items: center; gap: 8px; }

/* Sync Guide */
.sync-guide-section {
  margin-bottom: 30px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: var(--bg-sidebar);
  overflow: hidden;
}

details summary {
  padding: 15px 20px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 500;
  color: var(--text-secondary);
  transition: background 0.2s;
}

details summary:hover { color: var(--text-bright); background: rgba(255, 255, 255, 0.02); }
details[open] summary { border-bottom: 1px solid var(--border-color); }
.summary-title { font-size: 14px; text-transform: uppercase; letter-spacing: 0.5px; }

.guide-content { padding: 20px; background: var(--bg-card); }

.steps-grid {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.guide-step { display: flex; align-items: flex-start; gap: 12px; flex: 1; }
.guide-step.arrow { flex: 0; padding: 0 20px; color: var(--text-secondary); font-size: 20px; }

.step-badge {
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  color: var(--text-secondary);
  width: 24px;
  height: 24px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: bold;
  flex-shrink: 0;
}

.step-content strong { display: block; color: var(--text-bright); font-size: 13px; margin-bottom: 6px; }
.step-content p { font-size: 13px; color: var(--text-secondary); margin: 0; }
.step-desc { font-size: 13px; color: var(--text-secondary); }
.code-block {
  background: #111;
  padding: 4px 8px;
  border-radius: 4px;
  font-family: monospace;
  font-size: 12px;
  color: var(--success);
  display: inline-block;
}
.highlight { color: var(--accent); font-weight: 500; }

.log-section {
  border-top: 1px solid var(--border-color);
  padding-top: 30px;
}
</style>
