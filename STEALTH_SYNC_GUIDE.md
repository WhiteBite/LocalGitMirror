# 🔐 Stealth Sync (DMP Protocol) - Полная инструкция

## Что это?

Система инкрементальной синхронизации кода с работы на дом через зашифрованные "дампы" памяти. Выглядит как обычные логи для систем мониторинга (EDR/DLP).

**Преимущества:**
- ✅ Минимальный трафик (только изменения)
- ✅ Зашифровано AES-256
- ✅ Выглядит как memory dump (`.dmp`)
- ✅ Автоматическое управление состоянием
- ✅ Разрешение конфликтов
- ✅ OpSec задержки (не спалит NDR)

---

## 📋 Требования

### На домашнем ПК (сервер):
- Python 3.12+
- Node.js 18+
- Git
- 7-Zip (обязательно!)
- LocalGitMirror (установлен и запущен)

### На рабочем ПК (клиент):
- Git
- 7-Zip (обязательно!)
- Браузер (Chrome/Firefox)

---

## 🚀 Первый запуск (Инициализация)

### Шаг 1: Подготовка домашнего сервера

**На домашнем ПК:**

```cmd
# 1. Установите зависимости
cd backend
pip install -r requirements.txt

cd ../frontend
npm install
npm run build

# 2. Сгенерируйте SSL сертификат
python generate_cert.py 192.168.1.100
# (замените на ваш домашний IP)

# 3. Настройте .env
# Откройте .env и установите:
# SYNC_PASSWORD=ваш_надежный_пароль
# STORAGE_PATH=E:/kryptonit (или ваша папка)

# 4. Запустите сервер
python backend/run.py
```

Сервер запустится на `https://192.168.1.100:443`

### Шаг 2: Первый полный бэкап

**На рабочем ПК:**

```cmd
# 1. Откройте Dashboard
# https://192.168.1.100:443

# 2. Перейдите: Dashboard → Data Sync Procedures

# 3. Скачайте backup_work_stealth.bat

# 4. Поместите в корень вашего проекта:
# C:\Users\YourName\Projects\MyProject\backup_work_stealth.bat

# 5. Запустите (первый раз - полный бэкап):
backup_work_stealth.bat dandan
# (используйте пароль из .env SYNC_PASSWORD)

# Создаст: dump_MyProject_20240520_1800.dmp (~50-200 MB)
```

**На домашнем ПК:**

```cmd
# 1. Откройте File Manager в браузере
# https://192.168.1.100:443/files

# 2. Перейдите в Shared Folders → work-sync (или sync)

# 3. Загрузите dump_MyProject_*.dmp

# 4. Вернитесь на Dashboard → Data Sync Procedures

# 5. Нажмите кнопку "Sync Changes to Workspace"

# 6. Ждите завершения (может быть 1-5 минут)

# 7. Проверьте System Log для подтверждения
```

---

## 📅 Ежедневный workflow

### Конец рабочего дня (на работе):

```cmd
cd C:\Users\YourName\Projects\MyProject

# Убедитесь что все закоммичено
git status

# Запустите синхронизацию
backup_work_stealth.bat dandan

# Вывод:
# [*] Generating memory dump...
# [+] SUCCESS: Memory dump generated
# File: dump_MyProject_20240520_1800.dmp (15 MB)
# 
# Next steps:
# 1. Upload via browser to home server
# 2. Click "Sync" in Dashboard
# 3. Run this script again for next sync
```

### Дома (вечер):

```cmd
# 1. Откройте браузер: https://192.168.1.100:443

# 2. Dashboard → Data Sync Procedures

# 3. Загрузите dump_MyProject_*.dmp через File Manager

# 4. Нажмите "Sync Changes to Workspace"

# 5. Ждите сообщения "Sync applied successfully"

# 6. Проверьте файлы в File Manager
```

### Утром на работе:

```cmd
# Повторите процесс в обратную сторону
# (если нужно синхронизировать домашние изменения на работу)

# Или просто продолжайте работать
# Следующий backup_work_stealth.bat создаст дельту
```

---

## 🔧 Конфигурация

### .env файл (домашний сервер):

```env
# Порты
WEB_PORT=443                    # Основной веб-интерфейс
GIT_PORT=8444                   # Git сервер

# Хранилище
STORAGE_PATH=E:/kryptonit       # Где хранятся ваши проекты

# Безопасность
API_KEY=stealth-bridge-token-2026  # Измените на уникальный!
SILENT_GIT=true                 # Скрывать Git операции в логах

# Stealth Sync (ОБЯЗАТЕЛЬНО!)
SYNC_PASSWORD=dandan            # Пароль для шифрования дампов

# 7-Zip (опционально)
# SEVEN_ZIP_PATH=C:/Program Files/7-Zip/7z.exe

# Ollama (опционально)
OLLAMA_URL=http://localhost:11434
OLLAMA_MODEL=llama3.2
```

### Изменение пароля:

```cmd
# 1. Отредактируйте .env
SYNC_PASSWORD=новый_пароль

# 2. Перезапустите сервер
# (остановите python backend/run.py и запустите снова)

# 3. На работе используйте новый пароль:
backup_work_stealth.bat новый_пароль
```

---

## 🌐 Сетевая настройка

### Получение домашнего IP:

```cmd
# На домашнем ПК
ipconfig | findstr "IPv4"

# Ищите адрес вида: 192.168.x.x
# Например: 192.168.1.100
```

### Доступ из локальной сети:

```
https://192.168.1.100:443
```

