<template>
  <Teleport to="body">
    <Transition name="context-menu">
      <div
        v-if="visible"
        ref="menuRef"
        class="fixed z-50 bg-gray-800 border border-gray-700 rounded-lg shadow-xl py-1 min-w-[220px]"
        :style="menuStyle"
        @click.stop
      >
        <!-- File menu options -->
        <template v-if="menuType === 'file'">
          <div class="menu-item" @click="handleAction('view')">
            <span class="menu-icon">👁️</span>
            <span>{{ t('sharedContextMenu.view') }}</span>
          </div>
          <div class="menu-item" @click="handleAction('rename')">
            <span class="menu-icon">✏️</span>
            <span>{{ t('sharedContextMenu.rename') }}</span>
          </div>
          <div class="menu-item" @click="handleAction('tags')">
            <span class="menu-icon">🏷️</span>
            <span>{{ t('sharedContextMenu.tags') }}</span>
          </div>
          <div class="menu-item" @click="handleAction('history')">
            <span class="menu-icon">📜</span>
            <span>{{ t('sharedContextMenu.history') }}</span>
          </div>
          <div class="menu-divider"></div>
          <div class="menu-item" @click="handleAction('download')">
            <span class="menu-icon">💾</span>
            <span>{{ t('sharedContextMenu.download') }}</span>
          </div>
          <div class="menu-item" @click="handleAction('copy-path')">
            <span class="menu-icon">📋</span>
            <span>{{ t('sharedContextMenu.copy_path') }}</span>
          </div>
          <div class="menu-divider"></div>
          <div class="menu-item text-red-400 hover:bg-red-900/20" @click="handleAction('delete')">
            <span class="menu-icon">🗑️</span>
            <span>{{ t('sharedContextMenu.delete') }}</span>
          </div>
        </template>

        <!-- Folder menu options -->
        <template v-else-if="menuType === 'folder'">
          <div class="menu-item" @click="handleAction('open')">
            <span class="menu-icon">📂</span>
            <span>{{ t('sharedContextMenu.open') }}</span>
          </div>
          <div class="menu-item" @click="handleAction('rename')">
            <span class="menu-icon">✏️</span>
            <span>{{ t('sharedContextMenu.rename') }}</span>
          </div>
          <div class="menu-item" @click="handleAction('stats')">
            <span class="menu-icon">📊</span>
            <span>{{ t('sharedContextMenu.stats') }}</span>
          </div>
          <div class="menu-divider"></div>
          <div class="menu-item text-red-400 hover:bg-red-900/20" @click="handleAction('delete')">
            <span class="menu-icon">🗑️</span>
            <span>{{ t('sharedContextMenu.delete') }}</span>
          </div>
        </template>

        <!-- Multiple selection menu options -->
        <template v-else-if="menuType === 'multiple'">
          <div class="menu-item text-red-400 hover:bg-red-900/20" @click="handleAction('delete-multiple')">
            <span class="menu-icon">🗑️</span>
            <span>{{ t('sharedContextMenu.delete_multiple', { count: selectedCount }) }}</span>
          </div>
          <div class="menu-divider"></div>
          <div class="menu-item" @click="handleAction('download-zip')">
            <span class="menu-icon">📦</span>
            <span>{{ t('sharedContextMenu.download_zip') }}</span>
          </div>
        </template>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  x: {
    type: Number,
    default: 0
  },
  y: {
    type: Number,
    default: 0
  },
  item: {
    type: Object,
    default: null
  },
  selectedCount: {
    type: Number,
    default: 0
  }
})

const emit = defineEmits(['close', 'action'])

const menuRef = ref(null)

// Determine menu type based on props
const menuType = computed(() => {
  if (props.selectedCount > 1) {
    return 'multiple'
  }
  if (props.item?.type === 'folder' || props.item?.isDirectory) {
    return 'folder'
  }
  return 'file'
})

// Calculate menu position with viewport boundary checks
const menuStyle = computed(() => {
  const menuWidth = 220
  const menuHeight = 300 // Approximate max height
  const padding = 10

  let x = props.x
  let y = props.y

  // Check right boundary
  if (x + menuWidth > window.innerWidth - padding) {
    x = window.innerWidth - menuWidth - padding
  }

  // Check bottom boundary
  if (y + menuHeight > window.innerHeight - padding) {
    y = window.innerHeight - menuHeight - padding
  }

  // Ensure minimum position
  x = Math.max(padding, x)
  y = Math.max(padding, y)

  return {
    top: `${y}px`,
    left: `${x}px`
  }
})

// Handle menu action
const handleAction = (type) => {
  emit('action', {
    type,
    item: props.item
  })
  emit('close')
}

// Close on click outside
const handleClickOutside = (e) => {
  if (menuRef.value && !menuRef.value.contains(e.target)) {
    emit('close')
  }
}

// Close on Escape
const handleEscape = (e) => {
  if (e.key === 'Escape') {
    emit('close')
  }
}

// Setup/cleanup event listeners
watch(() => props.visible, (visible) => {
  if (visible) {
    // Delay to avoid immediate close from the same click that opened the menu
    setTimeout(() => {
      document.addEventListener('click', handleClickOutside)
      document.addEventListener('keydown', handleEscape)
    }, 0)
  } else {
    document.removeEventListener('click', handleClickOutside)
    document.removeEventListener('keydown', handleEscape)
  }
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
  document.removeEventListener('keydown', handleEscape)
})
</script>

<style scoped>
.menu-item {
  @apply flex items-center gap-3 px-3 py-2 text-sm text-gray-300 hover:bg-gray-700 cursor-pointer transition-colors;
}

.menu-icon {
  @apply flex-shrink-0 text-base;
}

.menu-item span:last-child {
  @apply flex-1;
}

.menu-divider {
  @apply my-1 border-t border-gray-700;
}

/* Transition animations */
.context-menu-enter-active,
.context-menu-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.context-menu-enter-from {
  opacity: 0;
  transform: scale(0.95);
}

.context-menu-leave-to {
  opacity: 0;
  transform: scale(0.95);
}
</style>
