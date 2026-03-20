<template>
  <div class="code-viewer">
    <!-- Toolbar -->
    <div class="toolbar">
      <div class="flex items-center space-x-2">
        <svg class="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4" />
        </svg>
        <span class="text-sm text-gray-400">{{ displayLanguage }}</span>
      </div>
      <button 
        class="copy-button" 
        :class="{ 'copied': copied }"
        @click="copyCode"
      >
        <svg v-if="!copied" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
        </svg>
        <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
        </svg>
        <span class="ml-2">{{ copied ? t('codeViewer.copied') : t('codeViewer.copy') }}</span>
      </button>
    </div>

    <!-- VSCode-like code viewer (CodeMirror, read-only) -->
    <div class="editor-container">
      <codemirror
        v-model="code"
        :style="{ height: '100%' }"
        :autofocus="false"
        :indent-with-tab="true"
        :tab-size="4"
        :extensions="extensions"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Codemirror } from 'vue-codemirror'
import { EditorState } from '@codemirror/state'
import { EditorView, lineNumbers, highlightActiveLine, highlightActiveLineGutter } from '@codemirror/view'
import { oneDark } from '@codemirror/theme-one-dark'
import { javascript } from '@codemirror/lang-javascript'
import { python } from '@codemirror/lang-python'

const { t } = useI18n()

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

const copied = ref(false)
const code = ref(props.content || '')

watch(() => props.content, (newVal) => {
  code.value = newVal || ''
})

const displayLanguage = computed(() => {
  const lang = String(props.language || '').trim()
  return lang || 'plaintext'
})

const languageExtension = computed(() => {
  const lang = String(props.language || '').toLowerCase()

  if (lang === 'python') return python()

  // FileViewer maps many to 'javascript'/'typescript'
  if (lang === 'typescript') return javascript({ typescript: true, jsx: true })
  if (lang === 'javascript') return javascript({ jsx: true })

  // Common aliases
  if (lang === 'js') return javascript({ jsx: true })
  if (lang === 'ts' || lang === 'tsx') return javascript({ typescript: true, jsx: true })
  if (lang === 'jsx') return javascript({ jsx: true })

  // Reasonable defaults
  if (lang === 'json') return javascript({ typescript: false })

  return null
})

const extensions = computed(() => {
  return [
    lineNumbers(),
    highlightActiveLineGutter(),
    highlightActiveLine(),
    oneDark,
    languageExtension.value,
    EditorState.readOnly.of(true),
    EditorView.editable.of(false)
  ].filter(Boolean)
})

// Copy code to clipboard
async function copyCode() {
  try {
    await navigator.clipboard.writeText(code.value)
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 2000)
  } catch (err) {
    console.error('Copy error:', err)
  }
}
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

.editor-container {
  height: 100%;
  min-height: 360px;
}

/* Make CodeMirror match our app shell */
.editor-container :deep(.cm-editor) {
  height: 100%;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
  font-size: 13px;
}

.editor-container :deep(.cm-scroller) {
  overflow: auto;
}

.editor-container :deep(.cm-gutters) {
  background: #1f2937; /* gray-800 */
  border-right: 1px solid #374151; /* gray-700 */
}

.editor-container :deep(.cm-activeLineGutter) {
  background: rgba(255, 255, 255, 0.04);
}

.editor-container :deep(.cm-activeLine) {
  background: rgba(255, 255, 255, 0.03);
}
</style>
