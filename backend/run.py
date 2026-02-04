#!/usr/bin/env python3
"""
LocalGitMirror Entry Point
Run this file to start the application
"""

import sys
from pathlib import Path

# Add backend directory to Python path
backend_dir = Path(__file__).parent
sys.path.insert(0, str(backend_dir))

if __name__ == "__main__":
    from app.main import app, CONFIG
    import uvicorn
    
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=CONFIG["web_port"],
        reload=False,
        log_level="info"
    )
