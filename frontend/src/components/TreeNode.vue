<template>
  <div class="tree-node">
    <!-- Папка -->
    <div
      v-if="node.type === 'folder'"
      class="folder-node"
      :class="{ 'is-expanded': node.isExpanded }"
      :style="{ paddingLeft: `${level * 16}px` }"
      @click="toggleFolder"
    >
      <div class="folder-content">
        <span class="folder-icon">{{ node.isExpanded ? '📂' : '📁' }}</span>
        <span class="folder-name" :title="node.path">{{ node.name }}</span>
        <span class="file-count">({{ node.fileCount }})</span>
      </div>
    </div>

    <!-- Дети папки (с анимацией) -->
    <transition name="expand">
      <div v-if="node.type === 'folder' && node.isExpanded" class="folder-children">
        <TreeNode
          v-for="child in node.children"
          :key="child.path"
          :node="child"
          :level="level + 1"
          :selected-file="selectedFile"
          :search-query="searchQuery"
          @file-select="$emit('file-select', $event)"
          @folder-toggle="$emit('folder-toggle', $event)"
          @context-menu="$emit('context-menu', $event)"
        />
      </div>
    </transition>

    <!-- Файл -->
    <div
      v-if="node.type === 'file'"
      class="file-node"
      :class="{ 'is-selected': isSelected }"
      :style="{ paddingLeft: `${level * 16}px` }"
      @click="selectFile"
      @contextmenu.prevent="handleContextMenu"
    >
      <div class="file-content">
        <span class="file-icon">{{ node.icon }}</span>
        <span class="file-name" :title="node.path">{{ highlightedName }}</span>
        <span class="file-size">{{ formatSize(node.size) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  node: {
    type: Object,
    required: true
  },
  level: {
    type: Number,
    default: 0
  },
  selectedFile: {
    type: String,
    default: ''
  },
  searchQuery: {
    type: String,
    default: ''
  }
})

const emit = defineEmits(['file-select', 'folder-toggle', 'context-menu'])

// Проверка, выбран ли файл
const isSelected = computed(() => {
  return props.node.type === 'file' && props.node.path === props.selectedFile
})

// Подсветка совпадений в имени файла
const highlightedName = computed(() => {
  if (!props.searchQuery || props.node.type !== 'file') {
    return props.node.name
  }

  const query = props.searchQuery.toLowerCase()
  const name = props.node.name
  const lowerName = name.toLowerCase()
  const index = lowerName.indexOf(query)

  if (index === -1) return name

  // Возвращаем имя с подсветкой (используем HTML в v-html если нужно)
  return name
})

// Форматирование размера файла
const formatSize = (bytes) => {
  if (!bytes) return ''
  
  const units = ['B', 'KB', 'MB', 'GB']
  let size = bytes
  let unitIndex = 0

  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024
    unitIndex++
  }

  return `${size.toFixed(unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`
}

// Обработчики событий
const toggleFolder = () => {
  emit('folder-toggle', props.node.path)
}

const selectFile = () => {
  emit('file-select', props.node.path)
}

const handleContextMenu = (event) => {
  emit('context-menu', { path: props.node.path, event })
}
</script>

<style scoped>
.tree-node {
  @apply select-none;
}

/* Папка */
.folder-node {
  @apply flex items-center py-1.5 px-2 cursor-pointer transition-all duration-200;
  @apply hover:bg-gray-700 rounded;
}

.folder-content {
  @apply flex items-center gap-2 w-full;
}

.folder-icon {
  @apply text-lg flex-shrink-0;
}

.folder-name {
  @apply flex-1 truncate font-medium;
}

.file-count {
  @apply text-xs text-gray-500 flex-shrink-0;
}

/* Файл */
.file-node {
  @apply flex items-center py-1.5 px-2 cursor-pointer transition-all duration-200;
  @apply hover:bg-gray-700 rounded;
}

.file-node.is-selected {
  @apply bg-blue-900 border-l-4 border-blue-500;
}

.file-content {
  @apply flex items-center gap-2 w-full;
}

.file-icon {
  @apply text-base flex-shrink-0;
}

.file-name {
  @apply flex-1 truncate;
}

.file-size {
  @apply text-xs text-gray-500 flex-shrink-0;
}

/* Анимация разворачивания */
.expand-enter-active,
.expand-leave-active {
  @apply transition-all duration-200 overflow-hidden;
}

.expand-enter-from,
.expand-leave-to {
  @apply opacity-0 max-h-0;
}

.expand-enter-to,
.expand-leave-from {
  @apply opacity-100 max-h-screen;
}

.folder-children {
  @apply overflow-hidden;
}

/* Отступы для вложенности */
.tree-node {
  @apply relative;
}

/* Hover эффекты */
.folder-node:hover .folder-name,
.file-node:hover .file-name {
  @apply text-white;
}

.folder-node:active,
.file-node:active {
  @apply bg-gray-600;
}
</style>