Браузер покажет предупреждение о сертификате - это нормально (self-signed).

### Доступ из интернета (опасно!):

⚠️ **НЕ РЕКОМЕНДУЕТСЯ** открывать порт 443 в роутере!

Если нужно:
1. Используйте VPN
2. Или пробросьте на другой порт (например 8443)
3. Используйте сильный пароль в SYNC_PASSWORD

---

## 📊 Размеры файлов

| Сценарий | Размер | Время |
|----------|--------|-------|
| Первый полный бэкап | ~90% проекта | 5-30 мин |
| Ежедневная дельта | 1-50 MB | 1-5 мин |
| Еженедельная дельта | 10-100 MB | 5-15 мин |

**Пример:**
- Проект 500 MB → первый дамп ~50 MB (сжато)
- Ежедневные изменения → 2-5 MB

---

## 🐛 Устранение неполадок

### Ошибка: "7-Zip not found"

```cmd
# Установите 7-Zip с https://www.7-zip.org/

# Или укажите путь в .env:
SEVEN_ZIP_PATH=C:/Program Files/7-Zip/7z.exe

# Или если установлен в другом месте:
SEVEN_ZIP_PATH=D:/Tools/7z.exe
```

### Ошибка: "Not a valid git repository"

```cmd
# На рабочем ПК убедитесь что вы в Git репозитории
git status

# Если нет - инициализируйте:
git init
git add .
git commit -m "Initial commit"
```

### Ошибка: "Uncommitted changes detected"

```cmd
# На домашнем ПК есть незакоммиченные изменения
# Решение:

# Вариант 1: Закоммитьте их
git add .
git commit -m "Home changes"

# Вариант 2: Отмените их
git reset --hard HEAD
```

### Ошибка: "Dump is for 'ProjectX' but current repo is 'ProjectY'"

```cmd
# Вы загрузили дамп для другого проекта
# Решение:

# 1. Выберите правильный проект в Dashboard
# 2. Или загрузите правильный дамп
```

### Ошибка: "Decryption failed - wrong password"

```cmd
# Неверный пароль
# Решение:

# 1. Проверьте пароль в .env (SYNC_PASSWORD)
# 2. Используйте правильный пароль при запуске:
backup_work_stealth.bat правильный_пароль
```

### Порт 443 занят

```cmd
# Измените в .env:
WEB_PORT=8443

# Затем доступ будет по:
https://192.168.1.100:8443
```

---

## 🔒 Безопасность

### Рекомендации:

1. **Измените пароль:**
   ```env
   SYNC_PASSWORD=ваш_надежный_пароль_32_символа
   ```

2. **Измените API ключ:**
   ```env
   API_KEY=ваш-уникальный-ключ-2026
   ```

3. **Используйте VPN** для доступа из интернета

4. **Не открывайте порт 443** в роутере без необходимости

5. **Регулярно проверяйте логи:**
   - Dashboard → System Log

---

## 📈 Мониторинг

### Dashboard показывает:

- ✅ Статус последней синхронизации
- ✅ Размер последнего дампа
- ✅ Количество бэкапов в архиве
- ✅ Использование диска
- ✅ Использование памяти

### System Log показывает:

- Все операции синхронизации
- Ошибки и предупреждения
- Время выполнения операций

---

## 💡 Советы

### Оптимизация:

1. **Коммитьте перед бэкапом:**
   ```cmd
   git add .
   git commit -m "End of day"
   backup_work_stealth.bat dandan
   ```

2. **Исключайте большие файлы:**
   ```
   # .gitignore
   node_modules/
   dist/
   *.log
   ```

3. **Используйте инкрементальные бэкапы:**
   - Первый раз: полный (~50-200 MB)
   - Потом: только изменения (~2-10 MB)

### Автоматизация:

Создайте `sync.bat` для быстрого запуска:

```batch
@echo off
cd /d %~dp0
backup_work_stealth.bat dandan
echo.
echo Dump created! Upload it to home server.
pause
```

Затем просто запускайте `sync.bat` в конце дня.

---

## 🎯 Полный workflow (пример)

### День 1 (Понедельник):

```cmd
# Утро на работе
cd C:\Projects\MyProject
git status
# On branch main, nothing to commit

# Работаете весь день...
# Редактируете файлы, коммитите

# Конец дня
git add .
git commit -m "Monday work"
backup_work_stealth.bat dandan
# dump_MyProject_20240520_1800.dmp (15 MB)

# Загружаете через браузер на домашний сервер
# https://192.168.1.100:443/files
```

```cmd
# Вечер дома
# Открываете Dashboard
# https://192.168.1.100:443

# Видите загруженный дамп
# Нажимаете "Sync Changes to Workspace"
# Ждите 2-3 минуты

# Проверяете файлы в File Manager
# Все изменения применены!
```

### День 2 (Вторник):

```cmd
# Утро на работе
cd C:\Projects\MyProject
git pull  # Если нужны домашние изменения

# Работаете весь день...

# Конец дня
git add .
git commit -m "Tuesday work"
backup_work_stealth.bat dandan
# dump_MyProject_20240521_1800.dmp (8 MB)
# Только изменения за день!

# Повторяете процесс загрузки и синхронизации
```

---

## 📞 Поддержка

Если что-то не работает:

1. Проверьте System Log на домашнем сервере
2. Убедитесь что 7-Zip установлен
3. Проверьте пароль в .env
4. Перезапустите сервер
5. Проверьте сетевое соединение

---

**Готово! Система работает. Наслаждайтесь безопасной синхронизацией! 🚀**
