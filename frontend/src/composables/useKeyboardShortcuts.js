import { onMounted, onUnmounted } from 'vue'

export function useKeyboardShortcuts(handlers) {
  const handleKeydown = (e) => {
    // Ctrl+P - Quick Open
    if (e.ctrlKey && e.key === 'p') {
      e.preventDefault()
      handlers.quickOpen?.()
    }
    
    // Ctrl+B - Toggle Sidebar
    if (e.ctrlKey && e.key === 'b') {
      e.preventDefault()
      handlers.toggleSidebar?.()
    }
    
    // Ctrl+F - Search in file
    if (e.ctrlKey && e.key === 'f') {
      e.preventDefault()
      handlers.searchInFile?.()
    }
    
    // Ctrl+Shift+C - Copy path
    if (e.ctrlKey && e.shiftKey && e.key === 'C') {
      e.preventDefault()
      handlers.copyPath?.()
    }
    
    // Escape - Close preview
    if (e.key === 'Escape') {
      handlers.closePreview?.()
    }
    
    // Enter - Open in editor
    if (e.key === 'Enter' && !e.ctrlKey && !e.shiftKey) {
      handlers.openInEditor?.()
    }
  }

  onMounted(() => {
    document.addEventListener('keydown', handleKeydown)
  })

  onUnmounted(() => {
    document.removeEventListener('keydown', handleKeydown)
  })

  return { handleKeydown }
}
