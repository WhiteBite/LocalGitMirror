<template>
  <div class="file-viewer">
    <!-- Toolbar -->
    <div class="toolbar">
      <div class="flex items-center space-x-2">
        <svg class="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
        </svg>
        <span class="text-sm font-medium text-white truncate">{{ fileName }}</span>
        <span class="text-xs text-gray-500 ml-2">{{ t(`fileViewer.${effectiveFileType}`) }}</span>
      </div>
      
      <div class="flex items-center space-x-2">
        <button 
          v-if="isTextFile"
          class="toolbar-button" 
          :class="{ 'active': showEditor }"
          :title="t('fileViewer.edit_file')"
          @click="showEditor = !showEditor"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
          </svg>
          <span class="ml-1">{{ showEditor ? t('fileViewer.viewer') : t('fileViewer.edit') }}</span>
        </button>
        
        <button 
          class="toolbar-button" 
          :title="t('fileViewer.open_in_editor')"
          @click="openInEditor"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4" />
          </svg>
          <span class="ml-1">{{ t('fileViewer.ide') }}</span>
        </button>
        
        <button 
          v-if="canCopy"
          class="toolbar-button" 
          :class="{ 'copied': copied }"
          :title="t('fileViewer.copy_content')"
          @click="copyContent"
        >
          <svg v-if="!copied" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
          </svg>
          <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
          </svg>
          <span class="ml-1">{{ copied ? t('fileViewer.copied') : t('fileViewer.copy') }}</span>
        </button>
        
        <button 
          class="toolbar-button" 
          :title="t('fileViewer.download')"
          @click="downloadFile"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
          </svg>
          <span class="ml-1">{{ t('fileViewer.download') }}</span>
        </button>
      </div>
    </div>

    <!-- Content Area -->
    <div class="content-area">
      <!-- Loading State -->
      <div v-if="loading" class="state-container">
        <div class="spinner"></div>
        <p class="mt-4 text-gray-400">{{ t('fileViewer.loading') }}</p>
      </div>

      <!-- Error State -->
      <div v-else-if="error" class="state-container">
        <svg class="w-12 h-12 text-red-400 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
        <p class="text-red-400 mb-4">{{ error }}</p>
        <button class="btn-primary" @click="loadFile">{{ t('fileViewer.try_again') }}</button>
      </div>

      <!-- Markdown Viewer -->
      <div v-else-if="effectiveFileType === 'markdown' && !showEditor" class="viewer-container">
        <MarkdownRenderer :content="fileContent" />
      </div>

      <!-- Code Viewer -->
      <div v-else-if="effectiveFileType === 'code' && !showEditor" class="viewer-container">
        <CodeViewer :content="fileContent" :language="codeLanguage" />
      </div>

      <!-- PDF Viewer -->
      <div v-else-if="effectiveFileType === 'pdf'" class="viewer-container pdf-viewer-wrap">
        <PDFViewer :pdf-data="fileContent" />
      </div>

      <!-- Image Viewer -->
      <div v-else-if="effectiveFileType === 'image'" class="viewer-container image-viewer-wrap">
        <img v-if="imageObjectUrl" :src="imageObjectUrl" :alt="fileName" class="preview-image" />
        <div v-else class="state-container compact-state">
          <p class="text-gray-400">{{ t('fileViewer.image_preview_unavailable') }}</p>
        </div>
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
      <div v-else-if="effectiveFileType === 'text' || effectiveFileType === 'unknown'" class="viewer-container">
        <div class="text-viewer">
          <pre class="text-content">{{ fileContent }}</pre>
        </div>
      </div>

      <div v-else class="state-container compact-state">
        <p class="text-gray-400">{{ t('fileViewer.binary_preview_unavailable') }}</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import axios from 'axios'

const { t } = useI18n()
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
const detectedFileType = ref('unknown')
const imageObjectUrl = ref('')
const currentBase64 = ref('')

const isTextFile = computed(() => ['text', 'code', 'markdown', 'unknown'].includes(effectiveFileType.value))
const canCopy = computed(() => ['text', 'code', 'markdown'].includes(effectiveFileType.value))

// Reset editor mode when file changes
watch(() => props.filePath, () => {
  showEditor.value = false
  copied.value = false
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
  return parts[parts.length - 1] || props.filePath
})

const extensionBasedType = computed(() => {
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

  // Images
  if (['png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp', 'svg', 'ico'].includes(ext)) {
    return 'image'
  }
  
  // Default to text
  return 'text'
})

