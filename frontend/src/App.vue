<template>
  <div class="app-shell" :class="theme">
    <!-- Combined Sidebar (Navigation + Repos) -->
    <aside v-if="showSidebar" class="sidebar">
      <!-- Main Navigation Links -->
      <div class="nav-section">
        <router-link to="/dashboard" class="nav-item" title="Dashboard">
          <span class="icon">🏠</span>
          <span class="label">Dashboard</span>
        </router-link>
        <router-link to="/files" class="nav-item" title="Explorer">
          <span class="icon">📂</span>
          <span class="label">File Browser</span>
        </router-link>
        <router-link to="/settings" class="nav-item" title="Settings">
          <span class="icon">⚙️</span>
          <span class="label">Settings</span>
        </router-link>
      </div>

      <div class="separator"></div>

      <!-- Repositories List -->
      <div class="sidebar-section">
        <div class="section-header">
          <header>REPOSITORIES</header>
          <button class="icon-btn" title="New Repository" @click="showCreateModal = true">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="12" y1="5" x2="12" y2="19"></line>
              <line x1="5" y1="12" x2="19" y2="12"></line>
            </svg>
          </button>
        </div>
        <div class="project-list">
          <div 
            v-for="repo in reposStore.repos" 
            :key="repo"
            class="project-item"
            :class="{ active: reposStore.currentRepo === repo }"
            @click="selectProject(repo)"
          >
            <div class="item-content">
              <span class="icon">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
                </svg>
              </span>
              <span class="name">{{ repo }}</span>
            </div>
              
            <button 
              v-if="repo !== 'default'"
              class="delete-btn" 
              title="Delete Repository" 
              @click.stop="confirmDelete(repo)"
            >
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="3 6 5 6 21 6"></polyline>
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
              </svg>
            </button>
          </div>
        </div>
      </div>
    </aside>

    <!-- Main View -->
    <main class="main-view">
      <router-view v-slot="{ Component }">
        <transition name="fade" mode="out-in">
          <keep-alive>
            <component :is="Component" />
          </keep-alive>
        </transition>
      </router-view>
    </main>

    <!-- Status Bar -->
    <footer class="status-bar">
      <div class="left">
        <div class="status-item" :class="{ ok: systemStore.gitRunning }">
          <span class="dot"></span>
          Git Server: {{ systemStore.gitRunning ? 'Active' : 'Stopped' }}
        </div>
        <div 
          class="status-item interactive" 
          title="Click to copy IP" 
          @click="copyIP"
        >
          IP: {{ systemStore.status.local_ip || '...' }}
        </div>
        <div class="status-item">
          Project: {{ reposStore.currentRepo || 'None' }}
        </div>
      </div>
      <div class="right">
        <!-- 'Prepare for Work' logic can be here or dashboard, keeping simple status here -->
      </div>
    </footer>

    <!-- Notifications -->
    <div class="notification-container">
      <transition-group name="toast">
        <div 
          v-for="notification in systemStore.notifications" 
          :key="notification.id"
          class="toast"
          :class="notification.type"
          @click="systemStore.removeNotification(notification.id)"
        >
          {{ notification.message }}
        </div>
      </transition-group>
    </div>

    <!-- Command Palette -->
    <CommandPalette 
      v-model="showCommandPalette" 
      @command="(cmd) => { if (cmd === 'create-repo') showCreateModal = true }"
    />

    <!-- Create Modal -->
    <transition name="fade">
      <div v-if="showCreateModal" class="modal-overlay" @click.self="showCreateModal = false">
        <div class="modal">
          <h3>Create New Repository</h3>
          <input 
            v-model="newRepoName" 
            placeholder="Repository Name" 
            class="input-field"
            @keyup.enter="createRepository"
          />
          <div class="modal-actions">
            <button class="btn btn-secondary" @click="showCreateModal = false">Cancel</button>
            <button class="btn btn-primary" :disabled="!isValidRepoName" @click="createRepository">Create</button>
          </div>
        </div>
      </div>
    </transition>

    <!-- Delete Confirmation Modal -->
    <transition name="fade">
      <div v-if="repoToDelete" class="modal-overlay" @click.self="repoToDelete = null">
        <div class="modal">
          <h3>Delete Repository</h3>
          <p>Are you sure you want to delete <strong>{{ repoToDelete }}</strong>?</p>
          <p class="warning-text">This action cannot be undone.</p>
          <div class="modal-actions">
            <button class="btn btn-secondary" @click="repoToDelete = null">Cancel</button>
            <button class="btn btn-danger" @click="executeDelete">Delete</button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, onUnmounted } from 'vue'
