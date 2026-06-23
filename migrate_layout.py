#!/usr/bin/env python3
"""
migrate_layout.py — переезд STORAGE_PATH на новую раскладку.

ДО (плоско, всё вперемешку):
    kryptonit/
      onyx-platform/            рабочая копия
      onyx-platform.git/        bare
      frontend/  frontend.git/
      logs/  shared/  deps/  _export_cache/  default/  default.git/
      settings.json

ПОСЛЕ (в корне — только рабочие проекты, всё служебное скрыто в .lgm):
    kryptonit/
      onyx-platform/            рабочая копия (видно)
      frontend/  kryptonite-*/  ...
      .lgm/   (скрытая папка)
        bare/   onyx-platform.git, frontend.git, default.git, ...
        logs/  shared/  deps/  _export_cache/
        settings.json

Использование:
    python migrate_layout.py --dry-run        # показать план, ничего не двигая
    python migrate_layout.py                  # выполнить миграцию
    python migrate_layout.py --storage D:/p   # явный путь к хранилищу
    python migrate_layout.py --drop-default   # заодно удалить системный repo 'default'

ВАЖНО: остановите сервер LGM перед запуском (иначе Windows может держать файлы
.git занятыми). Скрипт идемпотентен и никогда не перезаписывает существующее
в новом месте.
"""
import argparse
import ctypes
import os
import shutil
import sys
from pathlib import Path

# Служебные имена верхнего уровня, которые НЕ являются рабочими проектами.
SERVICE_DIRS = {"logs", "shared", "deps", "_export_cache"}
ALREADY_NEW = {".lgm"}


def load_storage_path() -> Path:
    env_file = Path(__file__).parent / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith("STORAGE_PATH="):
                val = line.split("=", 1)[1].strip().strip('"').strip("'")
                if val:
                    return Path(val)
    val = os.getenv("STORAGE_PATH", "")
    if val:
        return Path(val)
    raise SystemExit("Не удалось определить STORAGE_PATH. Передайте --storage <путь> или задайте STORAGE_PATH в .env")


def move(src: Path, dst: Path, dry: bool, label: str, errors: list):
    if not src.exists():
        return
    if dst.exists():
        print(f"  [skip] {label}: уже есть в новом месте — {dst}")
        return
    print(f"  [move] {label}\n         {src}\n      -> {dst}")
    if dry:
        return
    try:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(src), str(dst))
    except Exception as e:
        errors.append(f"{label}: {e}")
        print(f"  [ERROR] {label}: {e}")


def delete(path: Path, dry: bool, label: str):
    if not path.exists():
        return
    print(f"  [delete] {label}: {path}")
    if dry:
        return

    def on_err(func, p, exc_info):
        import stat
        os.chmod(p, stat.S_IWRITE)
        func(p)

    shutil.rmtree(path, onerror=on_err)


def set_hidden(path: Path):
    """Set the Windows 'hidden' attribute on a folder (no-op elsewhere)."""
    if os.name != "nt" or not path.exists():
        return
    FILE_ATTRIBUTE_HIDDEN = 0x02
    try:
        ctypes.windll.kernel32.SetFileAttributesW(str(path), FILE_ATTRIBUTE_HIDDEN)
    except Exception:
        pass


def main():
    ap = argparse.ArgumentParser(description="Миграция хранилища LGM на раскладку с .lgm")
    ap.add_argument("--storage", help="Переопределить STORAGE_PATH")
    ap.add_argument("--dry-run", action="store_true", help="Показать план без изменений")
    ap.add_argument("--drop-default", action="store_true", help="Удалить системный repo 'default' (workspace + bare)")
    args = ap.parse_args()

    storage = Path(args.storage) if args.storage else load_storage_path()
    dry = args.dry_run

    print(f"\n{'[DRY RUN] ' if dry else ''}Миграция хранилища: {storage.absolute()}\n")
    if not storage.exists():
        raise SystemExit(f"Путь хранилища не существует: {storage}")

    lgm = storage / ".lgm"
    bare_dir = lgm / "bare"
    errors: list = []

    # ── 1. Удалить системный default (опционально) ───────────────────────────
    if args.drop_default:
        print("── Удаление системного 'default' ──────────────────────────────────")
        delete(storage / "default", dry, "default (workspace)")
        delete(storage / "default.git", dry, "default.git (bare)")
        print()

    # ── 2. Переместить все *.git bare-репозитории → .lgm/bare/ ────────────────
    print("── Bare-репозитории → .lgm/bare/ ──────────────────────────────────")
    for item in sorted(storage.iterdir()):
        if item.is_dir() and item.name.endswith(".git"):
            move(item, bare_dir / item.name, dry, f"bare: {item.name}", errors)

    # ── 3. Переместить служебные папки → .lgm/ ────────────────────────────────
    print("\n── Служебные данные → .lgm/ ───────────────────────────────────────")
    for name in sorted(SERVICE_DIRS):
        move(storage / name, lgm / name, dry, name, errors)

    # settings.json → .lgm/settings.json
    move(storage / "settings.json", lgm / "settings.json", dry, "settings.json", errors)

    # ── 4. Скрыть .lgm ────────────────────────────────────────────────────────
    if not dry:
        set_hidden(lgm)

    # ── Итог ──────────────────────────────────────────────────────────────────
    print("\n── Итог ───────────────────────────────────────────────────────────")
    if dry:
        print("  Сухой прогон завершён — ничего не изменено.")
    elif errors:
        print(f"  Завершено С ОШИБКАМИ ({len(errors)}). Вероятно, сервер запущен и держит файлы:")
        for e in errors:
            print(f"    ! {e}")
        print("  Остановите сервер LGM и запустите миграцию повторно (она идемпотентна).")
    else:
        print("  Миграция завершена успешно.")
    print(f"\n  В корне останутся только рабочие проекты. Служебное — в скрытой {lgm}\n")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
