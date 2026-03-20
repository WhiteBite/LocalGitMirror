# 🏗️ Архитектура LocalGitMirror v3.2

## Общая схема

```mermaid
graph TB
    subgraph "Рабочий ПК"
        W[Разработчик]
        WG[Git клиент]
    end
    
    subgraph "Домашний ПК - LocalGitMirror"
        subgraph "Backend (FastAPI)"
            API[API сервер]
            GS[Git сервер]
            RM[Менеджер репозиториев]
            WS[WebSocket]
            SM[Менеджер настроек]
            LOG[Логгер]
        end
        
        subgraph "Frontend (Vue.js)"
            APP[App.vue]
            DASH[Панель управления]
            FB[Браузер файлов]
            SET[Настройки]
            
            subgraph "Компоненты"
                FT[FileTree]
                FV[FileViewer]
                MD[MarkdownRenderer]
                CV[CodeViewer]
                PDF[PDFViewer]
                SL[Системный лог]
            end
            
            subgraph "Хранилища (Pinia)"
                FS[Хранилище файлов]
                RS[Хранилище репозиториев]
                SS[Системное хранилище]
            end
        end
        
        subgraph "Хранилище"
            BARE[Пустые репозитории]
            WORK[Рабочие копии]
            CONF[Настройки]
            LOGS[Файлы логов]
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
│   │   ├── logger.py            # НОВОЕ: Логирование
│   │   ├── settings_manager.py  # НОВОЕ: Настройки
│   │   └── cache_manager.py     # НОВОЕ: Кэширование
│   │
│   ├── routers/
│   │   ├── api.py               # REST API
│   │   ├── web.py               # Web страницы
│   │   ├── websocket.py         # НОВОЕ: WebSocket
│   │   └── settings.py          # НОВОЕ: API настроек
│   │
│   ├── main.py                  # Точка входа
│   └── requirements.txt
│
├── frontend/                    # НОВОЕ: Vue.js проект
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
│   ├── *.git/                   # Пустые репозитории
│   ├── workspaces/              # Рабочие копии
│   ├── settings.json            # НОВОЕ: Настройки
│   └── logs/                    # НОВОЕ: Логи
│
├── .env
├── TODO.md
├── TASKS_FOR_AGENTS.md
├── SUMMARY.md
└── README.md
```

## Поток данных

### 1. Git Push (Работа → Домой)

```mermaid
sequenceDiagram
    participant W as Рабочий ПК
    participant G as Git сервер
    participant R as Менеджер репозиториев
    participant WS as Рабочая копия
    participant L as Логгер
    participant UI as Frontend
    
    W->>G: git push
    G->>L: Log: Push получен
    G->>R: Триггер синхронизации
    R->>WS: Checkout файлов
    R->>L: Log: Синхронизация завершена
    L->>UI: WebSocket: Обновление лога
    UI->>UI: Обновление списка файлов
```

### 2. Просмотр файла (Домой)

```mermaid
sequenceDiagram
    participant U as Пользователь
    participant FT as FileTree
    participant API as Backend API
    participant FS as Хранилище файлов
    participant FV as FileViewer
    
    U->>FT: Клик на файл
    FT->>FS: Обновление выбранного файла
    FS->>API: GET /api/file/view
    API->>API: Чтение файла
    API->>FS: Возврат содержимого
    FS->>FV: Обновление содержимого
    FV->>U: Отображение файла
```

### 3. Редактирование и синхронизация (Домой → Работа)

```mermaid
sequenceDiagram
    participant U as Пользователь
    participant UI as Frontend
    participant API as Backend API
    participant R as Менеджер репозиториев
    participant G as Git сервер
    participant W as Рабочий ПК
    
    U->>UI: Клик "Открыть в редакторе"
    UI->>API: POST /api/open
    API->>API: Открытие Cursor/VS Code
    U->>U: Редактирование файлов
    U->>UI: Клик "Сохранить и синхронизировать"
    UI->>API: POST /api/git/save-and-sync
    API->>R: Фиксация изменений
    R->>G: Push в пустой репозиторий
    W->>G: git pull
    G->>W: Получение изменений
```

## Компонентная архитектура (Vue.js)

### Представление панели управления

```
Dashboard.vue
├── StatusBar.vue
├── WorkflowBanner.vue
├── ActionButtons.vue
│   ├── Button: Открыть Cursor
│   ├── Button: Браузер файлов
│   └── Button: Сохранить и синхронизировать
├── GitTerminal.vue
├── QuickStats.vue
│   ├── Количество файлов
│   ├── Количество изменений
│   └── Последний коммит
└── SystemLog.vue (опционально)
```

