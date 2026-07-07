<template>
  <div class="buffer-view">
    <header class="view-header flex justify-between items-center">
      <h1>{{ t('buffer.title') }}</h1>
      <div class="header-actions">
        <button class="icon-btn" :title="t('common.refresh')" :disabled="bufferStore.loading" @click="refresh">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2"><path d="M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15"/></svg>
        </button>
        <button v-if="bufferStore.items.length" class="btn-clear" @click="clearAll">{{ t('buffer.clear_all') }}</button>
      </div>
    </header>

    <div class="view-content">
      <p class="subtitle">{{ t('buffer.subtitle') }}</p>

      <div v-if="bufferStore.error === 'no_password'" class="alert-card">
        {{ t('buffer.no_password') }}
      </div>

      <!-- Composer -->
      <section class="composer-card">
        <textarea
          v-model="draft"
          class="composer-input"
          :placeholder="t('buffer.placeholder')"
          rows="6"
        ></textarea>
        <div class="composer-row">
          <input v-model="label" class="label-input" :placeholder="t('buffer.label_placeholder')" />
          <div class="composer-meta">
            <span :class="{ 'over-limit': draftBytes > bufferStore.limits.max_size }">
              {{ formatBytes(draftBytes) }} / {{ formatBytes(bufferStore.limits.max_size) }}
            </span>
            <button
              class="btn-push"
              :disabled="!canPush"
              @click="push"
            >
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
              {{ pushing ? t('buffer.pushing') : t('buffer.push') }}
            </button>
          </div>
        </div>
      </section>

      <!-- List -->
      <section class="list-section">
        <div class="list-head">
          <span>{{ t('buffer.entries', { count: bufferStore.items.length, max: bufferStore.limits.max_items }) }}</span>
          <span class="ttl-hint">{{ t('buffer.ttl_hint', { hours: Math.round(bufferStore.limits.ttl / 3600) }) }}</span>
        </div>

        <div v-if="bufferStore.items.length === 0" class="empty-state">
          {{ t('buffer.empty') }}
        </div>

        <div v-for="item in bufferStore.items" :key="item.id" class="buffer-item">
          <div class="item-head">
            <span class="item-hint">{{ item.hint || t('buffer.encrypted') }}</span>
            <div class="item-meta">
              <span>{{ formatBytes(item.size) }}</span>
              <span>{{ formatTime(item.ts) }}</span>
            </div>
          </div>

          <div v-if="revealed[item.id] !== undefined" class="item-body">
            <pre>{{ revealed[item.id] }}</pre>
          </div>

          <div class="item-actions">
            <button class="item-btn" :disabled="busy[item.id]" @click="copy(item)">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
              {{ copiedId === item.id ? t('buffer.copied') : t('buffer.copy') }}
            </button>
            <button class="item-btn" :disabled="busy[item.id]" @click="toggleReveal(item)">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
              {{ revealed[item.id] !== undefined ? t('buffer.hide') : t('buffer.reveal') }}
            </button>
            <button class="item-btn danger" @click="remove(item)">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
              {{ t('common.delete') }}
            </button>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { useBufferStore } from '@/stores/buffer'
import { useSystemStore } from '@/stores/system'

const { t } = useI18n()
const bufferStore = useBufferStore()
const systemStore = useSystemStore()

const draft = ref('')
const label = ref('')
const pushing = ref(false)
const revealed = reactive({})
const busy = reactive({})
const copiedId = ref('')

const draftBytes = computed(() => new TextEncoder().encode(draft.value).length)
const canPush = computed(() =>
  draft.value.trim().length > 0 &&
  !pushing.value &&
  draftBytes.value <= bufferStore.limits.max_size
)

onMounted(async () => {
  await bufferStore.ensurePassword()
  await bufferStore.fetchItems()
})

async function refresh() {
  await bufferStore.fetchItems()
}

async function push() {
  if (!canPush.value) return
  pushing.value = true
  try {
    await bufferStore.pushItem(draft.value, label.value.trim())
    draft.value = ''
    label.value = ''
    systemStore.addNotification(t('buffer.push_success'), 'success')
  } catch (e) {
    if (e.message && e.message.includes('password')) {
      systemStore.addNotification(t('buffer.no_password'), 'error')
    } else {
      systemStore.addNotification(t('buffer.push_error'), 'error')
    }
  } finally {
    pushing.value = false
  }
}

async function copy(item) {
  busy[item.id] = true
  try {
    const text = await bufferStore.revealItem(item.id)
    await navigator.clipboard.writeText(text)
    copiedId.value = item.id
    setTimeout(() => { if (copiedId.value === item.id) copiedId.value = '' }, 2000)
  } catch (e) {
    systemStore.addNotification(t('buffer.decrypt_error'), 'error')
  } finally {
    busy[item.id] = false
  }
}

