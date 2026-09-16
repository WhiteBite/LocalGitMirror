<template>
  <div
    class="exchange-view"
    :class="{ 'drag-over': dragOver }"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent
    @dragleave.prevent="onDragLeave"
    @drop.prevent="onDrop"
  >
    <header class="view-header flex justify-between items-center">
      <h1>{{ t('exchange.title') }}</h1>
      <div class="header-actions">
        <input
          v-model="filter"
          class="filter-input"
          :placeholder="t('exchange.filter_placeholder')"
        />
        <button class="icon-btn" :title="t('common.refresh')" :disabled="loading" @click="refresh">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15" /></svg>
        </button>
        <button v-if="bufferStore.items.length" class="btn-clear" @click="clearAll">{{ t('exchange.clear_all') }}</button>
      </div>
    </header>

    <div class="view-content">
      <p class="subtitle">{{ t('exchange.subtitle') }}</p>

      <div v-if="bufferStore.error === 'no_password'" class="alert-card">
        {{ t('exchange.no_password') }}
      </div>

      <div v-if="!filteredFeed.length" class="empty-state">
        {{ t('exchange.empty') }}
      </div>

      <div
        v-for="row in filteredFeed"
        :key="row.key"
        class="feed-item"
        :class="{ clickable: true }"
        @click="openRow(row)"
      >
        <div class="row-main">
          <span class="type-icon" :class="row.kind">
            <svg v-if="row.kind === 'message'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" /></svg>
            <svg v-else-if="row.kind === 'image'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2" /><circle cx="8.5" cy="8.5" r="1.5" /><polyline points="21 15 16 10 5 21" /></svg>
            <svg v-else-if="row.kind === 'text'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" /><polyline points="14 2 14 8 20 8" /><line x1="16" y1="13" x2="8" y2="13" /><line x1="16" y1="17" x2="8" y2="17" /></svg>
            <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z" /><polyline points="13 2 13 9 20 9" /></svg>
          </span>

          <img
            v-if="row.kind === 'image' && thumbs[row.key]"
            :src="thumbs[row.key]"
            class="thumb"
            alt=""
          />

          <div class="row-text">
            <span class="row-name">{{ displayName(row) }}</span>
            <span class="row-meta">
              {{ row.source === 'buffer' ? t('exchange.source_buffer') : t('exchange.source_file') }}
              · {{ formatBytes(row.size) }} · {{ formatTime(row.ts) }}
            </span>
          </div>

          <span v-if="row.pinned" class="pin-badge" :title="t('exchange.pinned')">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" stroke="currentColor" stroke-width="2"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z" /></svg>
          </span>
        </div>

        <div class="row-actions" @click.stop>
          <template v-if="row.source === 'buffer'">
            <button class="item-btn" :disabled="busy[row.key]" @click="copyBuffer(row)">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2" /><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" /></svg>
              {{ copiedKey === row.key ? t('exchange.copied') : t('exchange.copy') }}
            </button>
            <button
              class="item-btn"
              :class="{ active: row.pinned }"
              :title="row.pinned ? t('exchange.unpin') : t('exchange.pin')"
              :disabled="busy[row.key]"
              @click="togglePin(row)"
            >
              <svg viewBox="0 0 24 24" width="14" height="14" :fill="row.pinned ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2"><path d="M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z" /></svg>
            </button>
          </template>
          <template v-else>
            <button class="item-btn" :disabled="busy[row.key]" @click="downloadRow(row)">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" /><polyline points="7 10 12 15 17 10" /><line x1="12" y1="15" x2="12" y2="3" /></svg>
              {{ t('exchange.download') }}
            </button>
          </template>
          <button class="item-btn danger" :title="t('common.delete')" @click="removeRow(row)">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6" /><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" /></svg>
          </button>
        </div>
      </div>
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
import { ref, computed, reactive, watch, onActivated, onDeactivated, onUnmounted } from 'vue'
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

const filter = ref('')
const dragOver = ref(false)
const rid = ref('')
const busy = reactive({})
const names = reactive({})
const thumbs = reactive({})
const copiedKey = ref('')
const textPreview = ref(null)
const imageOverlay = ref(null)

let dragDepth = 0

const loading = computed(() => bufferStore.loading || postboxStore.loading)

const feed = computed(() => {
  const rows = []
  for (const it of bufferStore.items) {
    rows.push({
      key: `b-${it.id}`, source: 'buffer', kind: 'message',
      id: it.id, ts: it.ts || 0, size: it.size || 0, pinned: !!it.pinned, raw: it
    })
  }
  for (const it of postboxStore.items) {
    const name = names[`f-${it.id}`] || it.path || ''
    rows.push({
      key: `f-${it.id}`, source: 'file', kind: fileKind(name),
      id: it.id, ts: it.mtime || 0,
      size: it.plain_size > 0 ? it.plain_size : (it.size || 0),
      pinned: false, raw: it
    })
  }
  rows.sort((a, b) => (b.pinned - a.pinned) || (b.ts - a.ts))
  return rows
})

