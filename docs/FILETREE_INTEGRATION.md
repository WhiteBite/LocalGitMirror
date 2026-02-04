# FileTree Component Integration Guide

Руководство по интеграции компонента FileTree в LocalGitMirror.

## Созданные файлы

### Основные компоненты
1. **`frontend/src/components/FileTree.vue`** - Главный компонент дерева файлов
2. **`frontend/src/components/TreeNode.vue`** - Рекурсивный компонент узла дерева
3. **`frontend/src/components/FileTree.example.vue`** - Пример использования
4. **`frontend/src/views/FileBrowserWithTree.vue`** - Интегрированный FileBrowser с деревом

### Документация
5. **`frontend/src/components/FileTree.README.md`** - Полная документация компонента

### Тесты
6. **`frontend/src/components/__tests__/FileTree.spec.js`** - Тесты FileTree
7. **`frontend/src/components/__tests__/TreeNode.spec.js`** - Тесты TreeNode

## Быстрый старт

### 1. Базовое использование

```vue
<template>
  <FileTree
    :files="files"
    :selected-file="selectedFile"
    :search-query="searchQuery"
    @file-select="handleFileSelect"
    @folder-toggle="handleFolderToggle"
  />
</template>

<script setup>
import { ref } from 'vue'
import FileTree from '@/components/FileTree.vue'

const files = ref([
  { path: 'README.md', name: 'README.md', size: 1234, modified: '2026-01-28T12:00:00' },
  { path: 'src/main.js', name: 'main.js', size: 890, modified: '2026-01-28T12:00:00' }
])

const selectedFile = ref('')
const searchQuery = ref('')

const handleFileSelect = (path) => {
  selectedFile.value = path
  // Загрузить содержимое файла
}

const handleFolderToggle = (path) => {
  console.log('Folder toggled:', path)
}
</script>
```

### 2. Интеграция с существующим FileBrowser

#### Вариант A: Замена существующего FileBrowser

Замените `frontend/src/views/FileBrowser.vue` на `FileBrowserWithTree.vue`:

```bash
# Сохраните старую версию
mv frontend/src/views/FileBrowser.vue frontend/src/views/FileBrowser.old.vue

# Используйте новую версию
mv frontend/src/views/FileBrowserWithTree.vue frontend/src/views/FileBrowser.vue
```

#### Вариант B: Добавление переключателя режимов

Добавьте в существующий `FileBrowser.vue`:

```vue
<template>
  <div class="file-browser">
    <!-- Кнопка переключения режима -->
    <button @click="toggleViewMode" class="btn-toggle">
      {{ viewMode === 'tree' ? 'List View' : 'Tree View' }}
    </button>

    <!-- Древовидный режим -->
    <FileTree
      v-if="viewMode === 'tree'"
      :files="filesStore.files"
      :selected-file="selectedFile"
      :search-query="searchQuery"
      @file-select="handleFileSelect"
    />

    <!-- Режим списка (существующий код) -->
    <div v-else>
      <!-- Ваш существующий код списка -->
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import FileTree from '@/components/FileTree.vue'

const viewMode = ref('tree')

const toggleViewMode = () => {
  viewMode.value = viewMode.value === 'tree' ? 'list' : 'tree'
}
</script>
```

### 3. Настройка Store

Убедитесь, что ваш `files` store возвращает данные в правильном формате:

```javascript
// frontend/src/stores/files.js
export const useFilesStore = defineStore('files', {
  state: () => ({
    files: [],
    loading: false,
    error: null
  }),

  actions: {
    async fetchFiles(path = '/') {
      this.loading = true
      this.error = null
      
      try {
        const response = await fetch(`/api/repos/${owner}/${repo}/files?path=${path}`)
        const data = await response.json()
        
        // Преобразуйте данные в нужный формат
        this.files = data.map(file => ({
          path: file.path,           // Полный путь: "src/components/App.vue"
          name: file.name,           // Имя файла: "App.vue"
          size: file.size,           // Размер в байтах
          modified: file.modified    // ISO дата
        }))
      } catch (error) {
        this.error = error.message
      } finally {
        this.loading = false
      }
    }
  }
})
```

## Интеграция с API

### Формат данных API

FileTree ожидает массив файлов в следующем формате:

