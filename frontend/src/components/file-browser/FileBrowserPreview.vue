<template>
  <section class="file-browser-preview">
    <div v-if="filePath" class="preview-container">
      <header class="preview-header">
        <span class="file-path" :title="filePath">{{ filePath }}</span>
        <div class="actions">
          <button
            class="btn btn-secondary btn-mini"
            :class="{ active: showDiff }"
            :title="showChangesTitle"
            @click="emit('toggle-diff')"
          >
            {{ showDiff ? showFileLabel : showDiffLabel }}
          </button>
          <button class="btn btn-secondary btn-mini" @click="emit('open-in-explorer')">
            {{ openInExplorerLabel }}
          </button>
        </div>
      </header>

      <div class="preview-body">
        <div v-if="loadingDiff" class="loading-overlay">{{ loadingChangesLabel }}</div>
        <DiffViewer v-else-if="showDiff" :diff="diffContent" />
        <FileViewer v-else :file-path="filePath" />
      </div>
    </div>

    <div v-else class="empty-preview">
      <div class="msg">
        <svg viewBox="0 0 24 24"><path d="M13 9h5.5L13 3.5V9M6 2h8l6 6v12a2 2 0 01-2 2H6a2 2 0 01-2-2V4c0-1.1.9-2 2-2m0 18h12V10h-7V3H6v17z" /></svg>
        <p>{{ selectFileLabel }}</p>
      </div>
    </div>
  </section>
</template>

<script setup>
import FileViewer from '@/components/FileViewer.vue'
import DiffViewer from '@/components/DiffViewer.vue'

defineProps({
  filePath: {
    type: String,
    default: ''
  },
  showDiff: {
    type: Boolean,
    default: false
  },
  diffContent: {
    type: String,
    default: ''
  },
  loadingDiff: {
    type: Boolean,
    default: false
  },
  showChangesTitle: {
    type: String,
    default: ''
  },
  showFileLabel: {
    type: String,
    default: ''
  },
  showDiffLabel: {
    type: String,
    default: ''
  },
  openInExplorerLabel: {
    type: String,
    default: ''
  },
  loadingChangesLabel: {
    type: String,
    default: ''
  },
  selectFileLabel: {
    type: String,
    default: ''
  }
})

const emit = defineEmits(['toggle-diff', 'open-in-explorer'])
</script>

<style scoped>
.file-browser-preview {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
}

.preview-container {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.preview-header {
  min-height: 44px;
  padding: 8px 12px;
  background: var(--bg-sidebar);
  border-bottom: 1px solid var(--border-color);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}

.actions {
  display: flex;
  gap: 8px;
}

.btn-mini {
  font-size: 11px;
  padding: 4px 10px;
  line-height: 1.2;
  border: 1px solid var(--border-color);
  border-radius: 6px;
}

.btn-mini.active {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}

.loading-overlay {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-secondary);
}

.file-path {
  min-width: 0;
  max-width: calc(100% - 160px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--text-secondary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
}

.preview-body {
  flex: 1;
  overflow: auto;
}

.empty-preview {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
}

.msg {
  text-align: center;
}

.empty-preview svg {
  width: 64px;
  height: 64px;
  fill: currentColor;
  margin: 0 auto 12px;
  opacity: 0.7;
}

.msg p {
  font-size: 13px;
}
</style>
