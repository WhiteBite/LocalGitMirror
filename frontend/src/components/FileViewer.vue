<template>
  <div class="file-viewer">
    <!-- Toolbar -->
    <div class="toolbar">
      <div class="flex items-center space-x-2">
        <svg class="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
        </svg>
        <span class="text-sm font-medium text-white truncate">{{ fileName }}</span>
        <span class="text-xs text-gray-500 ml-2">{{ fileTypeLabel }}</span>
      </div>
      
      <div class="flex items-center space-x-2">
        <button 
          v-if="isTextFile"
          class="toolbar-button" 
          :class="{ 'active': showEditor }"
          title="Edit file"
          @click="showEditor = !showEditor"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
          </svg>
          <span class="ml-1">{{ showEditor ? 'Viewer' : 'Edit' }}</span>
        </button>
        
        <button 
          class="toolbar-button" 
          title="Open in system editor"
          @click="openInEditor"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4" />
          </svg>
          <span class="ml-1">IDE</span>
        </button>
        
        <button 
          v-if="fileType !== 'pdf'"
          class="toolbar-button" 
          :class="{ 'copied': copied }"
          title="Copy content"
          @click="copyContent"
        >
          <svg v-if="!copied" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
          </svg>
          <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
          </svg>
          <span class="ml-1">{{ copied ? 'Copied!' : 'Copy' }}</span>
        </button>
        
        <button 
          class="toolbar-button" 
          title="Download file"
          @click="downloadFile"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
          </svg>
          <span class="ml-1">Download</span>
        </button>
      </div>
    </div>

    <!-- Content Area -->
    <div class="content-area">
      <!-- Loading State -->
      <div v-if="loading" class="state-container">
        <div class="spinner"></div>
        <p class="mt-4 text-gray-400">Loading file...</p>
      </div>

      <!-- Error State -->
      <div v-else-if="error" class="state-container">
        <svg class="w-12 h-12 text-red-400 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
        <p class="text-red-400 mb-4">{{ error }}</p>
        <button class="btn-primary" @click="loadFile">Try Again</button>
      </div>

      <!-- Markdown Viewer -->
      <div v-else-if="fileType === 'markdown' && !showEditor" class="viewer-container">
        <MarkdownRenderer :content="fileContent" />
      </div>

      <!-- Code Viewer -->
      <div v-else-if="fileType === 'code' && !showEditor" class="viewer-container">
        <CodeViewer :content="fileContent" :language="codeLanguage" />
      </div>

      <!-- PDF Viewer -->
      <div v-else-if="fileType === 'pdf'" class="viewer-container">
        <PDFViewer :pdf-data="fileContent" />
      </div>

      <!-- Code Editor -->
      <div v-else-if="showEditor" class="editor-container h-full">
        <CodeEditor 
          :file-path="filePath" 
          :initial-content="fileContent" 
          @saved="onFileSaved"
        />
      </div>

      <!-- Text Viewer -->
      <div v-else class="viewer-container">
        <div class="text-viewer">
          <pre class="text-content">{{ fileContent }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import axios from 'axios'
import MarkdownRenderer from './MarkdownRenderer.vue'
import CodeViewer from './CodeViewer.vue'
import PDFViewer from './PDFViewer.vue'
import CodeEditor from './CodeEditor.vue'

const props = defineProps({
  filePath: {
    type: String,
    required: true
  }
})

const fileContent = ref('')
const loading = ref(false)
const error = ref(null)
const copied = ref(false)
const showEditor = ref(false)

const isTextFile = computed(() => fileType.value !== 'pdf' && fileType.value !== 'image')

// Reset editor mode when file changes
watch(() => props.filePath, () => {
  showEditor.value = false
})

function onFileSaved() {
  loadFile() // Reload content
}

// File type detection
const fileExtension = computed(() => {
  const parts = props.filePath.split('.')
  return parts.length > 1 ? parts[parts.length - 1].toLowerCase() : ''
})

const fileName = computed(() => {
  const parts = props.filePath.split('/')
  return parts[parts.length - 1]
})

const fileType = computed(() => {
  const ext = fileExtension.value
  
  // Markdown
  if (['md', 'markdown'].includes(ext)) {
    return 'markdown'
  }
  
  // Code
  if (['py', 'js', 'ts', 'jsx', 'tsx', 'json', 'html', 'css', 'scss', 'sass', 
       'vue', 'java', 'cpp', 'c', 'h', 'cs', 'php', 'rb', 'go', 'rs', 'swift',
       'kt', 'scala', 'sh', 'bash', 'yaml', 'yml', 'xml', 'sql', 'r', 'dart'].includes(ext)) {
    return 'code'
  }
  
  // PDF
  if (ext === 'pdf') {
    return 'pdf'
  }
  
  // Default to text
  return 'text'
})

const fileTypeLabel = computed(() => {
  const labels = {
    markdown: 'Markdown',
    code: 'Code',
    pdf: 'PDF',
    text: 'Text'
  }
  return labels[fileType.value] || 'Unknown'
})

const codeLanguage = computed(() => {
  const ext = fileExtension.value
  
  // Map extensions to highlight.js language names
  const languageMap = {
    'py': 'python',
    'js': 'javascript',
    'ts': 'typescript',
    'jsx': 'javascript',
    'tsx': 'typescript',
    'json': 'json',
    'html': 'html',
    'css': 'css',
    'scss': 'scss',
    'sass': 'sass',
    'vue': 'vue',
    'java': 'java',
    'cpp': 'cpp',
    'c': 'c',
    'h': 'c',
    'cs': 'csharp',
    'php': 'php',
    'rb': 'ruby',
    'go': 'go',
    'rs': 'rust',
    'swift': 'swift',
    'kt': 'kotlin',
    'scala': 'scala',
    'sh': 'bash',
    'bash': 'bash',
    'yaml': 'yaml',
    'yml': 'yaml',
    'xml': 'xml',
    'sql': 'sql',
    'r': 'r',
    'dart': 'dart'
  }
  
  return languageMap[ext] || ext
})

// Load file content
async function loadFile() {
  loading.value = true
  error.value = null
  
  try {
    if (fileType.value === 'pdf') {
      // Load PDF as base64
      const response = await axios.get('/api/file/pdf', {
        params: { file: props.filePath }
      })
      fileContent.value = response.data.content || response.data
    } else {
      // Load text content
      const response = await axios.get('/api/file/view', {
        params: { file: props.filePath }
      })
      fileContent.value = response.data.content || response.data
    }
  } catch (err) {
    console.error('Error loading file:', err)
    error.value = err.response?.data?.detail || 'Failed to load file'
  } finally {
    loading.value = false
  }
}

// Copy content to clipboard
async function copyContent() {
  try {
    await navigator.clipboard.writeText(fileContent.value)
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 2000)
  } catch (err) {
    console.error('Copy error:', err)
  }
}

