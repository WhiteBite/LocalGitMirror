<template>
  <div class="p-8">
    <h2 class="text-2xl font-bold text-white mb-4">SharedContextMenu Examples</h2>
    
    <!-- Example 1: File context menu -->
    <div 
      class="bg-gray-800 p-4 rounded-lg mb-4 cursor-pointer"
      @contextmenu.prevent="showFileMenu"
    >
      <p class="text-gray-300">Right-click here for file menu</p>
      <p class="text-sm text-gray-500">File: example.txt</p>
    </div>

    <!-- Example 2: Folder context menu -->
    <div 
      class="bg-gray-800 p-4 rounded-lg mb-4 cursor-pointer"
      @contextmenu.prevent="showFolderMenu"
    >
      <p class="text-gray-300">Right-click here for folder menu</p>
      <p class="text-sm text-gray-500">Folder: src/components</p>
    </div>

    <!-- Example 3: Multiple selection menu -->
    <div 
      class="bg-gray-800 p-4 rounded-lg mb-4 cursor-pointer"
      @contextmenu.prevent="showMultipleMenu"
    >
      <p class="text-gray-300">Right-click here for multiple selection menu</p>
      <p class="text-sm text-gray-500">Selected: 5 files</p>
    </div>

    <!-- Context Menu Component -->
    <SharedContextMenu
      :visible="contextMenu.visible"
      :x="contextMenu.x"
      :y="contextMenu.y"
      :item="contextMenu.item"
      :selected-count="contextMenu.selectedCount"
      @close="closeContextMenu"
      @action="handleContextAction"
    />

    <!-- Action log -->
    <div v-if="lastAction" class="bg-green-900/20 border border-green-700 rounded-lg p-4 mt-4">
      <p class="text-green-400">Last action: {{ lastAction.type }}</p>
      <pre class="text-xs text-gray-400 mt-2">{{ JSON.stringify(lastAction, null, 2) }}</pre>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import SharedContextMenu from './SharedContextMenu.vue'

const contextMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  item: null,
  selectedCount: 0
})

const lastAction = ref(null)

const showFileMenu = (event) => {
  contextMenu.value = {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    item: {
      type: 'file',
      name: 'example.txt',
      path: '/src/example.txt'
    },
    selectedCount: 0
  }
}

const showFolderMenu = (event) => {
  contextMenu.value = {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    item: {
      type: 'folder',
      name: 'components',
      path: '/src/components',
      isDirectory: true
    },
    selectedCount: 0
  }
}

const showMultipleMenu = (event) => {
  contextMenu.value = {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    item: null,
    selectedCount: 5
  }
}

const closeContextMenu = () => {
  contextMenu.value.visible = false
}

const handleContextAction = (action) => {
  lastAction.value = action
  console.log('Context menu action:', action)
}
</script>
