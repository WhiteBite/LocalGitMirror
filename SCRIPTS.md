# Скрипты запуска LocalGitMirror

## 🚀 Быстрый старт

### Production (собранный frontend)
```bash
# Windows
start.bat
# или
start-prod.bat

# Linux/Mac
./start.sh
# или
./start-prod.sh
```

### Development (hot reload)
```bash
# Windows
start-dev.bat

# Linux/Mac
./start-dev.sh
```

---

## 📋 Описание скриптов

### Production режим

**Файлы:** `start.bat`, `start-prod.bat`, `start.sh`, `start-prod.sh`

**Что делает:**
- Проверяет наличие собранного frontend (`frontend/dist/`)
- Если нет - собирает автоматически (`npm run build`)
- Запускает backend на порту 8000
- Backend отдаёт статические файлы frontend

**Когда использовать:**
- Для production использования
- Когда не нужен hot reload
- Для демонстрации проекта
- Для деплоя на сервер

**URL:** http://localhost:8000

---

### Development режим

**Файлы:** `start-dev.bat`, `start-dev.sh`

**Что делает:**
- Запускает backend с hot reload (uvicorn --reload)
- Запускает frontend dev server (Vite)
- Открывает 2 окна терминала (Windows) или фоновые процессы (Linux/Mac)

**Когда использовать:**
- Во время разработки
- Когда нужен hot reload
- Для быстрого тестирования изменений

**URLs:**
- Backend: http://localhost:8000
- Frontend: http://localhost:5173 (с hot reload)

---

## 🔧 Требования

### Production режим
- Python 3.10+
- Node.js 18+ (только для первой сборки)

### Development режим
- Python 3.10+
- Node.js 18+
- npm

---

## 📝 Примеры использования

### 1. Первый запуск (Production)

```bash
# Windows
start.bat

# Linux/Mac
chmod +x start.sh start-prod.sh
./start.sh
```

Скрипт автоматически:
1. Проверит наличие `frontend/dist/`
2. Если нет - соберёт frontend
3. Запустит backend
4. Откроет http://localhost:8000

### 2. Разработка (Development)

```bash
# Windows
start-dev.bat

# Linux/Mac
chmod +x start-dev.sh
./start-dev.sh
```

Откроются 2 окна:
- **Backend Dev** - backend с hot reload
- **Frontend Dev** - Vite dev server

Изменения в коде применяются автоматически!

### 3. Остановка серверов

**Production:**
- Нажмите `Ctrl+C` в терминале

**Development (Windows):**
- Закройте оба окна терминала
- Или нажмите `Ctrl+C` в каждом окне

**Development (Linux/Mac):**
- Нажмите `Ctrl+C` в терминале
- Скрипт автоматически остановит оба процесса

---

## 🐛 Устранение неполадок

### "Python not found"
Установите Python 3.10+: https://www.python.org/downloads/

### "Node.js not found"
Установите Node.js 18+: https://nodejs.org/

### "Frontend not built"
```bash
cd frontend
npm install
npm run build
cd ..
```

### "Port 8000 already in use"
Остановите другой процесс на порту 8000:
```bash
# Windows
netstat -ano | findstr :8000
taskkill /PID <PID> /F

# Linux/Mac
lsof -ti:8000 | xargs kill -9
```

### "Permission denied" (Linux/Mac)
```bash
chmod +x start.sh start-prod.sh start-dev.sh
```

---

## 🎯 Рекомендации

### Для разработки
✅ Используйте `start-dev.bat` / `start-dev.sh`
- Hot reload для backend и frontend
- Быстрое тестирование изменений
- Удобная отладка

### Для production
✅ Используйте `start.bat` / `start.sh`
- Оптимизированный frontend
- Один сервер на порту 8000
- Готово к деплою

### Для демонстрации
✅ Используйте `start.bat` / `start.sh`
- Быстрый запуск
- Всё в одном месте
- Профессиональный вид

---

## 📦 Структура

```
LocalGitMirror/
├── start.bat              # Production (Windows)
├── start.sh               # Production (Linux/Mac)
├── start-prod.bat         # Production (Windows, детальный)
├── start-prod.sh          # Production (Linux/Mac, детальный)
├── start-dev.bat          # Development (Windows)
├── start-dev.sh           # Development (Linux/Mac)
├── backend/
│   └── run.py            # Точка входа backend
└── frontend/
    ├── dist/             # Собранный frontend (production)
    └── package.json      # Конфигурация frontend
```

---

## 🔄 Workflow

### Типичный день разработки:

1. **Утро:** Запустите `start-dev.bat`
2. **Работа:** Редактируйте код, изменения применяются автоматически
3. **Тестирование:** Проверяйте на http://localhost:5173
4. **Вечер:** Закройте терминалы

### Перед коммитом:

1. Остановите dev серверы
2. Запустите `start.bat` (production режим)
3. Проверьте что всё работает на http://localhost:8000
4. Сделайте коммит изменений

### Деплой:

1. Соберите frontend: `cd frontend && npm run build`
2. Скопируйте проект на сервер
3. Запустите `start.sh` на сервере
4. Готово!

---

## 💡 Советы

### Быстрая пересборка frontend
```bash
cd frontend
npm run build
cd ..
```

### Проверка портов
```bash
# Backend должен быть на 8000
curl http://localhost:8000/api/status

# Frontend dev на 5173
curl http://localhost:5173
```

### Логи
- Backend логи: в терминале
- Frontend логи: в терминале + browser console
- Системные логи: `storage/logs/system.log`