import { useReposStore } from '@/stores/repos'
import { useSystemStore } from '@/stores/system'
import { useFilesStore } from '@/stores/files'
import CommandPalette from '@/components/CommandPalette.vue'
import axios from 'axios'

const reposStore = useReposStore()
const systemStore = useSystemStore()
const filesStore = useFilesStore()

const theme = ref('dark')
const showSidebar = ref(true)

// Command Palette state
const showCommandPalette = ref(false)

// Modal states
const showCreateModal = ref(false)
const newRepoName = ref('')
const repoToDelete = ref(null)

const isValidRepoName = computed(() => {
  return newRepoName.value && /^[a-zA-Z0-9_-]+$/.test(newRepoName.value)
})

onMounted(async () => {
  await Promise.all([
    reposStore.fetchRepos(),
    systemStore.fetchStatus()
  ])
  
  window.addEventListener('keydown', handleGlobalKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
})

function handleGlobalKeydown(e) {
  if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'p')) {
    e.preventDefault()
    showCommandPalette.value = !showCommandPalette.value
  }
}

async function selectProject(repo) {
  await axios.post('/api/repos/select', { repo })
  reposStore.currentRepo = repo
  await filesStore.fetchFiles('/')
}

async function createRepository() {
  if (!isValidRepoName.value) return
  
  try {
    await reposStore.createRepo(newRepoName.value)
    systemStore.addNotification(`Repository '${newRepoName.value}' created`, 'success')
    showCreateModal.value = false
    newRepoName.value = ''
  } catch (err) {
    systemStore.addNotification(reposStore.error || 'Failed to create repository', 'error')
  }
}

function confirmDelete(repo) {
  repoToDelete.value = repo
}

async function executeDelete() {
  if (!repoToDelete.value) return
  
  try {
    await reposStore.deleteRepo(repoToDelete.value)
    systemStore.addNotification(`Repository '${repoToDelete.value}' deleted`, 'success')
    repoToDelete.value = null
  } catch (err) {
    systemStore.addNotification(reposStore.error || 'Failed to delete repository', 'error')
  }
}

function copyIP() {
  const ip = systemStore.status.local_ip
  if (ip) {
    navigator.clipboard.writeText(ip)
      .then(() => {
        systemStore.addNotification(`IP Copied: ${ip}`, 'success')
      })
      .catch(() => {
        systemStore.addNotification('Failed to copy IP', 'error')
      })
  }
}
</script>

<style>
:root {
  --bg-primary: #1e1e1e;
  --bg-sidebar: #252526;
  --bg-activity: #333333;
  --border-color: #3e3e42;
  --text-main: #cccccc;
  --text-bright: #ffffff;
  --accent: #3794ff;
  --status-bar-bg: #181818;
  --success: #89d185;
  --error: #f48771;
  --warning: #cca700;
}

body {
  margin: 0;
  padding: 0;
  background: var(--bg-primary);
  color: var(--text-main);
  font-family: 'Inter', -apple-system, sans-serif;
  overflow: hidden;
}

.app-shell {
  display: grid;
  grid-template-columns: 250px 1fr; /* Removed activity bar column */
  grid-template-rows: 1fr 22px;
  height: 100vh;
  width: 100vw;
}

/* Sidebar */
.sidebar {
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border-color);
  padding: 10px 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.nav-section {
  padding: 0 10px;
  margin-bottom: 10px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  margin-bottom: 4px;
  border-radius: 6px;
  color: var(--text-main);
  text-decoration: none;
  font-size: 14px;
  transition: background 0.2s;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-bright);
}

.nav-item.router-link-active {
  background: rgba(55, 148, 255, 0.15);
  color: var(--text-bright);
  font-weight: 500;
}

.nav-item .icon {
  font-size: 16px;
  width: 20px;
  text-align: center;
}

.separator {
  height: 1px;
  background: var(--border-color);
  margin: 10px 15px 15px;
  opacity: 0.5;
}

