<template>
  <div class="app-shell" :class="theme">
    <!-- Activity Bar (Far Left) -->
    <aside class="activity-bar">
      <div class="top-icons">
        <router-link to="/dashboard" class="nav-icon" title="Dashboard">
          <svg viewBox="0 0 24 24"><path d="M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z"/></svg>
        </router-link>
        <router-link to="/files" class="nav-icon" title="Explorer">
          <svg viewBox="0 0 24 24"><path d="M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2z"/></svg>
        </router-link>
      </div>
      <div class="bottom-icons">
        <router-link to="/settings" class="nav-icon" title="Settings">
          <svg viewBox="0 0 24 24"><path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/></svg>
        </router-link>
      </div>
    </aside>

    <!-- Side Bar (Navigator) -->
    <aside v-if="showSidebar" class="sidebar">
      <div class="sidebar-section">
        <header>REPOSITORIES</header>
        <div class="project-list">
          <div 
            v-for="repo in reposStore.repos" 
            :key="repo"
            class="project-item"
            :class="{ active: reposStore.currentRepo === repo }"
            @click="selectProject(repo)"
          >
            <span class="icon">📁</span>
            <span class="name">{{ repo }}</span>
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
        <div class="status-item">
          IP: {{ systemStore.status.local_ip || '...' }}
        </div>
        <div class="status-item">
          Project: {{ reposStore.currentRepo || 'None' }}
        </div>
      </div>
      <div class="right">
        <button class="sync-btn" @click="prepareForWork" :disabled="syncing">
          {{ syncing ? 'Committing...' : 'Prepare for Work' }}
        </button>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useReposStore } from '@/stores/repos'
import { useSystemStore } from '@/stores/system'
import { useFilesStore } from '@/stores/files'

const reposStore = useReposStore()
const systemStore = useSystemStore()
const filesStore = useFilesStore()

const theme = ref('dark')
const showSidebar = ref(true)
const syncing = ref(false)

onMounted(async () => {
  await Promise.all([
    reposStore.fetchRepos(),
    systemStore.fetchStatus()
  ])
})

async function selectProject(repo) {
  await fetch('/api/repos/select', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ repo })
  })
  reposStore.currentRepo = repo
  await filesStore.fetchFiles('/')
}

async function prepareForWork() {
  if (syncing.value) return
  syncing.value = true
  try {
    const response = await fetch('/api/git/save-and-sync', { method: 'POST' })
    const data = await response.json()
    if (data.success) {
      alert('Success: Project state committed. You can pull on Work PC.')
    } else {
      alert('Error: ' + data.message)
    }
  } finally {
    syncing.value = false
  }
}
</script>

<style>
:root {
  --bg-primary: #1e1e1e;
  --bg-sidebar: #252526;
  --bg-activity: #333333;
  --border-color: #3c3c3c;
  --text-main: #cccccc;
  --text-bright: #ffffff;
  --accent: #007acc;
  --status-bar-bg: #007acc;
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
  grid-template-columns: 48px 250px 1fr;
  grid-template-rows: 1fr 22px;
  height: 100vh;
  width: 100vw;
}

/* Activity Bar */
.activity-bar {
  background: var(--bg-activity);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  border-right: 1px solid var(--border-color);
}

.nav-icon {
  width: 28px;
  height: 28px;
  margin-bottom: 20px;
  color: #858585;
  transition: color 0.2s;
  cursor: pointer;
}

.nav-icon svg { fill: currentColor; }
.nav-icon:hover, .router-link-active.nav-icon { color: white; }

/* Side Bar */
.sidebar {
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border-color);
  padding: 10px 0;
  overflow-y: auto;
}

.sidebar header {
  font-size: 11px;
  font-weight: bold;
  padding: 0 15px 10px;
  color: #858585;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.project-item {
  padding: 6px 15px;
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  font-size: 13px;
  transition: background 0.1s;
}

.project-item:hover { background: #2a2d2e; }
.project-item.active { background: #37373d; color: white; }

/* Main View */
.main-view {
  background: var(--bg-primary);
  overflow: auto;
  position: relative;
}

/* Status Bar */
.status-bar {
  grid-column: 1 / 4;
  background: var(--status-bar-bg);
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 10px;
  font-size: 12px;
  color: white;
}

.status-bar .left { display: flex; gap: 15px; }

.status-item { display: flex; align-items: center; gap: 5px; }
.status-item .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ff4444;
}
.status-item.ok .dot { background: #89d185; }

.sync-btn {
  background: rgba(255, 255, 255, 0.15);
  border: none;
  color: white;
  padding: 1px 8px;
  font-size: 11px;
  cursor: pointer;
  border-radius: 2px;
}
.sync-btn:hover { background: rgba(255, 255, 255, 0.25); }

/* Transitions */
.fade-enter-active, .fade-leave-active { transition: opacity 0.15s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
