<template>
  <div v-if="isVisible" class="command-palette-overlay" @click.self="close">
    <div class="command-palette">
      <div class="search-container">
        <input
          ref="searchInput"
          v-model="searchQuery"
          type="text"
          :placeholder="t('commandPalette.type_command_or_search')"
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
        {{ t('commandPalette.no_matching_commands') }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useReposStore } from '@/stores/repos'
import { useSystemStore } from '@/stores/system'
import { useFilesStore } from '@/stores/files'
import axios from 'axios'

const { t } = useI18n()

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
      label: t('commandPalette.go_to_dashboard'),
      action: () => router.push('/dashboard'),
      icon: '🏠',
      detail: t('commandPalette.navigate_to_dashboard')
    },
    {
      label: t('commandPalette.go_to_files'),
      action: () => router.push('/files'),
      icon: '📂',
      detail: t('commandPalette.browse_files_current_volume')
    },
    {
      label: t('commandPalette.go_to_settings'),
      action: () => router.push('/settings'),
      icon: '⚙️',
      detail: t('commandPalette.configure_application_settings')
    },
    {
      label: t('commandPalette.sync_current_volume'),
      action: async () => {
        if (reposStore.currentRepo) {
           try {
             await reposStore.syncRepo(reposStore.currentRepo)
             systemStore.addNotification(t('commandPalette.synced', { repo: reposStore.currentRepo }), 'success')
           } catch (e) {
             systemStore.addNotification(t('commandPalette.sync_failed'), 'error')
           }
        } else {
            systemStore.addNotification(t('commandPalette.no_volume_selected'), 'warning')
        }
      },
      icon: '🔄',
      detail: t('commandPalette.pull_latest_changes_push_local')
    },
    {
      label: t('commandPalette.create_new_volume'),
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
      detail: t('commandPalette.initialize_new_data_volume')
    }
  ]

  // Add repo switching commands
  if (reposStore.repos && reposStore.repos.length > 0) {
    reposStore.repos.forEach(repo => {
      if (repo !== reposStore.currentRepo) {
        list.push({
          label: t('commandPalette.switch_to', { repo }),
          action: () => {
             // We need to call selectProject which is in App.vue or move that logic to store.
             // Best practice: Move selectProject logic to store or expose it.
             // reposStore has setCurrentRepo, but App.vue does axios call + filesStore fetch.
             // Let's replicate the logic here or delegate.
             selectProject(repo)
          },
          icon: '🔀',
          detail: t('commandPalette.switch_context_to', { repo })
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
        systemStore.addNotification(t('commandPalette.switched_to', { repo }), 'success')
    } catch (e) {
        console.error(e)
        systemStore.addNotification(t('commandPalette.failed_to_switch', { repo }), 'error')
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