.sidebar header {
  font-size: 11px;
  font-weight: bold;
  padding: 0 20px 8px;
  color: #858585;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.project-item {
  padding: 6px 20px;
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.2s ease;
  border-left: 3px solid transparent;
  color: #999;
}

.project-item:hover {
  background: rgba(255, 255, 255, 0.04);
  color: var(--text-bright);
}

.project-item.active {
  background: rgba(255, 255, 255, 0.08);
  color: var(--text-bright);
  border-left-color: var(--accent);
}

.project-item .icon {
  display: flex;
  align-items: center;
  opacity: 0.7;
}

.project-item.active .icon {
  opacity: 1;
  color: var(--accent);
}

/* Main View */
.main-view {
  background: var(--bg-primary);
  overflow: auto;
  position: relative;
}

/* Status Bar */
.status-bar {
  grid-column: 1 / 3;
  background: var(--status-bar-bg);
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 10px;
  font-size: 12px;
  color: #858585; /* Quieter text color */
  border-top: 1px solid var(--border-color);
}

.status-bar .left { display: flex; gap: 15px; }

.status-item { display: flex; align-items: center; gap: 6px; }
.status-item.interactive {
  cursor: pointer;
  transition: color 0.2s, background-color 0.2s;
  padding: 2px 6px;
  border-radius: 4px;
}
.status-item.interactive:hover {
  color: var(--text-bright);
  background-color: rgba(255, 255, 255, 0.05);
}
.status-item .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #666;
}
.status-item.ok .dot { background: #89d185; }

/* Transitions */
.fade-enter-active, .fade-leave-active { transition: opacity 0.15s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

/* Notifications */
.notification-container {
  position: fixed;
  bottom: 30px;
  right: 20px;
  z-index: 9999;
  display: flex;
  flex-direction: column;
  gap: 10px;
  pointer-events: none;
}

.toast {
  pointer-events: auto;
  background: var(--bg-sidebar);
  border: 1px solid var(--border-color);
  padding: 12px 16px;
  border-radius: 4px;
  color: var(--text-bright);
  box-shadow: 0 4px 12px rgba(0,0,0,0.5);
  font-size: 13px;
  min-width: 250px;
  cursor: pointer;
  display: flex;
  align-items: center;
  border-left: 3px solid var(--accent);
}

.toast.success { border-left-color: var(--success); }
.toast.error { border-left-color: var(--error); }
.toast.info { border-left-color: var(--accent); }

.toast-enter-active, .toast-leave-active { transition: all 0.3s cubic-bezier(0.16, 1, 0.3, 1); }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateX(20px); }

/* Modals */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 10000;
  backdrop-filter: blur(2px);
}

.modal {
  background: var(--bg-sidebar);
  padding: 24px;
  border-radius: 8px;
  width: 400px;
  border: 1px solid var(--border-color);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.5);
}

.modal h3 {
  margin: 0 0 16px;
  font-size: 16px;
  color: var(--text-bright);
}

.modal p {
  margin: 0 0 16px;
  color: var(--text-main);
}

.warning-text {
  color: var(--error) !important;
  font-size: 13px;
}

.input-field {
  width: 100%;
  padding: 8px 12px;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 4px;
  color: var(--text-bright);
  outline: none;
  box-sizing: border-box;
  margin-bottom: 20px;
}

.input-field:focus {
  border-color: var(--accent);
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.btn {
  padding: 8px 16px;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
  border: none;
  transition: all 0.2s;
}

.btn-secondary {
  background: transparent;
  color: var(--text-main);
  border: 1px solid var(--border-color);
}

.btn-secondary:hover {
  background: rgba(255, 255, 255, 0.05);
  color: var(--text-bright);
}

.btn-primary {
  background: var(--accent);
  color: white;
}

.btn-primary:hover:not(:disabled) {
  opacity: 0.9;
}

.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-danger {
  background: var(--error);
  color: white;
}

.btn-danger:hover {
  opacity: 0.9;
}

/* Sidebar Header Actions */
.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-right: 15px;
}

.icon-btn {
  background: none;
  border: none;
  color: #858585;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.icon-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: var(--text-bright);
}

.item-content {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
}

.delete-btn {
  background: none;
  border: none;
  color: #666;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: all 0.2s;
}

.project-item:hover .delete-btn {
  opacity: 1;
}

.delete-btn:hover {
  color: var(--error);
  background: rgba(244, 135, 113, 0.1);
}
</style>
