@router.post("/system/panic")
async def panic_mode():
    """Emergency shutdown"""
    if system_logger:
        system_logger.critical("PANIC BUTTON PRESSED. TERMINATING.")

    # Schedule death
    import threading
    import time

    def kill_self():
        time.sleep(0.5)
        os._exit(0)

    threading.Thread(target=kill_self, daemon=True).start()
    return {"message": "Terminating..."}
