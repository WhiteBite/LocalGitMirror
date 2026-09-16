import { defineStore } from 'pinia'
import { ref } from 'vue'
import axios from 'axios'
import { encryptFileBytes, decryptFileBytes, encryptText, decryptText } from '@/lib/bundleCrypto'
import { useBufferStore } from '@/stores/buffer'

/**
 * Repo-scoped encrypted file postbox (/api/documents/attachment-*).
 *
 * The server stores opaque containers; encryption/decryption happens here with
 * the shared sync password (same one the buffer store resolves). Wire path is
 * an opaque "x/<random8>" token; the real file name travels as `path_enc`
 * (bundle-v2 ciphertext, base64).
 */
export const usePostboxStore = defineStore('postbox', () => {
  const items = ref([])
  const loading = ref(false)
  const error = ref(null)

  async function syncPassword() {
    const password = await useBufferStore().ensurePassword()
    if (!password) throw new Error('Sync password not configured')
    return password
  }

  async function fetchItems(rid) {
    loading.value = true
    error.value = null
    try {
      const res = await axios.get('/api/documents/attachment-list', { params: { rid } })
      items.value = res.data.items || []
    } catch (err) {
      error.value = err.response?.data?.detail || 'Failed to load postbox'
      items.value = []
      console.error('Error loading postbox:', err)
    } finally {
      loading.value = false
    }
  }

  async function fetchPlainBytes(rid, id) {
    const password = await syncPassword()
    const res = await axios.get('/api/documents/attachment-get', {
      params: { rid, id },
      responseType: 'arraybuffer'
    })
    return decryptFileBytes(new Uint8Array(res.data), password)
  }

  async function revealName(item) {
    if (!item.path_enc) return item.path || ''
    try {
      return await decryptText(item.path_enc, await syncPassword())
    } catch {
      return item.path || ''
    }
  }

  async function uploadFile(rid, name, plainBytes) {
    const password = await syncPassword()
    const encrypted = await encryptFileBytes(plainBytes, password)
    const form = new FormData()
    form.append('rid', rid)
    form.append('path', `x/${randomToken()}`)
    form.append('plain_size', '0')
    form.append('path_enc', await encryptText(name, password))
    form.append('attachment', new Blob([encrypted]), 'data.bin')
    const res = await axios.post('/api/documents/attachment-upload', form)
    await fetchItems(rid)
    return res.data
  }

  async function deleteItem(rid, id) {
    await axios.delete('/api/documents/attachment-ack', { params: { rid, id } })
    items.value = items.value.filter(it => it.id !== id)
  }

  function randomToken() {
    return Array.from(crypto.getRandomValues(new Uint8Array(4)))
      .map(b => b.toString(16).padStart(2, '0'))
      .join('')
  }

  return {
    items,
    loading,
    error,
    fetchItems,
    fetchPlainBytes,
    revealName,
    uploadFile,
    deleteItem
  }
})
