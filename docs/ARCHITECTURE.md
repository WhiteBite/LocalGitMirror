# 🏗️ Архитектура LocalGitMirror v3.2

## Общая схема

```mermaid
graph TB
    subgraph "Work PC"
        W[Developer]
        WG[Git Client]
    end
    
    subgraph "Home PC - LocalGitMirror"
        subgraph "Backend (FastAPI)"
            API[API Server]
            GS[Git Server]
            RM[Repo Manager]
            WS[WebSocket]
            SM[Settings Manager]
            LOG[Logger]
        end
        
        subgraph "Frontend (Vue.js)"
            APP[App.vue]
            DASH[Dashboard]
            FB[File Browser]
            SET[Settings]
            
            subgraph "Components"
                FT[FileTree]
                FV[FileViewer]
                MD[MarkdownRenderer]
                CV[CodeViewer]
                PDF[PDFViewer]
                SL[SystemLog]
            end
            
            subgraph "Stores (Pinia)"
                FS[Files Store]
                RS[Repos Store]
                SS[System Store]
            end
        end
        
        subgraph "Storage"
            BARE[Bare Repos]
            WORK[Workspaces]
            CONF[Settings]
            LOGS[Log Files]
        end
    end
    
    W -->|git push| WG
    WG -->|git://| GS
    GS -->|trigger| RM
    RM -->|sync| WORK
    RM -->|read| BARE
    
    APP --> DASH
    APP --> FB
    APP --> SET
    
    FB --> FT
    FB --> FV
    FV --> MD
    FV --> CV
    FV --> PDF
    
    DASH --> SL
    FB --> SL
    
    FT --> FS
    FV --> FS
    DASH --> RS
    DASH --> SS
    
    API --> RM
    API --> SM
    API --> LOG
    WS --> LOG
    WS --> SL
    
    RM --> WORK
    RM --> BARE
    SM --> CONF
    LOG --> LOGS
    
    style W fill:#3b82f6
    style API fill:#10b981
    style APP fill:#8b5cf6
    style WORK fill:#f59e0b
```

## Структура проекта

```
LocalGitMirror/
├── backend/
│   ├── core/
│   │   ├── git_handler.py       # Git сервер
│   │   ├── repo_manager.py      # Управление репозиториями
│   │   ├── git_utils.py         # Git утилиты
│   │   ├── system_monitor.py    # Мониторинг системы
│   │   ├── logger.py            # NEW: Логирование
│   │   ├── settings_manager.py  # NEW: Настройки
│   │   └── cache_manager.py     # NEW: Кэширование
│   │
│   ├── routers/
│   │   ├── api.py               # REST API
│   │   ├── web.py               # Web страницы
│   │   ├── websocket.py         # NEW: WebSocket
│   │   └── settings.py          # NEW: API настроек
│   │
│   ├── main.py                  # Точка входа
│   └── requirements.txt
│
├── frontend/                    # NEW: Vue.js проект
│   ├── src/
│   │   ├── components/
│   │   │   ├── FileTree.vue
│   │   │   ├── FileViewer.vue
│   │   │   ├── MarkdownRenderer.vue
│   │   │   ├── CodeViewer.vue
│   │   │   ├── PDFViewer.vue
│   │   │   ├── Breadcrumbs.vue
│   │   │   ├── Toolbar.vue
│   │   │   ├── SystemLog.vue
│   │   │   ├── StatusBar.vue
│   │   │   ├── WorkflowBanner.vue
│   │   │   ├── ActionButtons.vue
│   │   │   ├── GitTerminal.vue
│   │   │   ├── QuickStats.vue
│   │   │   ├── GitStatus.vue
│   │   │   ├── DiffViewer.vue
│   │   │   ├── CommitHistory.vue
│   │   │   ├── BranchSelector.vue
│   │   │   ├── SearchBar.vue
│   │   │   └── QuickOpen.vue
│   │   │
│   │   ├── views/
│   │   │   ├── Dashboard.vue
│   │   │   ├── FileBrowser.vue
│   │   │   └── Settings.vue
│   │   │
│   │   ├── stores/
│   │   │   ├── files.js
│   │   │   ├── repos.js
│   │   │   └── system.js
│   │   │
│   │   ├── router/
│   │   │   └── index.js
│   │   │
│   │   ├── App.vue
│   │   └── main.js
│   │
│   ├── package.json
│   ├── vite.config.js
│   └── tailwind.config.js
│
├── storage/
│   ├── *.git/                   # Bare репозитории
│   ├── workspaces/              # Рабочие копии
│   ├── settings.json            # NEW: Настройки
│   └── logs/                    # NEW: Логи
│
├── .env
├── TODO.md
├── TASKS_FOR_AGENTS.md
├── SUMMARY.md
└── README.md
```

