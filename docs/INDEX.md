# Индекс документации LocalGitMirror

Добро пожаловать в документацию LocalGitMirror! Этот индекс поможет вам найти нужную информацию.

## 📚 Быстрый старт

- **[QUICKSTART.md](QUICKSTART.md)** - Быстрый старт с LocalGitMirror
- **[README.md](../README.md)** - Основной обзор проекта (в корне)

## 🏗️ Архитектура и дизайн

- **[ARCHITECTURE.md](ARCHITECTURE.md)** - Полная архитектура системы с диаграммами
- **[MIGRATION_GUIDE.md](MIGRATION_GUIDE.md)** - Руководство по миграции для обновлений версий

## 🔧 Основные функции

### Система логирования
- **[LOGGING.md](LOGGING.md)** - Полная документация системы логирования
  - Потоковая передача WebSocket в реальном времени
  - Постоянное хранение в файлах
  - Vue.js компонент
  - Справочник API
  - Устранение неполадок

### Система настроек
- **[SETTINGS.md](SETTINGS.md)** - Полная документация системы настроек
  - Постоянное хранение бэкенда
  - Vue.js frontend
  - API endpoints
  - Параметры конфигурации
  - Правила валидации

### Браузер файлов
- **[FILE_BROWSER.md](FILE_BROWSER.md)** - Документация браузера файлов
  - Навигация по древовидной структуре
  - Предпросмотр файлов
  - Рендеринг Markdown
  - Подсветка кода
  - Просмотр PDF

## 🎨 Frontend компоненты

- **[FILE_VIEWER_COMPONENTS.md](FILE_VIEWER_COMPONENTS.md)** - Компоненты просмотра файлов
- **[FILETREE_INTEGRATION.md](FILETREE_INTEGRATION.md)** - Интеграция дерева файлов
- **[FILETREE_SUMMARY.md](FILETREE_SUMMARY.md)** - Резюме дерева файлов

## 📊 Информация о проекте

- **[PROJECT_SUMMARY.md](PROJECT_SUMMARY.md)** - Резюме проекта и обзор
- **[SUMMARY.md](SUMMARY.md)** - Общее резюме

## 📝 Разработка

- **[TODO.md](../TODO.md)** - Задачи разработки и roadmap (в корне)
- **[CLEANUP_REPORT.md](../CLEANUP_REPORT.md)** - Отчёт о недавней очистке (в корне)

## 🗂️ Структура документации

```
docs/
├── INDEX.md                     # Этот файл - Индекс документации
├── QUICKSTART.md                # Руководство по быстрому старту
├── ARCHITECTURE.md              # Архитектура системы
├── MIGRATION_GUIDE.md           # Руководство по миграции
├── LOGGING.md                   # Система логирования (консолидированная)
├── SETTINGS.md                  # Система настроек (консолидированная)
├── FILE_BROWSER.md              # Браузер файлов (консолидированная)
├── FILE_VIEWER_COMPONENTS.md    # Компоненты просмотра файлов
├── FILETREE_INTEGRATION.md      # Интеграция дерева файлов
├── FILETREE_SUMMARY.md          # Резюме дерева файлов
├── PROJECT_SUMMARY.md           # Резюме проекта
└── SUMMARY.md                   # Общее резюме
```

## 🔍 Поиск информации

### Для пользователей
1. Начните с [QUICKSTART.md](QUICKSTART.md)
2. Изучите функции в [FILE_BROWSER.md](FILE_BROWSER.md)
3. Настройте параметры, используя [SETTINGS.md](SETTINGS.md)

### Для разработчиков
1. Поймите архитектуру в [ARCHITECTURE.md](ARCHITECTURE.md)
2. Изучите логирование в [LOGGING.md](LOGGING.md)
3. Просмотрите компоненты в [FILE_VIEWER_COMPONENTS.md](FILE_VIEWER_COMPONENTS.md)

### Для системных администраторов
1. Просмотрите [ARCHITECTURE.md](ARCHITECTURE.md) для обзора системы
2. Проверьте [SETTINGS.md](SETTINGS.md) для конфигурации
3. Используйте [LOGGING.md](LOGGING.md) для мониторинга

## 📌 Документация ключевых функций

| Функция | Документация | Описание |
|---------|--------------|----------|
| Логирование | [LOGGING.md](LOGGING.md) | Реальное логирование с WebSocket |
| Настройки | [SETTINGS.md](SETTINGS.md) | Полное управление настройками |
| Браузер файлов | [FILE_BROWSER.md](FILE_BROWSER.md) | Браузер файлов как в VS Code |
| Архитектура | [ARCHITECTURE.md](ARCHITECTURE.md) | Дизайн системы и компоненты |

## 🆘 Получение помощи

1. Проверьте соответствующую документацию выше
2. Просмотрите [QUICKSTART.md](QUICKSTART.md) для распространённых задач
3. Проверьте [ARCHITECTURE.md](ARCHITECTURE.md) для понимания системы
4. Просмотрите специфическую документацию функции (LOGGING, SETTINGS, FILE_BROWSER)

## 📅 Недавние обновления

- **2026-01-28**: Крупная очистка и консолидация документации
  - Консолидирована документация LOGGING (5 файлов → 1)
  - Консолидирована документация SETTINGS (3 файла → 1)
  - Консолидирована документация FILE_BROWSER (3 файла → 1)
  - Создан этот INDEX.md для лёгкой навигации
  - Смотрите [CLEANUP_REPORT.md](../CLEANUP_REPORT.md) для деталей

## 🔗 Внешние ресурсы

- **Backend**: FastAPI, Python 3.12+
- **Frontend**: Vue 3, Vite, Tailwind CSS
- **Git**: Интеграция Git-сервера
- **Хранилище**: Локальная файловая система

---

**Версия:** 3.2.0  
**Последнее обновление:** 2026-01-28  
**Статус:** ✅ Активная разработка