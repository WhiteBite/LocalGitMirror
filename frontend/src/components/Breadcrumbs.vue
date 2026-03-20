<template>
  <nav class="breadcrumbs" :class="{ compact }">
    <div class="flex items-center space-x-1">
      <button
        v-for="(crumb, index) in normalizedCrumbs"
        :key="crumb.path"
        class="breadcrumb-item"
        :class="{ 'active': index === normalizedCrumbs.length - 1 }"
        @click="emit('navigate', crumb.path)"
      >
        <svg v-if="index === 0" class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" />
        </svg>
        <span>{{ crumb.name }}</span>
        <svg v-if="index < crumbs.length - 1" class="w-4 h-4 text-gray-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
        </svg>
      </button>
    </div>
  </nav>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  crumbs: {
    type: Array,
    default: null
  },
  rootLabel: {
    type: String,
    default: 'Root'
  },
  compact: {
    type: Boolean,
    default: false
  },
  path: {
    type: String,
    default: '/'
  }
})

const emit = defineEmits(['navigate'])

const normalizedCrumbs = computed(() => {
  if (props.crumbs?.length) {
    return props.crumbs.map((crumb, index) => {
      if (index === 0) {
        return {
          ...crumb,
          name: props.rootLabel || crumb.name || 'Root'
        }
      }
      return crumb
    })
  }

  if (props.path === '/') return [{ name: props.rootLabel, path: '/' }]

  const parts = props.path.split('/').filter(Boolean)
  const result = [{ name: props.rootLabel, path: '/' }]

  let currentPath = ''
  parts.forEach(part => {
    currentPath += `/${part}`
    result.push({ name: part, path: currentPath })
  })

  return result
})
</script>

<style scoped>
.breadcrumbs {
  @apply px-4 py-2 bg-gray-800 border-b border-gray-700;
}

.breadcrumbs.compact {
  @apply p-0 bg-transparent border-b-0;
}

.breadcrumb-item {
  @apply flex items-center gap-1 px-2 py-1 text-sm text-gray-400 hover:text-gray-200 hover:bg-gray-700 rounded transition-colors;
}

.breadcrumbs.compact .breadcrumb-item {
  @apply py-1 px-1.5 text-xs;
}

.breadcrumb-item.active {
  @apply text-gray-200 font-medium;
}

.breadcrumb-item svg:last-child {
  @apply ml-1;
}
</style>
