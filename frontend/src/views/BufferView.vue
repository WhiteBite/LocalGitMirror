<template>
  <div
    class="chat-view"
    :class="{ 'drag-over': dragOver }"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent
    @dragleave.prevent="onDragLeave"
    @drop.prevent="onDrop"
  >
    <header class="view-header flex justify-between items-center">
      <h1>{{ t('exchange.title') }}</h1>
      <div class="header-actions">
        <input v-model="filter" class="filter-input" :placeholder="t('exchange.filter_placeholder')" />
        <button class="icon-btn" :title="t('common.refresh')" :disabled="loading" @click="refresh">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15" /></svg>
        </button>
        <button v-if="bufferStore.items.length" class="btn-clear" @click="clearAll">{{ t('exchange.clear_all') }}</button>
      </div>
    </header>

    <div ref="scrollEl" class="chat-scroll">
      <div v-if="bufferStore.error === 'no_password'" class="alert-card">
        {{ t('exchange.no_password') }}
      </div>
      <div v-if="!filteredMessages.length" class="empty-state">{{ t('exchange.empty') }}</div>

      <div
        v-for="msg in filteredMessages"
        :key="msg.key"
        class="msg-row"
        :class="sideClass(msg)"
      >
        <div class="bubble" :class="[msg.status || '']">
          <template v-if="msg.kind === 'message'">
            <div class="bubble-text">{{ bubbleText(msg) }}</div>
            <button
              v-if="msg.source === 'buffer' && !expanded[msg.key]"
              class="link-btn"
              @click="expandMessage(msg)"
            >
              {{ t('exchange.expand') }}
            </button>
            <button
              v-else-if="msg.source === 'buffer' && expanded[msg.key]"
              class="link-btn"
              @click="expanded[msg.key] = false"
            >
              {{ t('exchange.collapse') }}
            </button>
          </template>

          <template v-else-if="msg.kind === 'image'">
            <img
              v-if="thumbOf(msg)"
              :src="thumbOf(msg)"
              class="bubble-thumb"
              alt=""
              @click="openImage(msg)"
            />
            <div v-else class="file-card" @click="openImage(msg)">
              <span class="file-name">{{ displayText(msg) }}</span>
            </div>
          </template>

          <template v-else>
            <div class="file-card" @click="msg.kind === 'text' ? openText(msg) : downloadRow(msg)">
              <span class="file-name">{{ displayText(msg) }}</span>
              <span class="file-size">{{ formatBytes(msg.size) }}</span>
            </div>
          </template>

          <div class="bubble-footer">
            <span>{{ formatTime(msg.ts) }}</span>
            <span v-if="msg.size">· {{ formatBytes(msg.size) }}</span>
            <span v-if="sideLabel(msg)">· {{ sideLabel(msg) }}</span>
            <span v-if="msg.pinned" class="pin-mark" :title="t('exchange.pinned')">★</span>
            <span v-if="msg.status === 'pending'" class="status">{{ t('exchange.pending') }}</span>
            <span v-else-if="msg.status === 'failed'" class="status failed">
              {{ t('exchange.failed') }}
              <button class="link-btn" @click="retryPending(msg)">{{ t('exchange.retry') }}</button>
            </span>
          </div>

          <div v-if="!msg.status" class="bubble-actions">
            <button
              v-if="msg.source === 'buffer'"
              class="icon-btn mini"
              :title="copiedKey === msg.key ? t('exchange.copied') : t('exchange.copy')"
              @click="copyBuffer(msg)"
            >
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" /></svg>
            </button>
            <button
              v-if="msg.source === 'buffer'"
              class="icon-btn mini"
              :class="{ active: msg.pinned }"
              :title="msg.pinned ? t('exchange.unpin') : t('exchange.pin')"
              @click="togglePin(msg)"
            >
              <svg viewBox="0 0 24 24" width="13" height="13" :fill="msg.pinned ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z" /></svg>
            </button>
            <button
              v-if="msg.source === 'file'"
              class="icon-btn mini"
              :title="t('exchange.download')"
              @click="downloadRow(msg)"
            >
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" /></svg>
            </button>
            <button class="icon-btn mini danger" :title="t('common.delete')" @click="removeRow(msg)">
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6" /><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" /></svg>
            </button>
          </div>
        </div>
      </div>
    </div>

    <div class="composer">
      <button class="icon-btn" :title="t('exchange.attach')" @click="fileInput?.click()">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" /></svg>
      </button>
      <input ref="fileInput" type="file" multiple hidden @change="onAttach" />
      <textarea
        v-model="composer"
        class="composer-input"
        rows="1"
        :placeholder="t('exchange.composer_placeholder')"
        @keydown.enter.exact.prevent="sendComposer"
      ></textarea>
      <button class="send-btn" :disabled="!composer.trim()" @click="sendComposer">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13" /><polygon points="22 2 15 22 11 13 2 9 22 2" /></svg>
      </button>
    </div>

    <transition name="fade">
      <div v-if="textPreview" class="modal-overlay" @click.self="textPreview = null">
        <div class="modal preview-modal">
          <div class="preview-head">
            <h3>{{ textPreview.name }}</h3>
            <button class="icon-btn" :title="t('common.hide')" @click="textPreview = null">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" /></svg>
            </button>
          </div>
          <pre class="preview-body">{{ textPreview.content }}</pre>
        </div>
      </div>
    </transition>

    <transition name="fade">
      <div v-if="imageOverlay" class="modal-overlay image-overlay" @click.self="imageOverlay = null">
        <img :src="imageOverlay.url" :alt="imageOverlay.name" />
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed, reactive, watch, nextTick, onActivated, onDeactivated, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import axios from 'axios'
import { useBufferStore } from '@/stores/buffer'
import { usePostboxStore } from '@/stores/postbox'
import { useReposStore } from '@/stores/repos'
import { useSystemStore } from '@/stores/system'