// Download file
function downloadFile() {
  const blob = new Blob([fileContent.value], { type: 'text/plain' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName.value
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// Open in editor (placeholder - implement based on your editor integration)
function openInEditor() {
  // This would typically call an API endpoint or use a protocol handler
  // For now, just log the action
  console.log('Opening in editor:', props.filePath)
  
  // Example: You could implement this by calling a backend endpoint
  // that opens the file in VS Code or Cursor
  axios.post('/api/editor/open', { path: props.filePath })
    .then(() => {
      console.log('File opened in editor')
    })
    .catch(err => {
      console.error('Failed to open in editor:', err)
    })
}

// Initialize
onMounted(() => {
  if (props.filePath) {
    loadFile()
  }
})

// Watch for file path changes
watch(() => props.filePath, (newPath) => {
  if (newPath) {
    loadFile()
  }
})
</script>

<style scoped>
.file-viewer {
  @apply flex flex-col h-full bg-gray-900 rounded-lg overflow-hidden border border-gray-700;
}

.toolbar {
  @apply flex items-center justify-between px-4 py-3 bg-gray-800 border-b border-gray-700 flex-shrink-0;
}

.toolbar-button {
  @apply flex items-center px-3 py-1.5 text-sm text-gray-300 hover:text-white bg-gray-700 hover:bg-gray-600 rounded transition-colors;
}

.toolbar-button.copied {
  @apply text-green-400 bg-green-900 hover:bg-green-900;
}

.toolbar-button.active {
  background: var(--accent);
  color: white;
}

.editor-container {
  @apply bg-gray-900;
}

.content-area {
  @apply flex-1 overflow-auto;
}

.state-container {
  @apply flex flex-col items-center justify-center h-full min-h-[400px] text-center p-8;
}

.spinner {
  @apply inline-block animate-spin rounded-full h-12 w-12 border-b-2 border-blue-500;
}

.viewer-container {
  @apply p-6;
}

.text-viewer {
  @apply bg-gray-900 rounded-lg border border-gray-700 overflow-hidden;
}

.text-content {
  @apply p-4 text-gray-200 text-sm font-mono overflow-x-auto whitespace-pre-wrap;
}

.btn-primary {
  @apply px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white rounded transition-colors;
}

/* Scrollbar styling */
.content-area::-webkit-scrollbar {
  width: 8px;
}

.content-area::-webkit-scrollbar-track {
  @apply bg-gray-900;
}

.content-area::-webkit-scrollbar-thumb {
  @apply bg-gray-600 rounded;
}

.content-area::-webkit-scrollbar-thumb:hover {
  @apply bg-gray-500;
}
</style>