```typescript
interface FileItem {
  path: string        // Полный путь: "src/components/App.vue"
  name: string        // Имя файла: "App.vue"
  size: number        // Размер в байтах
  modified: string    // ISO дата: "2026-01-28T12:00:00"
}
```

### Пример API endpoint

```python
# app.py
@app.route('/api/repos/<owner>/<repo>/files')
def get_files(owner, repo):
    path = request.args.get('path', '')
    
    # Получить список файлов из репозитория
    files = []
    repo_path = os.path.join(REPOS_DIR, owner, repo)
    
    for root, dirs, filenames in os.walk(repo_path):
        for filename in filenames:
            file_path = os.path.join(root, filename)
            rel_path = os.path.relpath(file_path, repo_path)
            
            stat = os.stat(file_path)
            files.append({
                'path': rel_path.replace('\\', '/'),
                'name': filename,
                'size': stat.st_size,
                'modified': datetime.fromtimestamp(stat.st_mtime).isoformat()
            })
    
    return jsonify(files)
```

### Загрузка содержимого файла

```javascript
async function loadFileContent(path) {
  try {
    const response = await fetch(`/api/repos/${owner}/${repo}/files/${path}`)
    const content = await response.text()
    return content
  } catch (error) {
    console.error('Failed to load file:', error)
    throw error
  }
}
```

## Функциональность

### 1. Древовидная структура

FileTree автоматически преобразует плоский список файлов в дерево:

```javascript
// Входные данные
[
  { path: 'src/main.js', ... },
  { path: 'src/components/App.vue', ... }
]

// Результат
[
  {
    type: 'folder',
    name: 'src',
    children: [
      { type: 'file', name: 'main.js', ... },
      {
        type: 'folder',
        name: 'components',
        children: [
          { type: 'file', name: 'App.vue', ... }
        ]
      }
    ]
  }
]
```

### 2. Иконки файлов

Автоматическое определение иконок по расширению:

- 📝 `.md` - Markdown
- 🐍 `.py` - Python
- 📜 `.js` - JavaScript
- 📘 `.ts` - TypeScript
- 📋 `.json` - JSON
- 💚 `.vue` - Vue
- 🌐 `.html` - HTML
- 🎨 `.css`, `.scss` - Стили
- 📄 Остальные файлы

### 3. Поиск и фильтрация

```vue
<template>
  <div>
    <input v-model="searchQuery" placeholder="Search..." />
    <FileTree :search-query="searchQuery" :files="files" />
  </div>
</template>
```

При поиске:
- Фильтруются файлы по имени и пути
- Папки с совпадениями автоматически разворачиваются
- Пустые папки скрываются

### 4. Выделение активного файла

```vue
<FileTree
  :files="files"
  :selected-file="currentFile"
  @file-select="currentFile = $event"
/>
```

### 5. Счетчик файлов

Каждая папка показывает количество файлов внутри:

```
📂 src (15)
  📂 components (8)
  📜 main.js
```

## Стилизация

### Темная тема (по умолчанию)

```css
.file-tree {
  background: #1f2937;  /* bg-gray-800 */
  color: #d1d5db;       /* text-gray-300 */
}

.file-node:hover {
  background: #374151;  /* bg-gray-700 */
}

.file-node.is-selected {
  background: #1e3a8a;  /* bg-blue-900 */
  border-left: 4px solid #3b82f6;  /* border-blue-500 */
}
```

### Кастомизация

Переопределите стили в вашем компоненте:

```vue
<style>
/* Изменить цвет активного файла */
.file-node.is-selected {
  @apply bg-green-900 border-green-500;
}

/* Изменить hover эффект */
.folder-node:hover,
.file-node:hover {
  @apply bg-gray-600;
}

/* Изменить размер иконок */
.file-icon,
.folder-icon {
  @apply text-xl;
}
</style>
```

## Расширенные возможности

### 1. Контекстное меню

