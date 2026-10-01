import { defineStore } from 'pinia'
import { ref } from 'vue'
import axios from 'axios'

// Loopback-plaintext postbox: the SPA is a trusted HOME-host client.
export const usePostboxStore = defineStore('postbox', () => {
  const items = ref([])
  const loading = ref(false)
  const error = ref(null)

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
    const res = await axios.get('/api/documents/attachment-get', {
      params: { rid, id },
      responseType: 'arraybuffer'
    })
    return new Uint8Array(res.data)
  }

  async function revealName(item) {
    return { side: '', text: item.path || '' }
  }

  async function uploadFile(rid, name, plainBytes) {
    const form = new FormData()
    form.append('rid', rid)
    form.append('path', name)
    form.append('plain_size', String(plainBytes.length))
    form.append('attachment', new Blob([plainBytes]), 'data.bin')
    const res = await axios.post('/api/documents/attachment-upload', form)
    await fetchItems(rid)
    return res.data
  }

  async function deleteItem(rid, id) {
    await axios.delete('/api/documents/attachment-ack', { params: { rid, id } })
    items.value = items.value.filter(it => it.id !== id)
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