const { t } = useI18n()
const bufferStore = useBufferStore()
const postboxStore = usePostboxStore()
const reposStore = useReposStore()
const systemStore = useSystemStore()

const IMAGE_EXTS = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp']
const TEXT_EXTS = ['log', 'txt', 'md']
const MIME_BY_EXT = {
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg',
  gif: 'image/gif', webp: 'image/webp', bmp: 'image/bmp'
}
const POLL_MS = 5000

const filter = ref('')
const composer = ref('')
const dragOver = ref(false)
const rid = ref('')
const scrollEl = ref(null)
const fileInput = ref(null)
const busy = reactive({})
const metas = reactive({})
const contents = reactive({})
const expanded = reactive({})
const thumbs = reactive({})
const copiedKey = ref('')
const textPreview = ref(null)
const imageOverlay = ref(null)
const pending = ref([])

let dragDepth = 0
let pollTimer = null
let pendingSeq = 0

const loading = computed(() => bufferStore.loading || postboxStore.loading)

const messages = computed(() => {
  const rows = []
  for (const it of bufferStore.items) {
    rows.push({
      key: `b-${it.id}`, source: 'buffer', kind: 'message',
      id: it.id, ts: it.ts || 0, size: it.size || 0, pinned: !!it.pinned, raw: it
    })
  }
  for (const it of postboxStore.items) {
    const name = metas[`f-${it.id}`]?.text || it.path || ''
    rows.push({
      key: `f-${it.id}`, source: 'file', kind: fileKind(name),
      id: it.id, ts: it.mtime || 0,
      size: it.plain_size > 0 ? it.plain_size : (it.size || 0),
      pinned: false, raw: it
    })
  }
  for (const p of pending.value) rows.push(p)
  rows.sort((a, b) => a.ts - b.ts)
  return rows
})

const filteredMessages = computed(() => {
  const q = filter.value.trim().toLowerCase()
  if (!q) return messages.value
  return messages.value.filter(m =>
    displayText(m).toLowerCase().includes(q) ||
    (contents[m.key] || '').toLowerCase().includes(q)
  )
})

function extOf(name) {
  const i = name.lastIndexOf('.')
  return i >= 0 ? name.slice(i + 1).toLowerCase() : ''
}

function fileKind(name) {
  const ext = extOf(name)
  if (IMAGE_EXTS.includes(ext)) return 'image'
  if (TEXT_EXTS.includes(ext)) return 'text'
  return 'file'
}

