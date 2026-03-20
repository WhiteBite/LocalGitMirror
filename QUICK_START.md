# ⚡ Stealth Sync - Быстрая шпаргалка

## 🏠 На домашнем ПК (один раз)

```cmd
# 1. Установите зависимости
cd backend && pip install -r requirements.txt
cd ../frontend && npm install && npm run build

# 2. Отредактируйте .env
# SYNC_PASSWORD=dandan
# STORAGE_PATH=E:/kryptonit

# 3. Запустите сервер
python backend/run.py

# Готово! Сервер на https://192.168.1.100:443
```

---

## 💼 На рабочем ПК (каждый день)

### Конец дня:

```cmd
cd C:\Users\YourName\Projects\MyProject

# Убедитесь что все закоммичено
git status

# Создайте дамп
backup_work_stealth.bat dandan

# Загрузите dump_MyProject_*.dmp через браузер
# https://192.168.1.100:443/files
```

### Дома (вечер):

```
1. Откройте https://192.168.1.100:443
2. Dashboard → Data Sync Procedures
3. Нажмите "Sync Changes to Workspace"
4. Готово!
```

---

## 📋 Команды

| Что | Команда |
|-----|---------|
| Запустить сервер | `python backend/run.py` |
| Создать дамп | `backup_work_stealth.bat dandan` |
| Открыть интерфейс | `https://192.168.1.100:443` |
| Загрузить файл | Dashboard → File Manager |
| Синхронизировать | Dashboard → Data Sync Procedures |
| Проверить логи | Dashboard → System Log |

---

## 🔑 Пароли

- **SYNC_PASSWORD**: `dandan` (в .env)
- **API_KEY**: `stealth-bridge-token-2026` (в .env)

---

## 🆘 Если не работает

```cmd
# 1. Проверьте 7-Zip
"C:\Program Files\7-Zip\7z.exe" --version

# 2. Проверьте пароль в .env
# SYNC_PASSWORD=dandan

# 3. Перезапустите сервер
# Ctrl+C в терминале, затем python backend/run.py

# 4. Проверьте логи
# Dashboard → System Log
```

---

## 📊 Размеры

- Первый раз: ~50-200 MB (полный проект)
- Потом: ~2-10 MB (только изменения)

---

**Полная инструкция: STEALTH_SYNC_GUIDE.md**