const effectiveFileType = computed(() => {
  if (detectedFileType.value && detectedFileType.value !== 'unknown') {
    return detectedFileType.value
  }
  return extensionBasedType.value || 'unknown'
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
  if (!props.filePath) {
    loading.value = false
    error.value = null
    fileContent.value = ''
    detectedFileType.value = 'unknown'
    cleanupImageObjectUrl()
    return
  }

  loading.value = true
  error.value = null
  fileContent.value = ''
  currentBase64.value = ''
  cleanupImageObjectUrl()
  detectedFileType.value = 'unknown'
  
  try {
    const response = await axios.get('/api/files/content', {
      params: { path: props.filePath }
    })

    const payload = response.data || {}
    const content = payload.content ?? ''
    const apiType = payload.type
    const isBinary = Boolean(payload.is_binary)
    const extension = (payload.extension || `.${fileExtension.value}`).replace('.', '').toLowerCase()

    if (apiType === 'pdf' || extension === 'pdf') {
      detectedFileType.value = 'pdf'
      fileContent.value = String(content)
      currentBase64.value = String(content)
      return
    }

    if (apiType === 'image' || ['png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp', 'svg', 'ico'].includes(extension)) {
      detectedFileType.value = 'image'
      currentBase64.value = String(content)
      imageObjectUrl.value = createImageObjectUrl(currentBase64.value, extension)
      return
    }

    if (isBinary) {
      detectedFileType.value = 'binary'
      currentBase64.value = String(content)
      return
    }

    detectedFileType.value = extensionBasedType.value
    fileContent.value = typeof content === 'string' ? content : JSON.stringify(content, null, 2)
  } catch (err) {
    console.error('Error loading file:', err)
    error.value = err.response?.data?.detail || 'Failed to load file'
  } finally {
    loading.value = false
  }
}

// Copy content to clipboard
async function copyContent() {
  if (!canCopy.value) return
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
  let blob

  if (effectiveFileType.value === 'pdf' && currentBase64.value) {
    blob = base64ToBlob(currentBase64.value, 'application/pdf')
  } else if (effectiveFileType.value === 'image' && currentBase64.value) {
    const mime = getImageMimeType(fileExtension.value)
    blob = base64ToBlob(currentBase64.value, mime)
  } else if (effectiveFileType.value === 'binary' && currentBase64.value) {
    blob = base64ToBlob(currentBase64.value, 'application/octet-stream')
  } else {
    blob = new Blob([fileContent.value], { type: 'text/plain;charset=utf-8' })
  }

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
  axios.post('/api/editor/open', null, { params: { file: props.filePath } })
    .then(() => {
      console.log('File opened in editor')
    })
    .catch(err => {
      console.error('Failed to open in editor:', err)
    })
}

function base64ToBlob(base64String, mimeType) {
  const normalized = String(base64String || '').replace(/\s/g, '')
  const binaryString = atob(normalized)
  const length = binaryString.length
  const bytes = new Uint8Array(length)

  for (let i = 0; i < length; i += 1) {
    bytes[i] = binaryString.charCodeAt(i)
  }

  return new Blob([bytes], { type: mimeType })
}

function getImageMimeType(ext) {
  const normalized = String(ext || '').toLowerCase().replace('.', '')
  const map = {
    png: 'image/png',
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    gif: 'image/gif',
    bmp: 'image/bmp',
    webp: 'image/webp',
    svg: 'image/svg+xml',
    ico: 'image/x-icon'
  }
  return map[normalized] || 'application/octet-stream'
}

function createImageObjectUrl(base64String, ext) {
  if (!base64String) return ''
  try {
    const blob = base64ToBlob(base64String, getImageMimeType(ext))
    return URL.createObjectURL(blob)
  } catch (err) {
    console.error('Failed to create image preview URL:', err)
    return ''
  }
}

function cleanupImageObjectUrl() {
  if (imageObjectUrl.value) {
    URL.revokeObjectURL(imageObjectUrl.value)
    imageObjectUrl.value = ''
  }
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
  } else {
    cleanupImageObjectUrl()
    fileContent.value = ''
    currentBase64.value = ''
    detectedFileType.value = 'unknown'
    error.value = null
    loading.value = false
  }
})

onBeforeUnmount(() => {
  cleanupImageObjectUrl()
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

.compact-state {
  min-height: 260px;
}

.image-viewer-wrap {
  @apply h-full flex items-center justify-center;
}

.pdf-viewer-wrap {
  @apply h-full;
}

.pdf-viewer-wrap :deep(.pdf-viewer) {
  height: 100%;
}

.preview-image {
  max-width: 100%;
  max-height: calc(100vh - 220px);
  object-fit: contain;
  border: 1px solid var(--border-color);
  border-radius: 8px;
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.35);
  background: #111;
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
