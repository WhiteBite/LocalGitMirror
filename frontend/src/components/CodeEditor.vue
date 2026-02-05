<template>
  <div class="code-editor" @keydown.ctrl.s.prevent="saveFile">
    <div class="editor-toolbar">
      <div class="file-info">
        <span class="file-name">{{ fileName }}</span>
        <span v-if="isModified" class="modified-dot" title="Unsaved changes">●</span>
      </div>
      <div class="editor-actions">
        <button 
          class="save-btn" 
          :disabled="!isModified || saving" 
          :class="{ 'pulse': isModified }"
          @click="saveFile"
        >
          {{ saving ? 'Saving...' : 'Save & Commit' }}
        </button>
      </div>
    </div>
    
    <div class="editor-main">
      <codemirror
        v-model="code"
        placeholder="Code goes here..."
        :style="{ height: '100%' }"
        :autofocus="true"
        :indent-with-tab="true"
        :tab-size="4"
        :extensions="extensions"
        @change="handleCodeChange"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { Codemirror } from 'vue-codemirror'
import { javascript } from '@codemirror/lang-javascript'
import { python } from '@codemirror/lang-python'
import { oneDark } from '@codemirror/theme-one-dark'
import axios from 'axios'
import { useSystemStore } from '@/stores/system'

const props = defineProps({
  filePath: String,
  initialContent: String
})

const emit = defineEmits(['saved'])
const systemStore = useSystemStore()

const code = ref(props.initialContent || '')
const isModified = ref(false)
const saving = ref(false)

const fileName = computed(() => {
  if (!props.filePath) return 'Unknown'
  return props.filePath.split('/').pop()
})

const extensions = computed(() => {
  const exts = [oneDark]
  if (props.filePath?.endsWith('.py')) exts.push(python())
  if (props.filePath?.endsWith('.js') || props.filePath?.endsWith('.ts')) exts.push(javascript())
  return exts
})

watch(() => props.initialContent, (newVal) => {
  code.value = newVal || ''
  isModified.value = false
})

function handleCodeChange() {
  isModified.value = code.value !== props.initialContent
}

async function saveFile() {
  if (!isModified.value || saving.value) return
  
  saving.value = true
  try {
    // 1. Save file content
    await axios.post('/api/file/save', {
      path: props.filePath,
      content: code.value
    })
    
    // 2. Automatic commit for this specific change
    await axios.post('/api/git/save-and-sync', null, {
      params: { message: `Edit ${fileName.value} via Browser` }
    })
    
    isModified.value = false
    systemStore.addNotification(`File ${fileName.value} saved and committed`, 'success')
    emit('saved')
  } catch (err) {
    console.error('Failed to save file', err)
    systemStore.addNotification('Failed to save file', 'error')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.code-editor {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #282c34;
}

.editor-toolbar {
  height: 35px;
  background: #21252b;
  border-bottom: 1px solid #181a1f;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 15px;
}

.file-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #abb2bf;
}

.modified-dot {
  color: #d19a66;
  font-size: 14px;
}

.save-btn {
  background: var(--accent);
  color: white;
  border: none;
  padding: 3px 10px;
  font-size: 11px;
  border-radius: 3px;
  cursor: pointer;
  font-weight: 600;
  transition: all 0.2s;
}

.save-btn:disabled {
  opacity: 0.5;
  cursor: default;
}

.save-btn.pulse {
  box-shadow: 0 0 0 0 rgba(55, 148, 255, 0.7);
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0% { box-shadow: 0 0 0 0 rgba(55, 148, 255, 0.4); }
  70% { box-shadow: 0 0 0 10px rgba(55, 148, 255, 0); }
  100% { box-shadow: 0 0 0 0 rgba(55, 148, 255, 0); }
}

.editor-main {
  flex: 1;
  overflow: hidden;
}
</style>
