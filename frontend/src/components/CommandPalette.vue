<template>
  <div v-if="isVisible" class="command-palette-overlay" @click.self="close">
    <div class="command-palette">
      <div class="search-container">
        <input
          ref="searchInput"
          v-model="searchQuery"
          type="text"
          placeholder="Type a command or search..."
          @keydown.down.prevent="navigateResults('down')"
          @keydown.up.prevent="navigateResults('up')"
          @keydown.enter.prevent="executeCommand"
          @keydown.esc.prevent="close"
        />
      </div>
      <ul v-if="filteredCommands.length > 0" class="results-list">
        <li
          v-for="(command, index) in filteredCommands"
          :key="index"
          :class="{ active: index === selectedIndex }"
          @click="executeCommand(index)"
          @mouseover="selectedIndex = index"
        >
          <div class="command-icon">
            <span v-if="command.icon">{{ command.icon }}</span>
          </div>
          <div class="command-details">
            <span class="command-label">{{ command.label }}</span>
            <span v-if="command.detail" class="command-detail">{{ command.detail }}</span>
          </div>
        </li>
      </ul>
      <div v-else class="no-results">
        No matching commands found
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useReposStore } from '@/stores/repos'
import { useSystemStore } from '@/stores/system'
import { useFilesStore } from '@/stores/files'
import axios from 'axios'

const props = defineProps({
  modelValue: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:modelValue', 'close', 'command'])

const router = useRouter()
const reposStore = useReposStore()
const systemStore = useSystemStore()
const filesStore = useFilesStore()

const searchInput = ref(null)
const searchQuery = ref('')
const selectedIndex = ref(0)

const isVisible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

const commands = computed(() => {
  const list = [
    {
      label: 'Go to Dashboard',
      action: () => router.push('/dashboard'),
      icon: '🏠',
      detail: 'Navigate to dashboard'
    },
    {
      label: 'Go to Files',
      action: () => router.push('/files'),
      icon: '📂',
      detail: 'Browse files in current repository'
    },
    {
      label: 'Go to Settings',
      action: () => router.push('/settings'),
      icon: '⚙️',
      detail: 'Configure application settings'
    },
    {
      label: 'Sync Current Repository',
      action: async () => {
        if (reposStore.currentRepo) {
           try {
             await reposStore.syncRepo(reposStore.currentRepo)
             systemStore.addNotification(`Synced ${reposStore.currentRepo}`, 'success')
           } catch (e) {
             systemStore.addNotification('Sync failed', 'error')
           }
        } else {
            systemStore.addNotification('No repository selected', 'warning')
        }
      },
      icon: '🔄',
      detail: 'Pull latest changes and push local commits'
    },
    {
      label: 'Create New Repository',
      action: () => {
        // We'll need to trigger the modal in App.vue or handle it here.
        // For simplicity, let's emit a custom event or use a global bus if available.
        // Since App.vue holds the modal state, we might need to expose that or just route if it was a separate page.
        // Given App.vue structure, we might emit an event that App.vue listens to, OR we can access a store that controls UI state if we refactor.
        // For now, let's assume we can trigger it via a store or event. 
        // NOTE: App.vue has showCreateModal ref. We might need to expose a way to open it.
        // Strategy: Emit a 'command' event that App.vue handles?
        emit('command', 'create-repo')
      },
      icon: '➕',
      detail: 'Initialize a new git repository'
    }
  ]

  // Add repo switching commands
  if (reposStore.repos && reposStore.repos.length > 0) {
    reposStore.repos.forEach(repo => {
      if (repo !== reposStore.currentRepo) {
        list.push({
          label: `Switch to ${repo}`,
          action: () => {
             // We need to call selectProject which is in App.vue or move that logic to store.
             // Best practice: Move selectProject logic to store or expose it.
             // reposStore has setCurrentRepo, but App.vue does axios call + filesStore fetch.
             // Let's replicate the logic here or delegate.
             selectProject(repo)
          },
          icon: '🔀',
          detail: `Switch context to ${repo}`
        })
      }
    })
  }

  return list
})

const filteredCommands = computed(() => {
  const query = searchQuery.value.toLowerCase()
  return commands.value.filter(cmd => 
    cmd.label.toLowerCase().includes(query) || 
    (cmd.detail && cmd.detail.toLowerCase().includes(query))
  )
})

async function selectProject(repo) {
    try {
        await axios.post('/api/repos/select', { repo })
        reposStore.currentRepo = repo
        await filesStore.fetchFiles('/')
        systemStore.addNotification(`Switched to ${repo}`, 'success')
    } catch (e) {
        console.error(e)
        systemStore.addNotification(`Failed to switch to ${repo}`, 'error')
    }
}


function navigateResults(direction) {
  if (direction === 'down') {
    selectedIndex.value = (selectedIndex.value + 1) % filteredCommands.value.length
  } else {
    selectedIndex.value = (selectedIndex.value - 1 + filteredCommands.value.length) % filteredCommands.value.length
  }
}

function executeCommand(index) {
  const cmdIndex = typeof index === 'number' ? index : selectedIndex.value
  const command = filteredCommands.value[cmdIndex]
  if (command) {
    command.action()
    close()
  }
}

function close() {
  isVisible.value = false
  searchQuery.value = ''
  selectedIndex.value = 0
}

watch(isVisible, (val) => {
  if (val) {
    nextTick(() => {
      searchInput.value?.focus()
    })
  }
})

// Global keyboard shortcut listener is better placed in App.vue, but this component needs to be mounted there.
</script>

<style scoped>
.command-palette-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: rgba(0, 0, 0, 0.4);
  z-index: 10001; /* Above modal overlay */
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding-top: 14vh;
  backdrop-filter: blur(1px);
}

.command-palette {
  width: 600px;
  max-width: 90vw;
  background: #252526; /* VS Code Quick Open background */
  border-radius: 6px;
  box-shadow: 0 0 20px rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #454545;
}

.search-container {
  padding: 8px;
  background: #252526;
  border-bottom: 1px solid #333;
}

input {
  width: 100%;
  padding: 8px 12px;
  background: #3c3c3c;
  border: 1px solid transparent;
  border-radius: 4px;
  color: #cccccc;
  font-size: 16px;
  outline: none;
  box-sizing: border-box;
}

input:focus {
  border-color: #007fd4; /* VS Code focus blue */
}

input::placeholder {
  color: #858585;
}

.results-list {
  list-style: none;
  margin: 0;
  padding: 4px 0;
  max-height: 400px;
  overflow-y: auto;
}

.results-list li {
  padding: 6px 12px;
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  color: #cccccc;
}

.results-list li.active,
.results-list li:hover {
  background: #2a2d2e;
  color: #ffffff;
}

.command-icon {
  width: 20px;
  display: flex;
  justify-content: center;
  font-size: 16px;
}

.command-details {
  display: flex;
  flex-direction: column;
}

.command-label {
  font-size: 14px;
  font-weight: 500;
}

.command-detail {
  font-size: 12px;
  color: #858585;
}

.results-list li.active .command-detail,
.results-list li:hover .command-detail {
  color: #bbbbbb;
}

.no-results {
  padding: 16px;
  text-align: center;
  color: #858585;
  font-size: 13px;
}
</style>
