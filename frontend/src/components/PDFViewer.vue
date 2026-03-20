<template>
  <div class="pdf-viewer">
    <!-- Toolbar -->
    <div class="toolbar">
      <div class="flex items-center space-x-4">
        <button 
          :disabled="currentPage <= 1" 
          class="nav-button"
          @click="previousPage"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 19l-7-7 7-7" />
          </svg>
          <span class="ml-1">{{ t('pdfViewer.previous') }}</span>
        </button>
        
        <div class="page-info">
          <span class="text-white font-medium">{{ currentPage }}</span>
          <span class="text-gray-400 mx-2">/</span>
          <span class="text-gray-400">{{ totalPages }}</span>
        </div>
        
        <button 
          :disabled="currentPage >= totalPages" 
          class="nav-button"
          @click="nextPage"
        >
          <span class="mr-1">{{ t('pdfViewer.next') }}</span>
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
          </svg>
        </button>
      </div>
    </div>

    <!-- PDF Canvas -->
    <div class="pdf-container">
      <div v-if="loading" class="loading-state">
        <div class="spinner"></div>
        <p class="mt-4 text-gray-400">{{ t('pdfViewer.loading_pdf') }}</p>
      </div>
      
      <div v-else-if="error" class="error-state">
        <svg class="w-12 h-12 text-red-400 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
        <p class="text-red-400">{{ error }}</p>
      </div>
      
      <canvas v-show="!loading && !error" ref="canvasRef" class="pdf-canvas"></canvas>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import * as pdfjsLib from 'pdfjs-dist'

const { t } = useI18n()

// Set worker path
pdfjsLib.GlobalWorkerOptions.workerSrc = `//cdnjs.cloudflare.com/ajax/libs/pdf.js/${pdfjsLib.version}/pdf.worker.min.js`

const props = defineProps({
  pdfData: {
    type: String,
    required: true
  }
})

const canvasRef = ref(null)
const currentPage = ref(1)
const totalPages = ref(0)
const loading = ref(true)
const error = ref(null)
const pdfDoc = ref(null)
const scale = 1.5

// Load PDF document
async function loadPDF() {
  loading.value = true
  error.value = null
  
  try {
    // Convert base64 to Uint8Array
    const binaryString = atob(props.pdfData)
    const bytes = new Uint8Array(binaryString.length)
    for (let i = 0; i < binaryString.length; i++) {
      bytes[i] = binaryString.charCodeAt(i)
    }
    
    // Load PDF
    const loadingTask = pdfjsLib.getDocument({ data: bytes })
    pdfDoc.value = await loadingTask.promise
    totalPages.value = pdfDoc.value.numPages
    
    // Render first page
    await renderPage(1)
  } catch (err) {
    console.error('PDF load error:', err)
    error.value = 'Failed to load PDF: ' + err.message
  } finally {
    loading.value = false
  }
}

// Render specific page
async function renderPage(pageNum) {
  if (!pdfDoc.value || !canvasRef.value) return
  
  try {
    const page = await pdfDoc.value.getPage(pageNum)
    const viewport = page.getViewport({ scale })
    
    const canvas = canvasRef.value
    const context = canvas.getContext('2d')
    
    canvas.height = viewport.height
    canvas.width = viewport.width
    
    const renderContext = {
      canvasContext: context,
      viewport: viewport
    }
    
    await page.render(renderContext).promise
    currentPage.value = pageNum
  } catch (err) {
    console.error('Page render error:', err)
    error.value = 'Failed to render page: ' + err.message
  }
}

// Navigation
function previousPage() {
  if (currentPage.value > 1) {
    renderPage(currentPage.value - 1)
  }
}

function nextPage() {
  if (currentPage.value < totalPages.value) {
    renderPage(currentPage.value + 1)
  }
}

// Initialize
onMounted(() => {
  if (props.pdfData) {
    loadPDF()
  }
})

// Watch for PDF data changes
watch(() => props.pdfData, (newData) => {
  if (newData) {
    currentPage.value = 1
    loadPDF()
  }
})
</script>

<style scoped>
.pdf-viewer {
  @apply bg-gray-900 rounded-lg overflow-hidden border border-gray-700;
}

.toolbar {
  @apply flex items-center justify-center px-4 py-3 bg-gray-800 border-b border-gray-700;
}

.nav-button {
  @apply flex items-center px-4 py-2 text-sm text-gray-300 hover:text-white bg-gray-700 hover:bg-gray-600 rounded transition-colors disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-gray-700 disabled:hover:text-gray-300;
}

.page-info {
  @apply flex items-center px-4 py-2 bg-gray-700 rounded;
}

.pdf-container {
  @apply flex items-center justify-center p-8 bg-gray-800 overflow-auto;
  min-height: 600px;
}

.pdf-canvas {
  @apply shadow-2xl bg-white;
  max-width: 100%;
  height: auto;
}

.loading-state {
  @apply text-center;
}

.spinner {
  @apply inline-block animate-spin rounded-full h-12 w-12 border-b-2 border-blue-500;
}

.error-state {
  @apply text-center;
}

/* Scrollbar styling */
.pdf-container::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

.pdf-container::-webkit-scrollbar-track {
  @apply bg-gray-900;
}

.pdf-container::-webkit-scrollbar-thumb {
  @apply bg-gray-600 rounded;
}

.pdf-container::-webkit-scrollbar-thumb:hover {
  @apply bg-gray-500;
}
</style>
