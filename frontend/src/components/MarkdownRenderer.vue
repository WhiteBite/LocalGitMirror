<template>
  <div class="markdown-renderer">
    <div ref="contentRef" class="markdown-content" v-html="renderedContent"></div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch, nextTick } from 'vue'
import { marked } from 'marked'
import mermaid from 'mermaid'
import hljs from 'highlight.js'
import 'highlight.js/styles/github-dark.css'

const props = defineProps({
  content: {
    type: String,
    required: true
  }
})

const contentRef = ref(null)

// Configure marked
marked.setOptions({
  highlight: function(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang }).value
      } catch (err) {
        console.error('Highlight error:', err)
      }
    }
    return hljs.highlightAuto(code).value
  },
  breaks: true,
  gfm: true
})

// Initialize mermaid
onMounted(() => {
  mermaid.initialize({
    startOnLoad: false,
    theme: 'dark',
    securityLevel: 'loose',
    fontFamily: 'monospace'
  })
  renderMermaid()
})

// Render markdown content
const renderedContent = computed(() => {
  if (!props.content) return ''
  
  // Replace mermaid code blocks with divs for mermaid rendering
  let content = props.content.replace(/```mermaid\n([\s\S]*?)```/g, (match, code) => {
    const id = 'mermaid-' + Math.random().toString(36).substr(2, 9)
    return `<div class="mermaid-diagram" data-mermaid-id="${id}">${code}</div>`
  })
  
  return marked.parse(content)
})

// Render mermaid diagrams
async function renderMermaid() {
  await nextTick()
  
  if (!contentRef.value) return
  
  const diagrams = contentRef.value.querySelectorAll('.mermaid-diagram')
  
  for (const diagram of diagrams) {
    const code = diagram.textContent
    const id = diagram.getAttribute('data-mermaid-id')
    
    try {
      const { svg } = await mermaid.render(id, code)
      diagram.innerHTML = svg
      diagram.classList.add('mermaid-rendered')
    } catch (err) {
      console.error('Mermaid render error:', err)
      diagram.innerHTML = `<pre class="mermaid-error">Error rendering diagram:\n${err.message}</pre>`
    }
  }
}

// Watch for content changes
watch(() => props.content, () => {
  nextTick(() => {
    renderMermaid()
  })
})
</script>

<style scoped>
.markdown-renderer {
  @apply w-full;
}

.markdown-content {
  @apply text-gray-200;
}

/* Headings */
.markdown-content :deep(h1) {
  @apply text-3xl font-bold mb-4 mt-6 text-white border-b border-gray-700 pb-2;
}

.markdown-content :deep(h2) {
  @apply text-2xl font-bold mb-3 mt-5 text-white border-b border-gray-700 pb-2;
}

.markdown-content :deep(h3) {
  @apply text-xl font-bold mb-2 mt-4 text-white;
}

.markdown-content :deep(h4) {
  @apply text-lg font-bold mb-2 mt-3 text-gray-100;
}

.markdown-content :deep(h5) {
  @apply text-base font-bold mb-2 mt-3 text-gray-100;
}

.markdown-content :deep(h6) {
  @apply text-sm font-bold mb-2 mt-3 text-gray-200;
}

/* Paragraphs */
.markdown-content :deep(p) {
  @apply mb-4 leading-relaxed;
}

/* Lists */
.markdown-content :deep(ul) {
  @apply list-disc list-inside mb-4 space-y-2;
}

.markdown-content :deep(ol) {
  @apply list-decimal list-inside mb-4 space-y-2;
}

.markdown-content :deep(li) {
  @apply ml-4;
}

.markdown-content :deep(li > ul),
.markdown-content :deep(li > ol) {
  @apply mt-2 ml-4;
}

/* Links */
.markdown-content :deep(a) {
  @apply text-blue-400 hover:text-blue-300 underline transition-colors;
}

/* Code */
.markdown-content :deep(code) {
  @apply bg-gray-800 text-pink-400 px-1.5 py-0.5 rounded text-sm font-mono;
}

.markdown-content :deep(pre) {
  @apply bg-gray-900 rounded-lg p-4 mb-4 overflow-x-auto;
}

.markdown-content :deep(pre code) {
  @apply bg-transparent text-gray-200 p-0;
}

/* Blockquotes */
.markdown-content :deep(blockquote) {
  @apply border-l-4 border-blue-500 pl-4 py-2 mb-4 italic text-gray-300 bg-gray-800 rounded-r;
}

/* Tables */
.markdown-content :deep(table) {
  @apply w-full mb-4 border-collapse;
}

.markdown-content :deep(thead) {
  @apply bg-gray-800;
}

.markdown-content :deep(th) {
  @apply border border-gray-700 px-4 py-2 text-left font-bold text-white;
}

.markdown-content :deep(td) {
  @apply border border-gray-700 px-4 py-2;
}

.markdown-content :deep(tr:nth-child(even)) {
  @apply bg-gray-800;
}

/* Horizontal rule */
.markdown-content :deep(hr) {
  @apply border-gray-700 my-6;
}

/* Images */
.markdown-content :deep(img) {
  @apply max-w-full h-auto rounded-lg my-4;
}

/* Mermaid diagrams */
.markdown-content :deep(.mermaid-diagram) {
  @apply my-6 flex justify-center bg-gray-900 rounded-lg p-4;
}

.markdown-content :deep(.mermaid-rendered) {
  @apply bg-transparent;
}

.markdown-content :deep(.mermaid-error) {
  @apply bg-red-900 text-red-200 p-4 rounded border border-red-700;
}

/* Task lists */
.markdown-content :deep(input[type="checkbox"]) {
  @apply mr-2;
}
</style>