function displayText(msg) {
  if (msg.source === 'pending') return msg.name || msg.text || ''
  return metas[msg.key]?.text || msg.raw.hint || msg.raw.path || t('exchange.encrypted')
}

function bubbleText(msg) {
  return expanded[msg.key] && contents[msg.key] ? contents[msg.key] : displayText(msg)
}

function sideOf(msg) {
  if (msg.source === 'pending') return 'h'
  return metas[msg.key]?.side || ''
}

function sideClass(msg) {
  const s = sideOf(msg)
  return s === 'h' ? 'own' : s === 'w' ? 'theirs' : 'unknown'
}

function sideLabel(msg) {
  const s = sideOf(msg)
  return s === 'h' ? t('exchange.side_home') : s === 'w' ? t('exchange.side_work') : ''
}

function thumbOf(msg) {
  return thumbs[msg.key] || null
}

async function resolveRid() {
  if (!reposStore.currentRepo) {
    await reposStore.fetchRepos()
    try {
      const statusResp = await axios.get('/api/status')
      if (statusResp.data.current_repo) reposStore.currentRepo = statusResp.data.current_repo
    } catch (err) {
      console.error('Failed to resolve current repo:', err)
    }
  }
  return reposStore.currentRepo || 'default'
}

async function resolveMetas() {
  const password = await bufferStore.ensurePassword()
  if (!password) return
  const jobs = []
  for (const it of bufferStore.items) {
    const key = `b-${it.id}`
    if (metas[key] !== undefined) continue
    jobs.push(bufferStore.revealHint(it).then(m => { metas[key] = m }))
  }
  for (const it of postboxStore.items) {
    const key = `f-${it.id}`
    if (metas[key] !== undefined) continue
    jobs.push(postboxStore.revealName(it).then(m => { metas[key] = m }))
  }
  await Promise.all(jobs)
}

function pruneThumbs() {
  const alive = new Set(postboxStore.items.map(it => `f-${it.id}`))
  for (const key of Object.keys(thumbs)) {
    if (!alive.has(key)) {
      URL.revokeObjectURL(thumbs[key])
      delete thumbs[key]
    }
  }
}

async function buildThumbs() {
  for (const it of postboxStore.items) {
    const key = `f-${it.id}`
    if (thumbs[key]) continue
    const name = metas[key]?.text || it.path || ''
    if (fileKind(name) !== 'image') continue
    try {
      const bytes = await postboxStore.fetchPlainBytes(rid.value, it.id)
      thumbs[key] = URL.createObjectURL(
        new Blob([bytes], { type: MIME_BY_EXT[extOf(name)] || 'application/octet-stream' })
      )
    } catch (err) {
      console.error('Failed to build thumbnail:', err)
    }
  }
}

