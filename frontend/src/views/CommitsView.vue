<template>
  <div class="commits-view">
    <div class="view-header">
      <h2>{{ t('commits.title') }}: {{ reposStore.currentRepo }}</h2>
      <button class="icon-btn" :disabled="loading" @click="fetchCommits">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15" /></svg>
      </button>
    </div>

    <div class="view-content">
      <!-- Commit List -->
      <div class="commit-list-pane">
        <div v-if="loading" class="loading-state">{{ t('commits.loading_commits') }}</div>
        <div v-else-if="commits.length === 0" class="empty-state">{{ t('commits.no_commits_found') }}</div>
        <div 
          v-for="commit in commits" 
          :key="commit.hash"
          class="commit-item"
          :class="{ active: selectedCommit?.hash === commit.hash }"
          @click="selectCommit(commit)"
        >
          <div class="commit-meta">
            <span class="commit-hash">{{ commit.hash }}</span>
            <span class="commit-date">{{ formatDate(commit.date) }}</span>
          </div>
          <div class="commit-msg">{{ commit.message }}</div>
          <div class="commit-author">{{ commit.author }}</div>
        </div>
      </div>

      <!-- Commit Details -->
      <div class="commit-details-pane">
        <div v-if="detailsLoading" class="loading-state">{{ t('commits.loading_details') }}</div>
        <div v-else-if="selectedCommitDetails" class="details-container">
          <div class="details-header">
            <h3>{{ selectedCommitDetails.hash }}</h3>
            <p class="full-msg">{{ selectedCommitDetails.message }}</p>
            <div class="meta-row">
              <span><strong>{{ t('commits.author') }}:</strong> {{ selectedCommitDetails.author }}</span>
              <span><strong>{{ t('commits.date') }}:</strong> {{ formatDate(selectedCommitDetails.date) }}</span>
            </div>
          </div>
          
          <div class="changed-files">
            <h4>{{ t('commits.changed_files') }} ({{ selectedCommitDetails.files.length }})</h4>
            <div v-for="file in selectedCommitDetails.files" :key="file.path" class="file-change-item">
              <span class="status-badge" :class="file.status">{{ file.status }}</span>
              <span class="file-path">{{ file.path }}</span>
            </div>
          </div>
        </div>
        <div v-else class="empty-details">
          {{ t('commits.select_commit') }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useReposStore } from '@/stores/repos'
import axios from 'axios'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const reposStore = useReposStore()
const commits = ref([])
const loading = ref(false)
const selectedCommit = ref(null)
const selectedCommitDetails = ref(null)
const detailsLoading = ref(false)

onMounted(fetchCommits)

watch(() => reposStore.currentRepo, fetchCommits)

async function fetchCommits() {
  loading.value = true
  try {
    const response = await axios.get('/api/commits', {
      params: { repo: reposStore.currentRepo }
    })
    commits.value = response.data.commits || []
  } catch (err) {
    console.error('Failed to fetch commits', err)
  } finally {
    loading.value = false
  }
}

async function selectCommit(commit) {
  selectedCommit.value = commit
  detailsLoading.value = true
  try {
    const response = await axios.get(`/api/git/commit/${commit.hash}`)
    if (response.data.success) {
      selectedCommitDetails.value = response.data
    }
  } catch (err) {
    console.error('Failed to fetch commit details', err)
  } finally {
    detailsLoading.value = false
  }
}

function formatDate(dateStr) {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  return date.toLocaleString()
}
</script>

<style scoped>
.commits-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg-primary);
}

.view-header {
  padding: 15px 20px;
  border-bottom: 1px solid var(--border-color);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.view-header h2 { margin: 0; font-size: 18px; color: var(--text-bright); }

.view-content {
  flex: 1;
  display: flex;
  overflow: hidden;
}

.commit-list-pane {
  width: 400px;
  border-right: 1px solid var(--border-color);
  overflow-y: auto;
  padding: 10px;
}

.commit-item {
  padding: 12px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 8px;
  border: 1px solid transparent;
  transition: all 0.2s;
}

.commit-item:hover { background: rgba(255, 255, 255, 0.05); }
.commit-item.active { background: rgba(55, 148, 255, 0.1); border-color: var(--accent); }

.commit-meta { display: flex; justify-content: space-between; font-size: 11px; margin-bottom: 4px; color: #858585; }
.commit-hash { font-family: monospace; color: var(--accent); }
.commit-msg { font-size: 13px; font-weight: 500; color: var(--text-bright); margin-bottom: 4px; }
.commit-author { font-size: 11px; color: #666; }

.commit-details-pane {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.details-header { margin-bottom: 25px; border-bottom: 1px solid var(--border-color); padding-bottom: 15px; }
.details-header h3 { font-family: monospace; color: var(--accent); margin: 0 0 10px; }
.full-msg { font-size: 16px; color: var(--text-bright); margin-bottom: 15px; }
.meta-row { display: flex; gap: 20px; font-size: 13px; color: #858585; }

.changed-files h4 { font-size: 14px; color: #858585; margin: 0 0 15px; }

.file-change-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  font-family: monospace;
  font-size: 13px;
}

.status-badge {
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 10px;
  font-weight: bold;
}

.status-badge.M { background: #cca700; color: black; }
.status-badge.A { background: #89d185; color: black; }
.status-badge.D { background: #f48771; color: black; }

.loading-state, .empty-state, .empty-details {
  padding: 40px;
  text-align: center;
  color: #555;
  font-style: italic;
}
</style>
