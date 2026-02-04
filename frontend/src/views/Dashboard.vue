<template>
  <div class="dashboard-view">
    <header class="view-header">
      <h1>Dashboard</h1>
    </header>

    <div class="view-content">
      <!-- Welcome / Project Card -->
      <section class="info-card primary">
        <div class="card-body">
          <h2>Active Project: {{ currentRepo }}</h2>
          <p class="path">Location: <code>{{ storagePath }}/{{ currentRepo }}</code></p>
          <div class="actions">
            <button @click="openExplorer" class="btn secondary">Open in Explorer</button>
            <button @click="prepareForWork" class="btn primary" :disabled="syncing">
              {{ syncing ? 'Processing...' : 'Prepare for Work' }}
            </button>
          </div>
        </div>
      </section>

      <!-- Status Grid -->
      <div class="status-grid">
        <div class="status-card">
          <label>GIT SERVER</label>
          <div class="value" :class="{ ok: gitRunning }">
            {{ gitRunning ? 'Running' : 'Stopped' }}
            <button v-if="!gitRunning" @click="startGitServer" class="mini-btn">Start</button>
          </div>
          <div v-if="gitRunning" class="protocol-warning">
            ⚠️ git:// is unencrypted. Use within trusted network.
          </div>
        </div>
        <div class="status-card">
          <label>UNCOMMITTED CHANGES</label>
          <div class="value" :class="{ warn: gitStatus.modified > 0 }">
            {{ gitStatus.modified }} files
          </div>
        </div>
        <div class="status-card">
          <label>LOCAL IP</label>
          <div class="value">{{ localIP }}</div>
        </div>
      </div>

      <!-- Quick Guide -->
      <section class="guide-section">
        <h3>Sync Guide</h3>
        <div class="steps">
          <div class="step">
            <span class="num">1</span>
            <div class="text">
              <strong>Work PC: Push</strong>
              <code>git push home main</code>
            </div>
          </div>
          <div class="step">
            <span class="num">2</span>
            <div class="text">
              <strong>Home PC: Prepare</strong>
              <p>Click "Prepare for Work" button above to commit changes.</p>
            </div>
          </div>
          <div class="step">
            <span class="num">3</span>
            <div class="text">
              <strong>Work PC: Pull</strong>
              <code>git pull home main</code>
            </div>
          </div>
        </div>
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
import { useRouter } from 'vue-router'
import { useReposStore } from '@/stores/repos'
import { useSystemStore } from '@/stores/system'
import SystemLog from '@/components/SystemLog.vue'

const router = useRouter()
const reposStore = useReposStore()
const systemStore = useSystemStore()

const gitRunning = ref(false)
const gitStatus = ref({ modified: 0, untracked: 0 })
const currentRepo = ref('default')
const localIP = ref('localhost')
const storagePath = ref('storage')
const syncing = ref(false)

onMounted(async () => {
  await fetchStatus()
})

async function fetchStatus() {
  try {
    const response = await fetch('/api/status')
    const data = await response.json()
    gitRunning.value = data.git_running
    currentRepo.value = data.current_repo
    localIP.value = data.local_ip || 'localhost'
    storagePath.value = data.storage_path || 'storage'
    gitStatus.value = {
      modified: data.change_count || 0
    }
  } catch (error) {
    console.error('Failed to fetch status:', error)
  }
}

async function openExplorer() {
  try {
    await fetch('/api/system/open-editor', { method: 'POST' })
  } catch (error) {
    console.error('Failed to open explorer:', error)
  }
}

async function startGitServer() {
  try {
    const response = await fetch('/api/git/start', { method: 'POST' })
    const data = await response.json()
    if (data.success) gitRunning.value = true
  } catch (error) {
    console.error('Failed to start git server:', error)
  }
}

async function prepareForWork() {
  if (syncing.value) return
  syncing.value = true
  try {
    const response = await fetch('/api/git/save-and-sync', { method: 'POST' })
    const data = await response.json()
    if (data.success) {
      alert('Changes prepared! You can now "git pull" on your work PC.')
      fetchStatus()
    } else {
      alert('Failed: ' + data.message)
    }
  } catch (error) {
    console.error('Failed to prepare for work:', error)
  } finally {
    syncing.value = false
  }
}
</script>

<style scoped>
.dashboard-view {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.view-header {
  padding: 20px 30px;
  border-bottom: 1px solid var(--border-color);
}

.view-header h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 500;
  color: var(--text-bright);
}

.view-content {
  padding: 30px;
  max-width: 1000px;
  margin: 0 auto;
  width: 100%;
}

.info-card {
  background: var(--bg-sidebar);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 25px;
  margin-bottom: 30px;
}

.info-card h2 { margin: 0 0 10px; font-size: 18px; color: var(--text-bright); }
.info-card .path { color: #858585; font-size: 13px; margin-bottom: 20px; }
.info-card code { background: #000; padding: 2px 6px; border-radius: 4px; color: #d7ba7d; }

.actions { display: flex; gap: 12px; }

.btn {
  padding: 8px 16px;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
  border: none;
  transition: opacity 0.2s;
}

.btn.primary { background: var(--accent); color: white; }
.btn.secondary { background: #3a3d41; color: white; }
.btn:hover { opacity: 0.9; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }

.status-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
  margin-bottom: 40px;
}

.status-card {
  background: var(--bg-sidebar);
  border: 1px solid var(--border-color);
  padding: 15px;
  border-radius: 4px;
}

.status-card label {
  display: block;
  font-size: 10px;
  color: #858585;
  margin-bottom: 8px;
  font-weight: bold;
}

.status-card .value {
  font-size: 16px;
  color: var(--text-bright);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.value.ok { color: #89d185; }
.value.warn { color: #cca700; }

.protocol-warning {
  margin-top: 8px;
  font-size: 10px;
  color: #cca700;
  line-height: 1.2;
}

.mini-btn {
  background: #3a3d41;
  color: white;
  border: none;
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 2px;
  cursor: pointer;
}

.guide-section {
  margin-bottom: 40px;
}

.guide-section h3 { font-size: 14px; margin-bottom: 20px; color: #858585; }

.steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 30px;
}

.step { display: flex; gap: 15px; }
.step .num {
  width: 24px;
  height: 24px;
  background: var(--accent);
  color: white;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: justify-center;
  font-size: 12px;
  font-weight: bold;
  flex-shrink: 0;
}

.step strong { display: block; font-size: 13px; color: var(--text-bright); margin-bottom: 5px; }
.step p, .step code { font-size: 12px; color: #858585; margin: 0; }
.step code { display: block; margin-top: 8px; color: var(--accent); background: rgba(0, 122, 204, 0.1); padding: 4px; }

.log-section {
  border-top: 1px solid var(--border-color);
  padding-top: 30px;
}
</style>
