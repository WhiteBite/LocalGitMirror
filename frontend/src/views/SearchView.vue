<template>
  <div class="h-full flex flex-col bg-gray-900 text-gray-100 p-6">
    <div class="flex justify-between items-center mb-6">
      <h1 class="text-2xl font-bold">Global Search</h1>
    </div>

    <!-- Search Bar -->
    <div class="mb-6 flex gap-2">
      <div class="relative flex-1">
        <input
          v-model="query"
          @keyup.enter="performSearch"
          type="text"
          placeholder="Search code (regex supported)..."
          class="w-full bg-gray-800 border border-gray-700 rounded p-3 pl-10 text-white focus:outline-none focus:border-blue-500"
        />
        <svg
          class="w-5 h-5 absolute left-3 top-3.5 text-gray-500"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
          ></path>
        </svg>
      </div>
      <button
        @click="performSearch"
        :disabled="loading"
        class="bg-blue-600 hover:bg-blue-700 px-6 py-2 rounded font-medium disabled:opacity-50"
      >
        <span v-if="loading">Searching...</span>
        <span v-else>Search</span>
      </button>
    </div>

    <!-- Results -->
    <div class="flex-1 overflow-auto">
      <div v-if="loading" class="flex justify-center items-center h-32">
        <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-500"></div>
      </div>

      <div v-else-if="results.length > 0" class="space-y-4">
        <div class="text-sm text-gray-400 mb-2">
          Found {{ count }} matches (limit 100)
        </div>
        
        <div
          v-for="(match, index) in results"
          :key="index"
          @click="openFile(match.file, match.line)"
          class="bg-gray-800 rounded p-3 cursor-pointer hover:bg-gray-750 border border-transparent hover:border-gray-600 group"
        >
          <div class="flex justify-between text-sm text-gray-400 mb-1">
            <span class="font-mono text-blue-400">{{ match.file }}</span>
            <span class="text-gray-500">Line {{ match.line }}</span>
          </div>
          <pre class="font-mono text-sm overflow-x-auto text-gray-300 bg-gray-900 p-2 rounded">{{ match.content }}</pre>
        </div>
      </div>

      <div v-else-if="hasSearched" class="text-center text-gray-500 mt-10">
        No matches found for "{{ lastQuery }}"
      </div>

      <div v-else class="text-center text-gray-500 mt-10">
        Enter a search term to find code in the current repository.
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue';
import axios from 'axios';

const query = ref('');
const lastQuery = ref('');
const results = ref([]);
const count = ref(0);
const loading = ref(false);
const hasSearched = ref(false);

const performSearch = async () => {
  if (!query.value.trim()) return;
  
  loading.value = true;
  lastQuery.value = query.value;
  results.value = [];
  
  try {
    // Get current repo first? Backend handles default repo if not specified
    const response = await axios.get(`/api/search`, {
      params: { q: query.value }
    });
    
    if (response.data.matches) {
      results.value = response.data.matches;
      count.value = response.data.count;
    }
  } catch (error) {
    console.error("Search failed", error);
    alert("Search failed: " + (error.response?.data?.detail || error.message));
  } finally {
    loading.value = false;
    hasSearched.value = true;
  }
};

const openFile = async (file, line) => {
  try {
    // We can use the editor open endpoint, but we probably want to navigate 
    // to the FileBrowser if we want to stay in-app.
    // For now, let's open in external editor since that's what "open_file" does.
    await axios.post(`/api/editor/open`, null, {
      params: { file }
    });
    // Optional: Toast notification
  } catch (error) {
    console.error("Failed to open file", error);
  }
};
</script>

<style scoped>
.hover\:bg-gray-750:hover {
  background-color: #2d3748;
}
</style>