### Представление браузера файлов

```
FileBrowser.vue
├── Breadcrumbs.vue
├── Toolbar.vue
│   ├── Button: Открыть в редакторе
│   ├── Button: Обновить
│   ├── Button: Копировать
│   └── Button: Скачать
├── Layout (flex)
│   ├── FileTree.vue (боковая панель)
│   │   ├── Элементы папок
│   │   ├── Элементы файлов
│   │   └── SearchBar.vue
│   └── FileViewer.vue (основной)
│       ├── MarkdownRenderer.vue
│       ├── CodeViewer.vue
│       └── PDFViewer.vue
└── SystemLog.vue (сворачиваемый)
```

### Представление настроек

```
Settings.vue
├── Секция: Общие
│   ├── Репозиторий по умолчанию
│   ├── Папка по умолчанию
│   └── Автосинхронизация
├── Секция: Git
│   ├── Порт Git
│   └── Автозапуск
├── Секция: Редактор
│   ├── Тип редактора
│   └── Пользовательский путь
├── Секция: UI
│   ├── Тема
│   └── Размер шрифта
└── Секция: Ollama
    ├── URL
    └── Модель
```

## Управление состоянием (Pinia)

### Хранилище файлов

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

### Хранилище репозиториев

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

### Системное хранилище

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

## API endpoints

### Существующие

```
GET  /                          # Панель управления
GET  /files                     # Браузер файлов
GET  /api/status                # Статус системы
GET  /api/files                 # Список файлов
GET  /api/file/view             # Просмотр файла
GET  /api/file/pdf              # Просмотр PDF
GET  /api/repos                 # Список репозиториев
POST /api/repos/select          # Выбор репозитория
POST /api/git/start             # Запуск Git сервера
POST /api/git/stop              # Остановка Git сервера
POST /api/git/save-and-sync     # Сохранение и синхронизация
GET  /api/git/changes           # Список изменений
POST /api/system/open-editor    # Открытие редактора
POST /api/chat                  # AI чат
```

### Новые (планируются)

```
# Настройки
GET  /api/settings              # Получение настроек
POST /api/settings              # Обновление настроек (частичное)
PUT  /api/settings              # Замена всех настроек
GET  /api/settings/defaults     # Значения по умолчанию

# Логи
GET  /api/logs                  # История логов
DELETE /api/logs                # Очистка логов
WS   /ws/logs                   # WebSocket для логов

# Git расширенный
GET  /api/git/status            # Статус всех файлов
GET  /api/git/diff              # Diff файла
GET  /api/git/history           # История коммитов
GET  /api/git/blame             # Blame файла
GET  /api/git/branches          # Список веток
POST /api/git/checkout          # Переключение ветки

# Поиск
GET  /api/search/files          # Поиск файлов
GET  /api/search/content        # Поиск по содержимому
POST /api/search/index          # Переиндексация
```

## Технологии

### Backend
- **FastAPI** - веб-фреймворк
- **Uvicorn** - ASGI сервер
- **WebSocket** - real-time коммуникация
- **Git** - система контроля версий
- **Python 3.10+**

### Frontend
- **Vue.js 3** - UI фреймворк
- **Vite** - сборщик
- **Vue Router** - маршрутизация
- **Pinia** - управление состоянием
- **TailwindCSS** - стили
- **TypeScript** - типизация (опционально)

### Библиотеки
- **Marked.js** - Markdown парсер
- **Mermaid.js** - диаграммы
- **Highlight.js** - подсветка кода
- **PDF.js** - PDF рендеринг
- **Fuse.js** - нечёткий поиск
- **@vscode/codicons** - иконки

## Безопасность

```mermaid
graph LR
    A[Клиент] -->|HTTPS| B[Веб-сервер]
    A -->|Git протокол| C[Git сервер]
    B -->|Локально| D[Файловая система]
    C -->|Локально| D
    B -->|WebSocket| E[Логгер]
    
    style A fill:#3b82f6
    style B fill:#10b981
    style C fill:#10b981
    style D fill:#f59e0b
    style E fill:#8b5cf6
```

### Меры безопасности
- Только локальная сеть (LAN)
- Нет внешнего доступа
- Изолированная файловая система
- Git протокол без аутентификации (локально)
- WebSocket только для логов (только чтение)

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