import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import axios from 'axios'
import { encryptText, decryptBytes, decryptText } from '@/lib/bundleCrypto'

/**
 * Cross-machine clipboard buffer.
 *
 * The server stores only opaque ciphertext (encrypted here, client-side, with
 * the same SYNC_PASSWORD the plugin uses). The list endpoint returns metadata
 * plus an optional plaintext `hint` (a user-supplied label) so the UI can show
 * rows without decrypting everything. Bodies are decrypted on demand.
 */
export const useBufferStore = defineStore('buffer', () => {
  const items = ref([])
  const limits = ref({ max_items: 50, max_size: 1024 * 1024, ttl: 86400 })
  const loading = ref(false)
  const error = ref(null)
  const syncPassword = ref('')

  const hasPassword = computed(() => !!syncPassword.value)

  async function ensurePassword() {
    if (syncPassword.value) return syncPassword.value
    try {
      const res = await axios.get('/api/connection-info')
      syncPassword.value = res.data.sync_password || ''
    } catch (err) {
      console.error('Failed to fetch sync password:', err)
    }
    return syncPassword.value
  }

  async function fetchItems() {
    loading.value = true
    error.value = null
    try {
      const res = await axios.get('/api/buffer')
      items.value = res.data.items || []
      if (res.data.limits) limits.value = res.data.limits
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to load buffer'
      console.error('Error loading buffer:', err)
    } finally {
      loading.value = false
    }
  }

  /**
   * Encrypt `text` and push it. The preview label travels as `hint_enc`
   * (bundle-v2 ciphertext, base64) — the plaintext `hint` field is legacy and
   * must never be sent. If `label` is omitted, we derive one from the first
   * line (first 80 chars), matching the plugin's buildHint().
   */
  async function pushItem(text, label = '') {
    error.value = null
    const password = await ensurePassword()
    if (!password) {
      error.value = 'no_password'
      throw new Error('Sync password not configured')
    }
    const ciphertext_b64 = await encryptText(text, password)
    const hint = label || (text.split('\n')[0] || '').trim().slice(0, 80)
    const payload = { ciphertext_b64 }
    if (hint) payload.hint_enc = await encryptText(hint, password)
    const res = await axios.post('/api/buffer', payload)
    await fetchItems()
    return res.data
  }

  /**
   * Resolve the display label of a list entry: decrypt `hint_enc` when present,
   * fall back to the legacy plaintext `hint`. Never throws.
   */
  async function revealHint(item) {
    if (!item.hint_enc) return item.hint || ''
    const password = await ensurePassword()
    if (!password) return item.hint || ''
    try {
      return await decryptText(item.hint_enc, password)
    } catch {
      return item.hint || ''
    }
  }

  async function togglePin(id, pinned) {
    await axios.post(`/api/buffer/${id}/pin`, { pinned })
    const item = items.value.find(it => it.id === id)
    if (item) item.pinned = pinned
  }

  /**
   * Fetch + decrypt a single entry, returning its plaintext.
   */
  async function revealItem(id) {
    const password = await ensurePassword()
    if (!password) throw new Error('Sync password not configured')
    const res = await axios.get(`/api/buffer/${id}`, { responseType: 'arraybuffer' })
    return decryptBytes(new Uint8Array(res.data), password)
  }

  async function deleteItem(id) {
    await axios.delete(`/api/buffer/${id}`)
    items.value = items.value.filter(it => it.id !== id)
  }

  async function clearAll() {
    await axios.delete('/api/buffer')
    items.value = []
  }

  return {
    items,
    limits,
    loading,
    error,
    syncPassword,
    hasPassword,
    ensurePassword,
    fetchItems,
    pushItem,
    revealItem,
    revealHint,
    togglePin,
    deleteItem,
    clearAll
  }
})
