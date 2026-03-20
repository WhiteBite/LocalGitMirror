# Развертывание LocalGitMirror на домашнем компьютере

## 🚀 Stealth Sync (DMP Protocol) - Быстрый старт

### Что это?
Инкрементальная синхронизация кода с работы на дом через зашифрованные "дампы" памяти. Выглядит как обычные логи для систем мониторинга.

### Требования:
- Python 3.12+
- Node.js 18+
- Git
- 7-Zip (обязательно!)
- Домашний сервер с LocalGitMirror (запущен)

### Workflow:

**На рабочем ПК (конец дня):**
```cmd
cd your-project
backup_work_stealth.bat [пароль]
# Создаст: dump_ProjectName_20240520_1800.dmp
```

**На домашнем ПК (через браузер):**
1. Откройте: `https://[домашний_IP]:443`
2. Перейдите: Dashboard → Data Sync Procedures
3. Загрузите `dump_ProjectName_*.dmp` через File Manager
4. Нажмите кнопку "Sync Changes to Workspace"
5. Готово! Изменения применены

---

## Быстрый старт

## Быстрый старт

### Вариант 1: Через инкрементальный бэкап (рекомендуется)

Используйте встроенную систему инкрементальных бэкапов для минимального трафика.

**На рабочем ПК:**

1. Откройте Dashboard → Data Sync Procedures
2. Скачайте `backup_work_delta.bat` и `restore_home_delta.bat`
3. Поместите скрипты в корень проекта
4. Запустите:
   ```cmd
   backup_work_delta.bat
   ```
5. Загрузите созданный `sys_log_chunk.bin` через File Manager → Shared Folders

**На домашнем ПК:**

1. Скачайте `sys_log_chunk.bin` из File Manager
2. Поместите в корень проекта
3. Запустите:
   ```cmd
   restore_home_delta.bat
   ```

**На рабочем ПК:**

1. Запустите `backup_vault.bat` (создаст полный архив)
2. Загрузите `sys_dump_logs.bin` через File Manager

**На домашнем ПК:**

1. Установите зависимости:
   - Python 3.12+
   - Node.js 18+
   - Git
   - 7-Zip

2. Скачайте и распакуйте `sys_dump_logs.bin`:
   ```cmd
   "C:\Program Files\7-Zip\7z.exe" x sys_dump_logs.bin
   ```

3. Установите зависимости:
   ```cmd
   cd backend
   pip install -r requirements.txt
   
   cd ../frontend
   npm install
   npm run build
   ```

4. Сгенерируйте SSL сертификат:
   ```cmd
   python generate_cert.py [ваш_домашний_IP]
   ```

5. Настройте `.env`:
   ```env
   WEB_PORT=443
   GIT_PORT=8444
   STORAGE_PATH=storage
   API_KEY=stealth-bridge-token-2026
   SILENT_GIT=true
   BACKUP_PASSWORD=ваш_пароль
   ```

6. Настройте Firewall:
   ```cmd
   # Запустите от администратора
   setup_firewall.bat
   ```

7. Запустите сервер:
   ```cmd
   python backend/run.py
   ```

## Структура проекта для переноса

### Обязательные файлы:
```
LocalGitMirror/
├── backend/              # Backend код
├── frontend/dist/        # Собранный frontend
├── storage/              # Ваши данные (репозитории)
├── .env                  # Конфигурация
├── cert.pem              # SSL сертификат
├── key.pem               # SSL ключ
├── backup_work_delta.bat # Скрипт бэкапа
├── restore_home_delta.bat # Скрипт восстановления
└── setup_firewall.bat    # Настройка firewall
```

### Можно не переносить:
- `node_modules/`
- `backend/venv/`
- `frontend/src/` (если dist собран)
- `.git/` (если используете инкрементальные бэкапы)
- Логи и кэши

## Автоматизация синхронизации

### Ежедневный workflow:

**Конец рабочего дня:**
```cmd
cd your-project
backup_work_delta.bat
# Загрузите sys_log_chunk.bin через веб-интерфейс
```

**Дома:**
```cmd
# Скачайте sys_log_chunk.bin
cd your-project
restore_home_delta.bat
```

**Утром на работе:**
```cmd
# Повторите процесс в обратную сторону
```

## Настройка сети

### Получение домашнего IP:

```cmd
ipconfig | findstr "IPv4"
```

Ищите адрес вида `192.168.x.x`

### Доступ из локальной сети:

1. Узнайте IP домашнего ПК
2. Откройте на другом устройстве: `https://[IP]:443`
3. Игнорируйте предупреждение SSL (self-signed сертификат)

### Обновление сертификата с новым IP:

```cmd
python generate_cert.py 192.168.1.100
```

## Устранение неполадок

### Порт 443 занят

```cmd
# Проверьте что использует порт
netstat -ano | findstr :443

# Измените порт в .env
WEB_PORT=8443
```

### Firewall блокирует

```cmd
# Запустите от администратора
setup_firewall.bat

# Или вручную добавьте правило
netsh advfirewall firewall add rule name="LocalGitMirror" dir=in action=allow protocol=TCP localport=443
```

### Ошибка SSL сертификата

```cmd
# Перегенерируйте сертификат
python generate_cert.py
```

### Не работает инкрементальный бэкап

```cmd
# Проверьте что вы в Git репозитории
git status

# Если нет - инициализируйте
git init
git add .
git commit -m "Initial commit"

# Удалите .last_sync для полного бэкапа
del .last_sync
backup_work_delta.bat
```

## Безопасность

### Рекомендации:

1. **Измените API ключ** в `.env`:
   ```env
   API_KEY=ваш-уникальный-ключ-2026
   ```

2. **Установите пароль бэкапа**:
   ```env
   BACKUP_PASSWORD=ваш_надежный_пароль
   ```

3. **Используйте VPN** для доступа из интернета

4. **Не открывайте порт 443** в роутере без необходимости

## Производительность

### Размеры файлов:

- **Полный бэкап**: ~90% размера проекта (сжатый)
- **Инкрементальный**: 1-50 MB (зависит от изменений)
- **Ежедневная дельта**: обычно 2-10 MB

### Оптимизация:

1. Используйте инкрементальные бэкапы
2. Коммитьте изменения перед бэкапом
3. Не включайте `node_modules` в Git
4. Используйте `.gitignore` для больших файлов

## Дополнительные возможности

### Авто-распаковка на домашнем ПК:

Система автоматически распаковывает `sys_dump_logs.bin` при загрузке через File Manager (если установлен `BACKUP_PASSWORD` в `.env`).

### Скачивание скриптов:

Все скрипты доступны через Dashboard → Data Sync Procedures → Setup Automation Scripts

### Мониторинг:

- Dashboard показывает статус последнего бэкапа
- System Log отображает все операции
- Metrics показывают использование диска и памяти