# Settings System Documentation

## 🎯 Overview

Complete settings management system for LocalGitMirror with backend persistence and comprehensive Vue frontend.

## 🚀 Quick Start

### 1. Start the Application

```bash
# Start backend
python main.py

# In another terminal, start frontend
cd frontend
npm run dev
```

### 2. Access Settings

Navigate to: **http://localhost:5173/settings**

### 3. Configure Your Settings

#### General Tab
- Set your default repository
- Enable/disable auto-sync
- Adjust refresh interval (1-60 seconds)

#### Git Tab
- Configure Git server port (default: 8081)
- Enable auto-start for Git server
- Enable auto-commit if desired

#### Editor Tab
- Choose your preferred editor:
  - ⚡ **Cursor** - AI-powered editor
  - 💻 **VS Code** - Visual Studio Code
  - 📁 **System** - Default system application
- Optionally set custom editor path

#### UI Tab
- Choose theme: 🌙 Dark or ☀️ Light
- Adjust font size (10-24px)
- Toggle system log visibility

#### Ollama Tab
- Enable/disable AI integration
- Set Ollama server URL (default: http://localhost:11434)
- Choose AI model (default: llama3.2)

### 4. Save Your Changes

Click **"Save Changes"** button at the bottom of any tab.

You'll see a success notification: ✅ "Settings saved successfully"

## 📖 Architecture

### Settings Structure

```python
{
    "general": {
        "default_repo": "default",
        "default_folder": "",
        "auto_sync": True,
        "refresh_interval": 5  # 1-60 seconds
    },
    "git": {
        "port": 8081,  # 1024-65535
        "auto_start": False,
        "auto_commit": False
    },
    "editor": {
        "type": "cursor",  # cursor | vscode | system
        "custom_path": ""
    },
    "ui": {
        "theme": "dark",  # dark | light
        "font_size": 14,  # 10-24px
        "show_system_log": True
    },
    "ollama": {
        "url": "http://localhost:11434",
        "model": "llama3.2",
        "enabled": True
    }
}
```

### Backend Components

#### 1. Settings Manager (`core/settings_manager.py`)

**Features:**
- Pydantic-based validation for all settings
- JSON file persistence (`storage/settings.json`)
- Default values with type checking
- Import/Export functionality
- Reset to defaults

**Classes:**
- `GeneralSettings` - General application settings
- `GitSettings` - Git service configuration
- `EditorSettings` - Editor preferences with validation
- `UISettings` - UI appearance settings
- `OllamaSettings` - AI integration settings
- `AppSettings` - Complete settings container
- `SettingsManager` - Main manager class

**Methods:**
- `load()` - Load settings from file or create defaults
- `save()` - Save current settings to file
- `get()` - Get current settings
- `update(settings_dict)` - Update settings with validation
- `reset_to_defaults()` - Reset all settings
- `get_defaults()` - Get default values
- `export_settings(path)` - Export to file
- `import_settings(path)` - Import from file

#### 2. Settings Router (`routers/settings.py`)

**Endpoints:**

| Method | Endpoint                  | Description                    |
|--------|---------------------------|--------------------------------|
| GET    | `/api/settings`           | Get current settings           |
| POST   | `/api/settings`           | Update settings (partial)      |
| PUT    | `/api/settings`           | Replace all settings           |
| GET    | `/api/settings/defaults`  | Get default values             |
| POST   | `/api/settings/reset`     | Reset to defaults              |
| POST   | `/api/settings/export`    | Export to file                 |
| POST   | `/api/settings/import`    | Import from file               |

### Frontend Component

#### Settings.vue (`frontend/src/views/Settings.vue`)

**Features:**
- Tab-based navigation (5 tabs)
- Real-time validation
- Success/Error notifications
- Loading states
- Auto-hide notifications (5 seconds)
- Responsive design with Tailwind CSS

**UI Components:**
- Text inputs (5)
- Number inputs (2)
- Range slider (1)
- Toggle switches (6)
- Card selectors (5)
- Buttons (2 per tab)

## 💻 Usage Examples

### Backend Usage

```python
# In main.py or any module
from core.settings_manager import SettingsManager

# Initialize
settings_manager = SettingsManager(storage_path)

# Get settings
settings = settings_manager.get()
print(settings.git.port)  # 8081

# Update settings
new_settings = {
    "git": {"port": 9000},
    "ui": {"theme": "light"}
}
settings_manager.update(new_settings)

# Reset to defaults
settings_manager.reset_to_defaults()
```

### API Usage

```bash
# Get current settings
curl http://localhost:8000/api/settings

# Update settings
curl -X POST http://localhost:8000/api/settings \
  -H "Content-Type: application/json" \
  -d '{
    "git": {"port": 9000},
    "ui": {"theme": "light"}
  }'

# Get defaults
curl http://localhost:8000/api/settings/defaults

# Reset to defaults
curl -X POST http://localhost:8000/api/settings/reset
```

## ✅ Validation Rules

### General Settings
- `refresh_interval`: 1-60 seconds

### Git Settings
- `port`: 1024-65535 (valid port range)

### Editor Settings
- `type`: Must be "cursor", "vscode", or "system"

### UI Settings
- `theme`: Must be "dark" or "light"
- `font_size`: 10-24 pixels

### Ollama Settings
- `url`: String (no validation)
- `model`: String (no validation)

## 🔧 Configuration

### Settings File Location

Your settings are automatically saved to:
```
storage/settings.json
```

### Example Settings File

```json
{
  "general": {
    "default_repo": "my-project",
    "default_folder": "src",
    "auto_sync": true,
    "refresh_interval": 10
  },
  "git": {
    "port": 8081,
    "auto_start": true,
    "auto_commit": false
  },
  "editor": {
    "type": "cursor",
    "custom_path": ""
  },
  "ui": {
    "theme": "dark",
    "font_size": 16,
    "show_system_log": true
  },
  "ollama": {
    "url": "http://localhost:11434",
    "model": "llama3.2",
    "enabled": true
  }
}
```

## 🐛 Troubleshooting

### Settings Not Saving
- Check console for error messages
- Verify `storage` directory exists and is writable
- Check backend logs for validation errors

### Settings Reset After Restart
- Ensure `storage/settings.json` file exists
- Check file permissions
- Verify no errors in backend startup logs

### Validation Errors
- Check that values are within allowed ranges
- Verify enum values (theme, editor type) are correct
- Review error notification for specific details

## 🎯 Common Use Cases

### Change Git Port
1. Go to **Git** tab
2. Change port number (e.g., 9000)
3. Click **Save Changes**
4. Restart Git server for changes to take effect

### Switch to Light Theme
1. Go to **UI** tab
2. Click on **Light Theme** card
3. Click **Save Changes**
4. Refresh page if needed

### Configure Ollama
1. Go to **Ollama** tab
2. Ensure toggle is **ON**
3. Set URL: `http://localhost:11434`
4. Set model: `llama3.2` (or your preferred model)
5. Click **Save Changes**

### Set Custom Editor
1. Go to **Editor** tab
2. Enter full path in **Custom Editor Path**
   - Example: `C:\Program Files\MyEditor\editor.exe`
3. Click **Save Changes**

## 🔮 Future Enhancements

Potential improvements:
- Settings search/filter
- Settings history/undo
- Settings profiles
- Cloud sync
- Settings validation on frontend
- Real-time settings sync across tabs
- Settings backup/restore
- Advanced editor configuration
- Custom themes
- Keyboard shortcuts configuration

## 📝 Notes

- Settings are stored in `storage/settings.json`
- File is created automatically on first run
- Invalid settings fall back to defaults
- All changes are validated before saving
- Settings persist across restarts
- Frontend uses reactive Vue 3 Composition API
- Backend uses Pydantic for type safety

---

**Version:** 1.0.0  
**Last Updated:** 2026-01-28  
**Part of:** LocalGitMirror v3.0
