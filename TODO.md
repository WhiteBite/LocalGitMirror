# 🛡️ LocalGitMirror: Development Plan

## ✅ Completed (Done)
- [x] **Core:** Implement Git-over-HTTP (replace Git Protocol).
- [x] **Fix:** Resolve "NoneType" error in Dulwich/WSGI.
- [x] **Fix:** Resolve "Win32 application" error for Git Hooks on Windows.
- [x] **Fix:** Implement robust "Auto-Sync" (Python fallback) for non-bare repos.
- [x] **Feature:** Global Search (Grep) via API & UI.
- [x] **UX:** Add loaders and status indicators for Sync operations.
- [x] **Stability:** Remove emoji from logs to prevent Unicode crashes on Windows.

---

## 🚀 Phase 2: Stealth & Security (Priority: HIGH)
*Задача: Сделать канал передачи данных невидимым для DLP и админов.*

- [ ] **HTTPS / SSL Support**
    - Внедрить SSL-сертификаты (self-signed) в Uvicorn.
    - Весь трафик (и веб, и git) должен идти через `https://`.
    - *Результат:* Снифферы видят только шифрованный поток, код внутри не прочитать.

- [ ] **Traffic Masquerading (Маскировка)**
    - Сменить порты по умолчанию. Вместо `8081` (палево) использовать `3000` (React/Dev) или `8443` (Common HTTPS).
    - Добавить опцию "Fake Headers" для ответов сервера (чтобы сканер думал, что это обычный Nginx или Apache).

- [ ] **Security: Token Auth**
    - Закрыть Git-доступ паролем/токеном.
    - Сейчас любой в локалке может сделать `git pull`. Нужно внедрить Basic Auth в `git_http.py`.

- [ ] **Feature: "Panic Button" (Boss Key)**
    - Кнопка в UI (или хоткей), которая мгновенно убивает процесс сервера и закрывает вкладку.

---

## 🛠 Phase 3: Improvements (Priority: Medium)
- [ ] **Smart Commits:** UI для просмотра изменений перед "Prepare for Work".
- [ ] **File Editor:** Простенький редактор кода прямо в браузере (Monaco Editor).
- [ ] **Context Awareness:** Интеграция с LLM для анализа кода (RAG).