## Поток данных

### 1. Git Push (Work → Home)

```mermaid
sequenceDiagram
    participant W as Work PC
    participant G as Git Server
    participant R as Repo Manager
    participant WS as Workspace
    participant L as Logger
    participant UI as Frontend
    
    W->>G: git push
    G->>L: Log: Push received
    G->>R: Trigger sync
    R->>WS: Checkout files
    R->>L: Log: Sync complete
    L->>UI: WebSocket: Update log
    UI->>UI: Refresh file list
```

### 2. Просмотр файла (Home)

```mermaid
sequenceDiagram
    participant U as User
    participant FT as FileTree
    participant API as Backend API
    participant FS as Files Store
    participant FV as FileViewer
    
    U->>FT: Click on file
    FT->>FS: Update selected file
    FS->>API: GET /api/file/view
    API->>API: Read file
    API->>FS: Return content
    FS->>FV: Update content
    FV->>U: Display file
```

### 3. Редактирование и синхронизация (Home → Work)

```mermaid
sequenceDiagram
    participant U as User
    participant UI as Frontend
    participant API as Backend API
    participant R as Repo Manager
    participant G as Git Server
    participant W as Work PC
    
    U->>UI: Click "Open in Editor"
    UI->>API: POST /api/open
    API->>API: Open Cursor/VS Code
    U->>U: Edit files
    U->>UI: Click "Save & Sync"
    UI->>API: POST /api/git/save-and-sync
    API->>R: Commit changes
    R->>G: Push to bare
    W->>G: git pull
    G->>W: Receive changes
```

## Компонентная архитектура (Vue.js)

### Dashboard View

```
Dashboard.vue
├── StatusBar.vue
├── WorkflowBanner.vue
├── ActionButtons.vue
│   ├── Button: Open Cursor
│   ├── Button: File Browser
│   └── Button: Save & Sync
├── GitTerminal.vue
├── QuickStats.vue
│   ├── Files count
│   ├── Changes count
│   └── Last commit
└── SystemLog.vue (optional)
```

### File Browser View

```
FileBrowser.vue
├── Breadcrumbs.vue
├── Toolbar.vue
│   ├── Button: Open in Editor
│   ├── Button: Refresh
│   ├── Button: Copy
│   └── Button: Download
├── Layout (flex)
│   ├── FileTree.vue (sidebar)
│   │   ├── Folder items
│   │   ├── File items
│   │   └── SearchBar.vue
│   └── FileViewer.vue (main)
│       ├── MarkdownRenderer.vue
│       ├── CodeViewer.vue
│       └── PDFViewer.vue
└── SystemLog.vue (collapsible)
```

### Settings View

```
Settings.vue
├── Section: General
│   ├── Default repo
│   ├── Default folder
│   └── Auto sync
├── Section: Git
│   ├── Git port
│   └── Auto start
├── Section: Editor
│   ├── Editor type
│   └── Custom path
├── Section: UI
│   ├── Theme
│   └── Font size
└── Section: Ollama
    ├── URL
    └── Model
```

## State Management (Pinia)

### Files Store

```javascript
{
  state: {
    files: [],           // Список всех файлов
    currentFile: null,   // Текущий открытый файл
    currentFolder: '',   // Текущая папка
    fileContent: null,   // Содержимое файла
    loading: false       // Загрузка
  },
  actions: {
    loadFiles(),
    selectFile(),
    loadFileContent(),
    refreshFiles()
  }
}
```

### Repos Store

