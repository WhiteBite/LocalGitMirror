<template>
  <div class="h-full flex flex-col bg-gray-900 text-gray-100 p-6">
    <div class="flex justify-between items-center mb-6">
      <h1 class="text-2xl font-bold">
        {{ t('search.title') }}
        <span class="text-blue-400 ml-2 text-lg font-medium opacity-80">
          [{{ reposStore.currentRepo || '...' }}]
        </span>
      </h1>
    </div>

    <!-- Поисковая строка -->
    <div class="mb-6 flex gap-2">
      <div class="relative flex-1">
        <input
          v-model="query"
          @keyup.enter="performSearch"
          type="text"
          :placeholder="t('search.placeholder')"
          class="w-full bg-gray-800 border border-gray-700 rounded p-3 pl-10 text-white focus:outline-none focus:border-blue-500 transition-all"
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
        class="bg-blue-600 hover:bg-blue-700 px-8 py-2 rounded font-bold disabled:opacity-50 transition-all flex items-center gap-2"
      >
        <span v-if="loading">{{ t('search.searching') }}</span>
        <span v-else>{{ t('search.button') }}</span>
      </button>
    </div>

    <!-- Результаты -->
    <div class="flex-1 overflow-auto">
      <div v-if="loading" class="flex justify-center items-center h-32">
        <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-500"></div>
      </div>

      <div v-else-if="results.length > 0" class="space-y-4">
        <div class="text-sm text-gray-400 mb-2 flex items-center gap-2">
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
          {{ t('search.found', { count }) }}
        </div>
        
        <div
          v-for="(match, index) in results"
          :key="index"
          @click="openFile(match.file, match.line)"
          class="bg-gray-800 rounded p-3 cursor-pointer hover:bg-gray-750 border border-transparent hover:border-gray-600 group transition-all"
        >
          <div class="flex justify-between text-sm text-gray-400 mb-1">
            <span class="font-mono text-blue-400 group-hover:text-blue-300">{{ match.file }}</span>
            <span class="text-gray-500">{{ t('search.line') }} {{ match.line }}</span>
          </div>
          <pre class="font-mono text-sm overflow-x-auto text-gray-300 bg-gray-900 p-2 rounded border border-gray-800">{{ match.content }}</pre>
        </div>
      </div>

      <div v-else-if="hasSearched" class="text-center py-20 bg-gray-800/20 rounded-lg border border-dashed border-gray-700">
        <div class="text-4xl mb-4 text-gray-600">🔍</div>
        <p class="text-gray-400">{{ t('search.nothing_found') }} "{{ lastQuery }}"</p>
      </div>

      <div v-else class="text-center py-20 text-gray-500">
        <div class="text-4xl mb-4 opacity-30">📂</div>
        {{ t('search.empty_state') }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useReposStore } from '@/stores/repos';
import axios from 'axios';

const { t } = useI18n();
const reposStore = useReposStore();
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
    const response = await axios.get(`/api/search`, {
      params: { q: query.value }
    });
    
    if (response.data.matches) {
      results.value = response.data.matches;
      count.value = response.data.count;
    }
  } catch (error) {
    console.error("Search failed", error);
  } finally {
    loading.value = false;
    hasSearched.value = true;
  }
};

const openFile = async (file, line) => {
  try {
    await axios.post(`/api/editor/open`, null, {
      params: { file }
    });
  } catch (error) {}
};
</script>

<style scoped>
.hover\:bg-gray-750:hover {
  background-color: #2d3748;
}
</style>