const filteredFeed = computed(() => {
  const q = filter.value.trim().toLowerCase()
  if (!q) return feed.value
  return feed.value.filter(row => displayName(row).toLowerCase().includes(q))
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

function displayName(row) {
  return names[row.key] || row.raw.hint || row.raw.path || t('exchange.encrypted')
}

async function resolveRid() {
  if (!reposStore.currentRepo) {
    await reposStore.fetchRepos()
    try {
      const statusResp = await axios.get('/api/status')
      if (statusResp.data.current_repo) {
        reposStore.currentRepo = statusResp.data.current_repo
      }
    } catch (err) {
      console.error('Failed to resolve current repo:', err)
    }
  }
  return reposStore.currentRepo || 'default'
}

async function resolveNames() {
  const password = await bufferStore.ensurePassword()
  if (!password) return
  const jobs = []
  for (const it of bufferStore.items) {
    const key = `b-${it.id}`
    if (names[key] !== undefined) continue
    jobs.push(bufferStore.revealHint(it).then(n => { names[key] = n }))
  }
  for (const it of postboxStore.items) {
    const key = `f-${it.id}`
    if (names[key] !== undefined) continue
    jobs.push(postboxStore.revealName(it).then(n => { names[key] = n }))
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
    const name = names[key] || it.path || ''
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

async function refresh() {
  rid.value = await resolveRid()
  await bufferStore.ensurePassword()
  await Promise.all([bufferStore.fetchItems(), postboxStore.fetchItems(rid.value)])
  await resolveNames()
  pruneThumbs()
  await buildThumbs()
}

async function openRow(row) {
  if (row.source === 'buffer') return copyBuffer(row)
  if (row.kind === 'image') return openImage(row)
  if (row.kind === 'text') return openText(row)
  return downloadRow(row)
}

async function copyBuffer(row) {
  busy[row.key] = true
  try {
    const text = await bufferStore.revealItem(row.id)
    await navigator.clipboard.writeText(text)
    copiedKey.value = row.key
    setTimeout(() => { if (copiedKey.value === row.key) copiedKey.value = '' }, 2000)
  } catch (err) {
    console.error('Failed to copy buffer entry:', err)
    systemStore.addNotification(t('exchange.decrypt_error'), 'error')
  } finally {
    busy[row.key] = false
  }
}

async function openImage(row) {
  if (thumbs[row.key]) {
    imageOverlay.value = { url: thumbs[row.key], name: displayName(row) }
    return
  }
  busy[row.key] = true
  try {
    const bytes = await postboxStore.fetchPlainBytes(rid.value, row.id)
    const url = URL.createObjectURL(
      new Blob([bytes], { type: MIME_BY_EXT[extOf(displayName(row))] || 'image/png' })
    )
    thumbs[row.key] = url
    imageOverlay.value = { url, name: displayName(row) }
  } catch (err) {
    console.error('Failed to decrypt image:', err)
    systemStore.addNotification(t('exchange.decrypt_error'), 'error')
  } finally {
    busy[row.key] = false
  }
}

async function openText(row) {
  busy[row.key] = true
  try {
    const bytes = await postboxStore.fetchPlainBytes(rid.value, row.id)
    textPreview.value = { name: displayName(row), content: new TextDecoder().decode(bytes) }
  } catch (err) {
    console.error('Failed to decrypt text file:', err)
    systemStore.addNotification(t('exchange.decrypt_error'), 'error')
  } finally {
    busy[row.key] = false
  }
}

async function downloadRow(row) {
  busy[row.key] = true
  try {
    const bytes = await postboxStore.fetchPlainBytes(rid.value, row.id)
    const url = URL.createObjectURL(new Blob([bytes]))
    const a = document.createElement('a')
    a.href = url
    a.download = displayName(row)
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 30000)
  } catch (err) {
    console.error('Failed to download file:', err)
    systemStore.addNotification(t('exchange.decrypt_error'), 'error')
  } finally {
    busy[row.key] = false
  }
}

async function togglePin(row) {
  busy[row.key] = true
  try {
    await bufferStore.togglePin(row.id, !row.pinned)
  } catch (err) {
    console.error('Failed to toggle pin:', err)
    systemStore.addNotification(t('exchange.pin_error'), 'error')
  } finally {
    busy[row.key] = false
  }
}

async function removeRow(row) {
  try {
    if (row.source === 'buffer') {
      await bufferStore.deleteItem(row.id)
    } else {
      await postboxStore.deleteItem(rid.value, row.id)
      if (thumbs[row.key]) {
        URL.revokeObjectURL(thumbs[row.key])
        delete thumbs[row.key]
      }
    }
    delete names[row.key]
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
  const el = e.target
  if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) return
  const dt = e.clipboardData
  if (!dt) return
  const files = Array.from(dt.files || [])
  if (files.length) {
    e.preventDefault()
    await uploadFiles(files.map(nameClipboardFile))
    return
  }
  const text = dt.getData('text/plain')
  if (text && text.trim()) {
    e.preventDefault()
    await pushText(text)
  }
}

function nameClipboardFile(file) {
  if (file.type.startsWith('image/') && (!file.name || file.name === 'image.png')) {
    return new File([file], `shot-${Date.now()}.png`, { type: file.type })
  }
  return file
}

async function uploadFiles(files) {
  if (!rid.value) rid.value = await resolveRid()
  let ok = false
  for (const file of files) {
    try {
      const bytes = new Uint8Array(await file.arrayBuffer())
      await postboxStore.uploadFile(rid.value, file.name || `shot-${Date.now()}.png`, bytes)
      ok = true
      systemStore.addNotification(t('exchange.upload_success', { name: file.name }), 'success')
    } catch (err) {
      console.error('Failed to upload file:', err)
      systemStore.addNotification(t('exchange.upload_error', { name: file.name }), 'error')
    }
  }
  if (ok) {
    await resolveNames()
    pruneThumbs()
    await buildThumbs()
  }
}

async function pushText(text) {
  try {
    await bufferStore.pushItem(text)
    await resolveNames()
    systemStore.addNotification(t('exchange.push_success'), 'success')
  } catch (e) {
    if (e.message && e.message.includes('password')) {
      systemStore.addNotification(t('exchange.no_password'), 'error')
    } else {
      systemStore.addNotification(t('exchange.push_error'), 'error')
    }
  }
}

watch(() => reposStore.currentRepo, async (repo) => {
  if (!repo || repo === rid.value) return
  rid.value = repo
  await postboxStore.fetchItems(repo)
  await resolveNames()
  pruneThumbs()
  await buildThumbs()
})

onActivated(() => {
  window.addEventListener('paste', onPaste)
  refresh()
})

onDeactivated(() => {
  window.removeEventListener('paste', onPaste)
})

onUnmounted(() => {
  window.removeEventListener('paste', onPaste)
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
    return new Date(ts * 1000).toLocaleString('ru-RU')
  } catch {
    return ''
  }
}
</script>

<style scoped>
.exchange-view { display: flex; flex-direction: column; height: 100%; color: var(--text-main); position: relative; }
.exchange-view.drag-over::after {
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
.view-content { padding: 30px; max-width: 900px; margin: 0 auto; width: 100%; }
.subtitle { color: var(--text-secondary); font-size: 13px; margin: 0 0 20px; }

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
  margin-bottom: 20px;
  font-size: 13px;
}

.empty-state { text-align: center; color: var(--text-secondary); padding: 60px 0; font-size: 14px; }

.feed-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 10px;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
}
.feed-item:hover { border-color: var(--accent); }

.row-main { display: flex; align-items: center; gap: 12px; min-width: 0; flex: 1; }
.type-icon { display: flex; align-items: center; color: var(--text-secondary); flex-shrink: 0; }
.type-icon.message { color: var(--accent); }
.type-icon.image { color: var(--success); }
.thumb {
  width: 44px;
  height: 44px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid var(--border-color);
  flex-shrink: 0;
}
.row-text { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.row-name {
  font-size: 14px;
  color: var(--text-bright);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.row-meta { font-size: 12px; color: var(--text-secondary); white-space: nowrap; }
.pin-badge { display: flex; align-items: center; color: var(--warning); flex-shrink: 0; }

.row-actions { display: flex; gap: 8px; flex-shrink: 0; }
.item-btn {
  display: inline-flex; align-items: center; gap: 6px;
  background: transparent;
  border: 1px solid var(--border-color);
  color: var(--text-main);
  border-radius: 5px;
  padding: 5px 10px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s;
}
.item-btn:hover:not(:disabled) { background: rgba(255,255,255,0.05); color: var(--text-bright); }
.item-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.item-btn.active { color: var(--warning); border-color: var(--warning); }
.item-btn.danger:hover { color: var(--error); border-color: var(--error); }

.icon-btn {
  background: none; border: 1px solid var(--border-color); color: var(--text-secondary);
  cursor: pointer; padding: 6px; border-radius: 6px; display: flex; align-items: center; justify-content: center;
  transition: all 0.2s;
}
.icon-btn:hover:not(:disabled) { background: rgba(255,255,255,0.06); color: var(--text-bright); }
.icon-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-clear {
  background: transparent; border: 1px solid var(--border-color); color: var(--text-secondary);
  border-radius: 6px; padding: 6px 12px; font-size: 12px; cursor: pointer; transition: all 0.2s;
}
.btn-clear:hover { color: var(--error); border-color: var(--error); }

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
</style>