```vue
<template>
  <FileTree
    :files="files"
    @file-select="handleFileSelect"
    @contextmenu.prevent="showContextMenu"
  />
  
  <ContextMenu
    v-if="contextMenuVisible"
    :x="contextMenuX"
    :y="contextMenuY"
    :items="contextMenuItems"
    @close="contextMenuVisible = false"
  />
</template>

<script setup>
const contextMenuVisible = ref(false)
const contextMenuX = ref(0)
const contextMenuY = ref(0)

const showContextMenu = (event) => {
  contextMenuX.value = event.clientX
  contextMenuY.value = event.clientY
  contextMenuVisible.value = true
}

const contextMenuItems = [
  { label: 'Download', action: downloadFile },
  { label: 'Copy Path', action: copyPath },
  { label: 'Open in New Tab', action: openInNewTab }
]
</script>
```

### 2. Drag & Drop

```vue
<template>
  <FileTree
    :files="files"
    @file-select="handleFileSelect"
    @dragstart="handleDragStart"
    @drop="handleDrop"
  />
</template>

<script setup>
const handleDragStart = (event, file) => {
  event.dataTransfer.setData('file-path', file.path)
}

const handleDrop = (event, targetFolder) => {
  const filePath = event.dataTransfer.getData('file-path')
  // Переместить файл в целевую папку
  moveFile(filePath, targetFolder)
}
</script>
```

### 3. Виртуальный скроллинг (для больших репозиториев)

Для репозиториев с >1000 файлов рекомендуется добавить виртуальный скроллинг:

```bash
npm install vue-virtual-scroller
```

```vue
<template>
  <RecycleScroller
    :items="flattenedTree"
    :item-size="32"
    key-field="path"
  >
    <template #default="{ item }">
      <TreeNode :node="item" />
    </template>
  </RecycleScroller>
</template>

<script setup>
import { RecycleScroller } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'
</script>
```

## Тестирование

### Запуск тестов

```bash
# Все тесты
npm run test

# Только FileTree тесты
npm run test FileTree

# С покрытием
npm run test:coverage
```

### Пример теста

```javascript
import { mount } from '@vue/test-utils'
import FileTree from '@/components/FileTree.vue'

describe('FileTree', () => {
  it('renders files correctly', () => {
    const files = [
      { path: 'README.md', name: 'README.md', size: 1234, modified: '2026-01-28' }
    ]
    const wrapper = mount(FileTree, { props: { files } })
    expect(wrapper.text()).toContain('README.md')
  })
})
```

## Производительность

### Текущая версия

- ✅ Подходит для репозиториев до ~1000 файлов
- ✅ Быстрое построение дерева (O(n log n))
- ✅ Эффективная фильтрация

### Оптимизация для больших репозиториев

1. **Виртуальный скроллинг** - рендерить только видимые элементы
2. **Ленивая загрузка** - загружать содержимое папок по требованию
3. **Пагинация** - ограничить количество отображаемых файлов
4. **Web Workers** - построение дерева в фоновом потоке

## Troubleshooting

### Проблема: Файлы не отображаются

**Решение:** Проверьте формат данных

```javascript
// ✅ Правильно
{ path: 'src/main.js', name: 'main.js', size: 890, modified: '2026-01-28' }

// ❌ Неправильно
{ filepath: 'src/main.js' }  // Неверное имя поля
```

### Проблема: Папки не разворачиваются

**Решение:** Убедитесь, что обрабатывается событие `folder-toggle`

```vue
<FileTree @folder-toggle="handleFolderToggle" />
```

### Проблема: Поиск не работает

**Решение:** Проверьте, что `searchQuery` передается как prop

```vue
<FileTree :search-query="searchQuery" />
```

### Проблема: Медленная работа с большими репозиториями

**Решение:** Добавьте виртуальный скроллинг или пагинацию

## Roadmap

### Версия 1.1
- [ ] Виртуальный скроллинг
- [ ] Контекстное меню
- [ ] Drag & Drop

### Версия 1.2
- [ ] Ленивая загрузка папок
- [ ] Сохранение состояния в localStorage
- [ ] Горячие клавиши (стрелки, Enter, Escape)

### Версия 1.3
- [ ] Множественный выбор файлов
- [ ] Bulk операции (скачать, удалить)
- [ ] История навигации

## Поддержка

Если у вас возникли вопросы или проблемы:

1. Проверьте [FileTree.README.md](./src/components/FileTree.README.md)
2. Посмотрите [FileTree.example.vue](./src/components/FileTree.example.vue)
3. Запустите тесты: `npm run test FileTree`
4. Создайте issue в репозитории

## Лицензия

MIT