async function toggleReveal(item) {
  if (revealed[item.id] !== undefined) {
    delete revealed[item.id]
    return
  }
  busy[item.id] = true
  try {
    revealed[item.id] = await bufferStore.revealItem(item.id)
  } catch (e) {
    systemStore.addNotification(t('buffer.decrypt_error'), 'error')
  } finally {
    busy[item.id] = false
  }
}

async function remove(item) {
  try {
    await bufferStore.deleteItem(item.id)
    delete revealed[item.id]
  } catch (e) {
    systemStore.addNotification(t('buffer.delete_error'), 'error')
  }
}

async function clearAll() {
  if (!confirm(t('buffer.clear_confirm'))) return
  try {
    await bufferStore.clearAll()
  } catch (e) {
    systemStore.addNotification(t('buffer.delete_error'), 'error')
  }
}

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
.buffer-view { display: flex; flex-direction: column; height: 100%; color: var(--text-main); }
.view-header { padding: 20px 30px; border-bottom: 1px solid var(--border-color); background: var(--bg-primary); }
.view-header h1 { margin: 0; font-size: 20px; font-weight: 500; color: var(--text-bright); }
.header-actions { display: flex; align-items: center; gap: 12px; }
.view-content { padding: 30px; max-width: 900px; margin: 0 auto; width: 100%; }
.subtitle { color: var(--text-secondary); font-size: 13px; margin: 0 0 20px; }

.alert-card {
  background: rgba(204, 167, 0, 0.1);
  border: 1px solid rgba(204, 167, 0, 0.3);
  color: var(--warning);
  border-radius: 8px;
  padding: 12px 16px;
  margin-bottom: 20px;
  font-size: 13px;
}

.composer-card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 28px;
}
.composer-input {
  width: 100%;
  box-sizing: border-box;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  color: var(--text-main);
  font-family: ui-monospace, Menlo, Consolas, monospace;
  font-size: 13px;
  padding: 12px;
  resize: vertical;
  outline: none;
}
.composer-input:focus { border-color: var(--accent); }
.composer-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  flex-wrap: wrap;
}
.label-input {
  flex: 1;
  min-width: 180px;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  color: var(--text-main);
  font-size: 13px;
  padding: 8px 12px;
  outline: none;
}
.label-input:focus { border-color: var(--accent); }
.composer-meta { display: flex; align-items: center; gap: 14px; font-size: 12px; color: var(--text-secondary); }
.over-limit { color: var(--error); font-weight: 600; }
.btn-push {
  display: inline-flex; align-items: center; gap: 8px;
  background: var(--accent); color: #fff;
  border: none; border-radius: 6px;
  padding: 8px 18px; font-size: 13px; font-weight: 600;
  cursor: pointer; transition: filter 0.2s, opacity 0.2s;
}
.btn-push:hover:not(:disabled) { filter: brightness(1.15); }
.btn-push:disabled { opacity: 0.45; cursor: not-allowed; }

.list-section { }
.list-head {
  display: flex; justify-content: space-between; align-items: center;
  font-size: 12px; text-transform: uppercase; letter-spacing: 0.5px;
  color: var(--text-secondary); margin-bottom: 12px;
}
.ttl-hint { text-transform: none; letter-spacing: 0; opacity: 0.8; }
.empty-state { text-align: center; color: var(--text-secondary); padding: 40px 0; font-size: 14px; }

.buffer-item {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 12px;
}
.item-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.item-hint { font-size: 14px; color: var(--text-bright); font-weight: 500; word-break: break-word; }
.item-meta { display: flex; gap: 14px; font-size: 12px; color: var(--text-secondary); white-space: nowrap; }
.item-body {
  margin-top: 10px;
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 10px;
  max-height: 320px;
  overflow: auto;
}
.item-body pre { margin: 0; font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; color: var(--text-main); white-space: pre-wrap; word-break: break-word; }
.item-actions { display: flex; gap: 8px; margin-top: 12px; }
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
.item-btn.danger:hover { color: var(--error); border-color: var(--error); }

.icon-btn {
  background: none; border: 1px solid var(--border-color); color: var(--text-secondary);
  cursor: pointer; padding: 6px; border-radius: 6px; display: flex; align-items: center; justify-content: center;
  transition: all 0.2s;
}
.icon-btn:hover:not(:disabled) { background: rgba(255,255,255,0.06); color: var(--text-bright); }
.btn-clear {
  background: transparent; border: 1px solid var(--border-color); color: var(--text-secondary);
  border-radius: 6px; padding: 6px 12px; font-size: 12px; cursor: pointer; transition: all 0.2s;
}
.btn-clear:hover { color: var(--error); border-color: var(--error); }
</style>
