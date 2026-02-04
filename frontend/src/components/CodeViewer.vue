<template>
  <div class="code-viewer">
    <!-- Toolbar -->
    <div class="toolbar">
      <div class="flex items-center space-x-2">
        <svg class="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4" />
        </svg>
        <span class="text-sm text-gray-400">{{ detectedLanguage }}</span>
      </div>
      <button 
        @click="copyCode" 
        class="copy-button"
        :class="{ 'copied': copied }"
      >
        <svg v-if="!copied" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
        </svg>
        <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
        </svg>
        <span class="ml-2">{{ copied ? 'Copied!' : 'Copy' }}</span>
      </button>
    </div>

    <!-- Code content -->
    <div class="code-container">
      <div class="line-numbers">
        <div v-for="n in lineCount" :key="n" class="line-number">
          {{ n }}
        </div>
      </div>
      <pre class="code-content"><code ref="codeRef" :class="`language-${detectedLanguage}`">{{ content }}</code></pre>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'

const props = defineProps({
  content: {
    type: String,
    required: true
  },
  language: {
    type: String,
    default: ''
  }
})

const codeRef = ref(null)
const copied = ref(false)

// Detect language
const detectedLanguage = computed(() => {
  if (props.language) {
    return props.language
  }
  
  // Auto-detect language
  if (props.content) {
    const result = hljs.highlightAuto(props.content)
    return result.language || 'plaintext'
  }
  
  return 'plaintext'
})

// Count lines
const lineCount = computed(() => {
  if (!props.content) return 1
  return props.content.split('\n').length
})

// Highlight code
function highlightCode() {
  if (!codeRef.value) return
  
  try {
    if (props.language && hljs.getLanguage(props.language)) {
      const result = hljs.highlight(props.content, { language: props.language })
      codeRef.value.innerHTML = result.value
    } else {
      const result = hljs.highlightAuto(props.content)
      codeRef.value.innerHTML = result.value
    }
  } catch (err) {
    console.error('Highlight error:', err)
    codeRef.value.textContent = props.content
  }
}

// Copy code to clipboard
async function copyCode() {
  try {
    await navigator.clipboard.writeText(props.content)
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 2000)
  } catch (err) {
    console.error('Copy error:', err)
  }
}

// Initialize highlighting
onMounted(() => {
  highlightCode()
})

// Re-highlight on content change
watch(() => props.content, () => {
  highlightCode()
})

watch(() => props.language, () => {
  highlightCode()
})
</script>

<style scoped>
.code-viewer {
  @apply bg-gray-900 rounded-lg overflow-hidden border border-gray-700;
}

.toolbar {
  @apply flex items-center justify-between px-4 py-2 bg-gray-800 border-b border-gray-700;
}

.copy-button {
  @apply flex items-center px-3 py-1.5 text-sm text-gray-300 hover:text-white bg-gray-700 hover:bg-gray-600 rounded transition-colors;
}

.copy-button.copied {
  @apply text-green-400 bg-green-900 hover:bg-green-900;
}

.code-container {
  @apply flex overflow-x-auto;
}

.line-numbers {
  @apply flex-shrink-0 bg-gray-800 text-gray-500 text-right select-none border-r border-gray-700;
  min-width: 3rem;
  padding: 1rem 0.5rem;
  font-family: 'Courier New', monospace;
  font-size: 0.875rem;
  line-height: 1.5rem;
}

.line-number {
  @apply px-2;
  height: 1.5rem;
}

.code-content {
  @apply flex-1 p-4 m-0 bg-transparent overflow-x-auto;
  font-family: 'Courier New', monospace;
  font-size: 0.875rem;
  line-height: 1.5rem;
}

.code-content code {
  @apply bg-transparent text-gray-200;
  display: block;
  white-space: pre;
}

/* Scrollbar styling */
.code-container::-webkit-scrollbar {
  height: 8px;
}

.code-container::-webkit-scrollbar-track {
  @apply bg-gray-800;
}

.code-container::-webkit-scrollbar-thumb {
  @apply bg-gray-600 rounded;
}

.code-container::-webkit-scrollbar-thumb:hover {
  @apply bg-gray-500;
}
</style>