function scrollBottom() {
  nextTick(() => {
    const el = scrollEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

async function refresh() {
  rid.value = await resolveRid()
  await bufferStore.ensurePassword()
  await Promise.all([bufferStore.fetchItems(), postboxStore.fetchItems(rid.value)])
  await resolveMetas()
  pruneThumbs()
  await buildThumbs()
}

async function expandMessage(msg) {
  expanded[msg.key] = true
  if (contents[msg.key] !== undefined) return
  busy[msg.key] = true
  try {
    contents[msg.key] = await bufferStore.revealItem(msg.id)
  } catch (err) {
    console.error('Failed to load full message:', err)
    systemStore.addNotification(t('exchange.decrypt_error'), 'error')
  } finally {
    busy[msg.key] = false
  }
}

async function copyBuffer(msg) {
  busy[msg.key] = true
  try {
    const text = contents[msg.key] ?? await bufferStore.revealItem(msg.id)
    contents[msg.key] = text
    await navigator.clipboard.writeText(text)
    copiedKey.value = msg.key
    setTimeout(() => { if (copiedKey.value === msg.key) copiedKey.value = '' }, 2000)
  } catch (err) {
    console.error('Failed to copy buffer entry:', err)
    systemStore.addNotification(t('exchange.decrypt_error'), 'error')
  } finally {
    busy[msg.key] = false
  }
}

async function openImage(msg) {
  if (msg.source === 'pending') return
  if (thumbs[msg.key]) {
    imageOverlay.value = { url: thumbs[msg.key], name: displayText(msg) }
    return
  }
  busy[msg.key] = true
  try {
    const bytes = await postboxStore.fetchPlainBytes(rid.value, msg.id)
    const url = URL.createObjectURL(
      new Blob([bytes], { type: MIME_BY_EXT[extOf(displayText(msg))] || 'image/png' })
    )
    thumbs[msg.key] = url
    imageOverlay.value = { url, name: displayText(msg) }
  } catch (err) {
    console.error('Failed to decrypt image:', err)
    systemStore.addNotification(t('exchange.decrypt_error'), 'error')
  } finally {
    busy[msg.key] = false
  }
}

async function openText(msg) {
  if (msg.source === 'pending') return
  busy[msg.key] = true
  try {
    const bytes = await postboxStore.fetchPlainBytes(rid.value, msg.id)
    textPreview.value = { name: displayText(msg), content: new TextDecoder().decode(bytes) }
  } catch (err) {
    console.error('Failed to decrypt text file:', err)
    systemStore.addNotification(t('exchange.decrypt_error'), 'error')
  } finally {
    busy[msg.key] = false
  }
}

async function downloadRow(msg) {
  if (msg.source === 'pending') return
  busy[msg.key] = true
  try {
    const bytes = await postboxStore.fetchPlainBytes(rid.value, msg.id)
    const url = URL.createObjectURL(new Blob([bytes]))
    const a = document.createElement('a')
    a.href = url
    a.download = displayText(msg)
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 30000)
  } catch (err) {
    console.error('Failed to download file:', err)
    systemStore.addNotification(t('exchange.decrypt_error'), 'error')
  } finally {
    busy[msg.key] = false
  }
}

async function togglePin(msg) {
  busy[msg.key] = true
  try {
    await bufferStore.togglePin(msg.id, !msg.pinned)
  } catch (err) {
    console.error('Failed to toggle pin:', err)
    systemStore.addNotification(t('exchange.pin_error'), 'error')
  } finally {
    busy[msg.key] = false
  }
}

async function removeRow(msg) {
  try {
    if (msg.source === 'pending') {
      pending.value = pending.value.filter(x => x.key !== msg.key)
      return
    }
    if (msg.source === 'buffer') {
      await bufferStore.deleteItem(msg.id)
    } else {
      await postboxStore.deleteItem(rid.value, msg.id)
      if (thumbs[msg.key]) {
        URL.revokeObjectURL(thumbs[msg.key])
        delete thumbs[msg.key]
      }
    }
    delete metas[msg.key]
    delete contents[msg.key]
  } catch (err) {
    console.error('Failed to delete entry:', err)
    systemStore.addNotification(t('exchange.delete_error'), 'error')
  }
}

async function clearAll() {
  if (!confirm(t('exchange.clear_confirm'))) return
  try {
    await bufferStore.clearAll()
  } catch (err) {
    console.error('Failed to clear buffer:', err)
    systemStore.addNotification(t('exchange.delete_error'), 'error')
  }
}

function pushPending(fields) {
  const p = {
    key: `p-${++pendingSeq}`, source: 'pending',
    ts: Date.now() / 1000, size: 0, pinned: false, status: 'pending',
    ...fields
  }
  pending.value.push(p)
  scrollBottom()
  return p
}

async function settlePending(p, job) {
  try {
    await job()
    pending.value = pending.value.filter(x => x.key !== p.key)
    await resolveMetas()
    pruneThumbs()
    await buildThumbs()
  } catch (err) {
    console.error('Failed to send:', err)
    p.status = 'failed'
  }
}

async function sendComposer() {
  const text = composer.value.replace(/\n+$/, '')
  if (!text.trim()) return
  composer.value = ''
  const p = pushPending({ kind: 'message', text })
  await settlePending(p, () => bufferStore.pushItem(text))
}

function onAttach(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  if (files.length) uploadFiles(files)
}

async function uploadFiles(files) {
  if (!rid.value) rid.value = await resolveRid()
  for (const file of files) {
    const name = file.name || `shot-${Date.now()}.png`
    const p = pushPending({ kind: fileKind(name), name, size: file.size, file })
    await settlePending(p, async () => {
      const bytes = new Uint8Array(await file.arrayBuffer())
      await postboxStore.uploadFile(rid.value, name, bytes)
    })
  }
}

async function retryPending(msg) {
  msg.status = 'pending'
  await settlePending(msg, async () => {
    if (msg.kind === 'message') {
      await bufferStore.pushItem(msg.text)
    } else {
      const bytes = new Uint8Array(await msg.file.arrayBuffer())
      await postboxStore.uploadFile(rid.value, msg.name, bytes)
    }
  })
}

function onDragEnter() {
  dragDepth++
  dragOver.value = true
}

function onDragLeave() {
  dragDepth = Math.max(0, dragDepth - 1)
  if (!dragDepth) dragOver.value = false
}

async function onDrop(e) {
  dragDepth = 0
  dragOver.value = false
  const files = Array.from(e.dataTransfer?.files || [])
  if (files.length) await uploadFiles(files)
}

async function onPaste(e) {
  const dt = e.clipboardData
  if (!dt) return
  const files = Array.from(dt.files || [])
  if (files.length) {
    e.preventDefault()
    await uploadFiles(files.map(nameClipboardFile))
    return
  }
  const el = e.target
  if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) return
  const text = dt.getData('text/plain')
  if (text && text.trim()) {
    e.preventDefault()
    const p = pushPending({ kind: 'message', text })
    await settlePending(p, () => bufferStore.pushItem(text))
  }
}

function nameClipboardFile(file) {
  if (file.type.startsWith('image/') && (!file.name || file.name === 'image.png')) {
    return new File([file], `shot-${Date.now()}.png`, { type: file.type })
  }
  return file
}

watch(() => reposStore.currentRepo, async (repo) => {
  if (!repo || repo === rid.value) return
  rid.value = repo
  await postboxStore.fetchItems(repo)
  await resolveMetas()
  pruneThumbs()
  await buildThumbs()
})

onActivated(() => {
  window.addEventListener('paste', onPaste)
  refresh().then(scrollBottom)
  pollTimer = setInterval(refresh, POLL_MS)
})

onDeactivated(() => {
  window.removeEventListener('paste', onPaste)
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
})

onUnmounted(() => {
  window.removeEventListener('paste', onPaste)
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
  for (const key of Object.keys(thumbs)) {
    URL.revokeObjectURL(thumbs[key])
    delete thumbs[key]
  }
})

function formatBytes(bytes) {
  if (!bytes || bytes <= 0) return '0 Б'
  const units = ['Б', 'КБ', 'МБ', 'ГБ']
  let i = 0
  let val = bytes
  while (val >= 1024 && i < units.length - 1) { val /= 1024; i++ }
  return `${val.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

function formatTime(ts) {
  try {
    return new Date(ts * 1000).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
  } catch {
    return ''
  }
}
</script>

<style scoped>
.chat-view { display: flex; flex-direction: column; height: 100%; color: var(--text-main); position: relative; }
.chat-view.drag-over::after {
  content: '';
  position: absolute;
  inset: 0;
  border: 2px dashed var(--accent);
  border-radius: 8px;
  background: rgba(55, 148, 255, 0.06);
  pointer-events: none;
  z-index: 10;
}
.view-header { padding: 20px 30px; border-bottom: 1px solid var(--border-color); background: var(--bg-primary); }
.view-header h1 { margin: 0; font-size: 20px; font-weight: 500; color: var(--text-bright); }
.header-actions { display: flex; align-items: center; gap: 12px; }
.filter-input {
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  color: var(--text-main);
  font-size: 13px;
  padding: 7px 12px;
  outline: none;
  width: 200px;
}
.filter-input:focus { border-color: var(--accent); }
.alert-card {
  background: rgba(204, 167, 0, 0.1);
  border: 1px solid rgba(204, 167, 0, 0.3);
  color: var(--warning);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 16px;
  font-size: 13px;
}
.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 20px 30px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.empty-state { text-align: center; color: var(--text-secondary); padding: 60px 0; font-size: 14px; }

.msg-row { display: flex; }
.msg-row.own { justify-content: flex-end; }
.msg-row.theirs, .msg-row.unknown { justify-content: flex-start; }

.bubble {
  position: relative;
  max-width: 70%;
  border-radius: 12px;
  padding: 10px 14px;
  border: 1px solid var(--border-color);
  background: var(--bg-card);
  word-break: break-word;
}
.msg-row.own .bubble { background: rgba(55, 148, 255, 0.12); border-color: rgba(55, 148, 255, 0.35); }
.msg-row.unknown .bubble { opacity: 0.85; }
.bubble.pending { opacity: 0.7; }
.bubble.failed { border-color: var(--error); }

.bubble-text { font-size: 14px; white-space: pre-wrap; }
.bubble-thumb {
  max-width: 240px;
  max-height: 320px;
  border-radius: 8px;
  cursor: pointer;
  display: block;
}
.file-card {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  min-width: 180px;
}
.file-card:hover .file-name { color: var(--accent); }
.file-name { font-size: 14px; color: var(--text-bright); word-break: break-all; }
.file-size { font-size: 12px; color: var(--text-secondary); white-space: nowrap; }

.bubble-footer {
  margin-top: 6px;
  display: flex;
  gap: 6px;
  font-size: 11px;
  color: var(--text-secondary);
  align-items: center;
}
.pin-mark { color: var(--warning); }
.status.failed { color: var(--error); }
.link-btn {
  background: none;
  border: none;
  color: var(--accent);
  cursor: pointer;
  font-size: 11px;
  padding: 0;
  text-decoration: underline;
}
.bubble-text + .link-btn { margin-top: 4px; }

.bubble-actions {
  position: absolute;
  top: -10px;
  right: 8px;
  display: none;
  gap: 4px;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 2px;
}
.bubble:hover .bubble-actions { display: flex; }
.icon-btn {
  background: none; border: 1px solid var(--border-color); color: var(--text-secondary);
  cursor: pointer; padding: 6px; border-radius: 6px; display: flex; align-items: center; justify-content: center;
  transition: all 0.2s;
}
.icon-btn:hover:not(:disabled) { background: rgba(255,255,255,0.06); color: var(--text-bright); }
.icon-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.icon-btn.mini { padding: 3px; border: none; }
.icon-btn.mini.active { color: var(--warning); }
.icon-btn.mini.danger:hover { color: var(--error); }
.btn-clear {
  background: transparent; border: 1px solid var(--border-color); color: var(--text-secondary);
  border-radius: 6px; padding: 6px 12px; font-size: 12px; cursor: pointer; transition: all 0.2s;
}
.btn-clear:hover { color: var(--error); border-color: var(--error); }

.composer {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 12px 30px;
  border-top: 1px solid var(--border-color);
  background: var(--bg-primary);
}
.composer-input {
  flex: 1;
  resize: none;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  color: var(--text-main);
  font-size: 14px;
  font-family: inherit;
  padding: 10px 14px;
  outline: none;
  min-height: 40px;
  max-height: 160px;
  line-height: 1.4;
}
.composer-input:focus { border-color: var(--accent); }
.send-btn {
  background: var(--accent);
  border: none;
  color: #fff;
  border-radius: 10px;
  width: 40px;
  height: 40px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.2s;
}
.send-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.preview-modal {
  width: 720px;
  max-width: 90vw;
  max-height: 80vh;
  display: flex;
  flex-direction: column;
}
.preview-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.preview-head h3 { margin: 0; word-break: break-all; }
.preview-body {
  margin: 0;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 12px;
  overflow: auto;
  font-family: ui-monospace, Menlo, Consolas, monospace;
  font-size: 12px;
  color: var(--text-main);
  white-space: pre-wrap;
  word-break: break-word;
}
.image-overlay { z-index: 10001; }
.image-overlay img {
  max-width: 92vw;
  max-height: 92vh;
  border-radius: 6px;
  box-shadow: 0 10px 40px rgba(0,0,0,0.6);
}
.fade-enter-active, .fade-leave-active { transition: opacity 0.15s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