```javascript
{
  state: {
    repos: [],           // Список репозиториев
    currentRepo: 'default',
    branches: [],        // Ветки
    currentBranch: 'main'
  },
  actions: {
    loadRepos(),
    selectRepo(),
    loadBranches(),
    checkoutBranch()
  }
}
```

### System Store

```javascript
{
  state: {
    gitRunning: false,   // Статус Git сервера
    status: 'idle',      // idle/processing/ready
    logs: [],            // Системные логи
    settings: {}         // Настройки
  },
  actions: {
    startGit(),
    stopGit(),
    loadSettings(),
    saveSettings(),
    connectWebSocket()
  }
}
```

## API Endpoints

### Существующие

```
GET  /                          # Dashboard
GET  /files                     # File Browser
GET  /api/status                # Статус системы
GET  /api/files                 # Список файлов
GET  /api/file/view             # Просмотр файла
GET  /api/file/pdf              # Просмотр PDF
GET  /api/repos                 # Список репозиториев
POST /api/repos/select          # Выбрать репозиторий
POST /api/git/start             # Запустить Git сервер
POST /api/git/stop              # Остановить Git сервер
POST /api/git/save-and-sync     # Сохранить и синхронизировать
GET  /api/git/changes           # Список изменений
POST /api/system/open-editor    # Открыть редактор
POST /api/chat                  # AI чат
```

### Новые (планируются)

```
# Settings
GET  /api/settings              # Получить настройки
POST /api/settings              # Сохранить настройки
GET  /api/settings/defaults     # Дефолтные значения

# Logs
GET  /api/logs                  # История логов
DELETE /api/logs                # Очистить логи
WS   /ws/logs                   # WebSocket для логов

# Git Extended
GET  /api/git/status            # Статус всех файлов
GET  /api/git/diff              # Diff файла
GET  /api/git/history           # История коммитов
GET  /api/git/blame             # Blame файла
GET  /api/git/branches          # Список веток
POST /api/git/checkout          # Переключить ветку

# Search
GET  /api/search/files          # Поиск файлов
GET  /api/search/content        # Поиск по содержимому
POST /api/search/index          # Переиндексация
```

## Технологии

### Backend
- **FastAPI** - веб-фреймворк
- **Uvicorn** - ASGI сервер
- **WebSocket** - real-time коммуникация
- **Git** - версионный контроль
- **Python 3.10+**

### Frontend
- **Vue.js 3** - UI фреймворк
- **Vite** - сборщик
- **Vue Router** - маршрутизация
- **Pinia** - state management
- **TailwindCSS** - стили
- **TypeScript** - типизация (опционально)

### Библиотеки
- **Marked.js** - Markdown парсер
- **Mermaid.js** - диаграммы
- **Highlight.js** - подсветка кода
- **PDF.js** - PDF рендеринг
- **Fuse.js** - fuzzy search
- **@vscode/codicons** - иконки

## Безопасность

```mermaid
graph LR
    A[Client] -->|HTTPS| B[Web Server]
    A -->|Git Protocol| C[Git Server]
    B -->|Local| D[File System]
    C -->|Local| D
    B -->|WebSocket| E[Logger]
    
    style A fill:#3b82f6
    style B fill:#10b981
    style C fill:#10b981
    style D fill:#f59e0b
    style E fill:#8b5cf6
```

### Меры безопасности
- Локальная сеть (LAN) только
- Нет внешнего доступа
- Файловая система изолирована
- Git протокол без аутентификации (локально)
- WebSocket только для логов (read-only)

## Производительность

### Оптимизации
- **Кэширование** - списки файлов, содержимое
- **Виртуальный скроллинг** - для больших списков
- **Lazy loading** - компоненты и роуты
- **Дебаунс** - для поиска и фильтров
- **Мемоизация** - вычисляемые свойства
- **Сжатие** - gzip для API ответов

### Метрики
- Загрузка страницы: < 1 сек
- Рендеринг списка: < 100ms
- Открытие файла: < 200ms
- WebSocket задержка: < 50ms

---

**Версия**: 3.2.0  
**Дата**: 2026-01-28  
**Статус**: Проектирование
