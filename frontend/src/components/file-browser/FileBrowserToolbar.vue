<template>
  <div class="file-browser-toolbar">
    <div class="toolbar-left">
      <div class="mode-tabs">
        <button
          class="mode-tab"
          :class="{ active: mode === 'git' }"
          @click="emit('mode-change', 'git')"
        >
          {{ gitModeLabel }}
        </button>
        <button
          class="mode-tab"
          :class="{ active: mode === 'shared' }"
          @click="emit('mode-change', 'shared')"
        >
          {{ sharedModeLabel }}
        </button>
      </div>

      <div class="breadcrumbs-wrap">
        <Breadcrumbs
          compact
          :crumbs="breadcrumbs"
          :root-label="rootLabel"
          @navigate="emit('navigate', $event)"
        />
      </div>
    </div>

    <div class="toolbar-actions">
      <input
        v-if="mode === 'git'"
        class="input search-input"
        type="text"
        :value="searchQuery"
        :placeholder="searchPlaceholder"
        @input="onSearchInput"
      />

      <button class="icon-btn" :title="refreshTitle" @click="emit('refresh')">
        <svg viewBox="0 0 24 24"><path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" /></svg>
      </button>
    </div>
  </div>
</template>

<script setup>
import Breadcrumbs from '@/components/Breadcrumbs.vue'

const props = defineProps({
  mode: {
    type: String,
    required: true
  },
  breadcrumbs: {
    type: Array,
    default: () => []
  },
  rootLabel: {
    type: String,
    default: 'Root'
  },
  searchQuery: {
    type: String,
    default: ''
  },
  searchPlaceholder: {
    type: String,
    default: ''
  },
  refreshTitle: {
    type: String,
    default: ''
  },
  gitModeLabel: {
    type: String,
    default: 'Files'
  },
  sharedModeLabel: {
    type: String,
    default: 'Shared'
  }
})

const emit = defineEmits(['mode-change', 'navigate', 'search-change', 'refresh'])

function onSearchInput(event) {
  emit('search-change', event?.target?.value ?? '')
}
</script>

<style scoped>
.file-browser-toolbar {
  height: 44px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 0 12px;
  background: var(--bg-sidebar);
  border-bottom: 1px solid var(--border-color);
}

.toolbar-left {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: center;
  gap: 10px;
}

.mode-tabs {
  flex-shrink: 0;
  display: flex;
  gap: 4px;
}

.mode-tab {
  background: transparent;
  border: 1px solid transparent;
  color: var(--text-secondary);
  font-size: 11px;
  line-height: 1;
  padding: 7px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.mode-tab:hover {
  color: var(--text-bright);
  border-color: var(--border-color);
  background: #3a3d41;
}

.mode-tab.active {
  color: #fff;
  background: var(--accent);
  border-color: var(--accent);
}

.breadcrumbs-wrap {
  min-width: 0;
  flex: 1;
  overflow: hidden;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.search-input {
  width: 220px;
  font-size: 12px;
  line-height: 1.2;
  padding-top: 5px;
  padding-bottom: 5px;
}

.icon-btn {
  background: transparent;
  border: 1px solid transparent;
  color: var(--text-secondary);
  width: 28px;
  height: 28px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
}

.icon-btn svg {
  width: 16px;
  height: 16px;
  fill: currentColor;
}

.icon-btn:hover {
  color: var(--text-bright);
  border-color: var(--border-color);
  background: #3a3d41;
}

@media (max-width: 960px) {
  .search-input {
    width: 160px;
  }
}

@media (max-width: 760px) {
  .breadcrumbs-wrap {
    display: none;
  }

  .search-input {
    width: 130px;
  }
}
</style>
