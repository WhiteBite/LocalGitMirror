<template>
  <div class="file-tree">
    <div v-if="filteredTree.length === 0" class="empty-state">
      <p class="text-gray-500 text-center py-8">
        {{ searchQuery ? 'Файлы не найдены' : 'Нет файлов' }}
      </p>
    </div>
    <div v-else class="tree-content">
      <TreeNode
        v-for="node in filteredTree"
        :key="node.path"
        :node="node"
        :level="0"
        :selected-file="selectedFile"
        :search-query="searchQuery"
        @file-select="handleFileSelect"
        @folder-toggle="handleFolderToggle"
        @context-menu="handleContextMenu"
      />
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import TreeNode from './TreeNode.vue'

const props = defineProps({
  files: {
    type: Array,
    default: () => []
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

// Состояние развернутых папок
const expandedFolders = ref(new Set())

// Построение дерева из плоского списка файлов
const buildTree = (files) => {
  const tree = []
  const pathMap = new Map()

  // Сортировка файлов по пути
  const sortedFiles = [...files].sort((a, b) => a.path.localeCompare(b.path))

  sortedFiles.forEach(file => {
    const parts = file.path.split('/')
    let currentLevel = tree
    let currentPath = ''

    parts.forEach((part, index) => {
      currentPath = currentPath ? `${currentPath}/${part}` : part
      const isFile = index === parts.length - 1

      if (isFile) {
        // Добавляем файл
        currentLevel.push({
          type: 'file',
          name: part,
          path: file.path,
          size: file.size,
          modified: file.modified,
          icon: getFileIcon(part)
        })
      } else {
        // Проверяем, существует ли папка
        let folder = pathMap.get(currentPath)
        
        if (!folder) {
          folder = {
            type: 'folder',
            name: part,
            path: currentPath,
            children: [],
            isExpanded: expandedFolders.value.has(currentPath),
            fileCount: 0
          }
          currentLevel.push(folder)
          pathMap.set(currentPath, folder)
        }

        currentLevel = folder.children
      }
    })
  })

  // Подсчет файлов в папках и сортировка
  const processNode = (node) => {
    if (node.type === 'folder') {
      node.children.forEach(processNode)
      
      // Подсчет файлов
      node.fileCount = node.children.reduce((count, child) => {
        if (child.type === 'file') return count + 1
        return count + child.fileCount
      }, 0)

      // Сортировка: папки сверху, затем файлы по алфавиту
      node.children.sort((a, b) => {
        if (a.type !== b.type) {
          return a.type === 'folder' ? -1 : 1
        }
        return a.name.localeCompare(b.name)
      })
    }
  }

  tree.forEach(processNode)
  
  // Сортировка корневого уровня
  tree.sort((a, b) => {
    if (a.type !== b.type) {
      return a.type === 'folder' ? -1 : 1
    }
    return a.name.localeCompare(b.name)
  })

  return tree
}

// Определение иконки для файла
const getFileIcon = (filename) => {
  const ext = filename.split('.').pop().toLowerCase()
  const iconMap = {
    'md': '📝',
    'py': '🐍',
    'js': '📜',
    'ts': '📘',
    'json': '📋',
    'vue': '💚',
    'html': '🌐',
    'css': '🎨',
    'scss': '🎨',
    'yaml': '⚙️',
    'yml': '⚙️',
    'txt': '📄',
    'xml': '📰',
    'svg': '🖼️',
    'png': '🖼️',
    'jpg': '🖼️',
    'jpeg': '🖼️',
    'gif': '🖼️',
    'pdf': '📕',
    'zip': '📦',
    'tar': '📦',
    'gz': '📦'
  }
  return iconMap[ext] || '📄'
}

// Фильтрация дерева по поисковому запросу
const filterTree = (nodes, query) => {
  if (!query) return nodes

  const lowerQuery = query.toLowerCase()
  
  const filterNode = (node) => {
    if (node.type === 'file') {
      return node.name.toLowerCase().includes(lowerQuery) ||
             node.path.toLowerCase().includes(lowerQuery)
    }

    // Для папок: фильтруем детей
    const filteredChildren = node.children
      .map(child => filterNode(child))
      .filter(child => child !== null)

    if (filteredChildren.length > 0) {
      return {
        ...node,
        children: filteredChildren,
        isExpanded: true // Автоматически разворачиваем папки при поиске
      }
    }

    // Проверяем имя папки
    if (node.name.toLowerCase().includes(lowerQuery)) {
      return node
    }

    return null
  }

  return nodes
    .map(node => filterNode(node))
    .filter(node => node !== null)
}

// Построение и фильтрация дерева
const tree = computed(() => buildTree(props.files))
const filteredTree = computed(() => filterTree(tree.value, props.searchQuery))

// Обработчики событий
const handleFileSelect = (path) => {
  emit('file-select', path)
}

const handleFolderToggle = (path) => {
  if (expandedFolders.value.has(path)) {
    expandedFolders.value.delete(path)
  } else {
    expandedFolders.value.add(path)
  }
  emit('folder-toggle', path)
}

const handleContextMenu = (data) => {
  emit('context-menu', data)
}
</script>

<style scoped>
.file-tree {
  @apply bg-gray-800 text-gray-300 h-full overflow-auto;
}

.tree-content {
  @apply py-2;
}

.empty-state {
  @apply flex items-center justify-center h-full;
}
</style>
