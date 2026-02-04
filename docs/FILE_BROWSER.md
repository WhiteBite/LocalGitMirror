# File Browser Documentation

## 📁 Overview

Separate page for browsing and navigating files, similar to Windows Explorer or VS Code.

## ✨ Features

### 🗂️ Sidebar
- Tree structure of files and folders
- Expand/collapse folders
- File count in each folder
- Icons for different file types
- Repository selection

### 📍 Breadcrumbs Navigation
- Breadcrumb trail for current path
- Quick navigation through folders
- Current file display

### 📄 Preview Area
- Full-screen file viewing
- Markdown with Mermaid diagrams
- Code syntax highlighting
- PDF viewer
- Open in editor button

## 🚀 How to Use

### 1. Open File Browser
On the main page, click **"File Browser"** button or navigate to:
```
http://localhost:8000/files
```

### 2. Navigate Folders
- Click on a folder (📁) in the sidebar to expand
- Click again to collapse
- Folders automatically expand when selecting files inside

### 3. View Files
- Click on a file in the sidebar
- Content displays in the center area
- Breadcrumbs show the file path

### 4. Open in Editor
- Click **"✏️ Editor"** button in the top panel
- File opens in Cursor or VS Code

### 5. Refresh List
- Click **"↻"** button to refresh the file list

## 🎨 Interface

```
┌─────────────────────────────────────────────────────────────┐
│  File Browser                              ← Back           │
│  [Repository: default ▼]                                    │
├─────────────┬───────────────────────────────────────────────┤
│             │  🏠 Root / docs / architecture.md             │
│ 📁 config   │  [↻] [✏️ Editor]                              │
│ 📂 docs     ├───────────────────────────────────────────────┤
│   📝 api.md │                                               │
│   📝 arch.. │  # System Architecture                        │
│ 📁 src      │                                               │
│ 📝 README   │  [Mermaid diagram]                            │
│ 📄 notes    │                                               │
│             │  [File content...]                            │
│             │                                               │
└─────────────┴───────────────────────────────────────────────┘
```

## 📋 Supported Formats

### Markdown (.md)
- Full rendering
- Mermaid diagrams
- Code block highlighting
- Tables, links, images

### Code
- Python, JavaScript, TypeScript
- JSON, YAML, XML
- HTML, CSS
- And many more

### PDF
- Page-by-page viewing
- Forward/backward navigation
- Page counter

### Text
- .txt, .log files
- Any text formats

## ✅ Advantages

✅ **Full-screen mode** - More space for content  
✅ **Fast navigation** - Breadcrumbs and tree structure  
✅ **Convenient viewing** - Like Windows Explorer  
✅ **Separate page** - Doesn't interfere with main dashboard  
✅ **All features** - Markdown, Mermaid, PDF, code highlighting  

## ⌨️ Keyboard Shortcuts

- Click on folder - expand/collapse
- Click on file - open for viewing
- Breadcrumb - quick navigation

## 💡 Usage Examples

### View Documentation
1. Open file browser
2. Expand `docs/` folder
3. Click on `architecture.md`
4. See 5 Mermaid diagrams!

### View Code
1. Expand `src/` folder
2. Click on `utils.py`
3. Code displays with syntax highlighting

### Navigate Project
1. Use breadcrumbs for quick navigation
2. Click on folders to navigate
3. Counters show number of files

## 🔧 Technical Details

### Structure
- **Sidebar**: 280px fixed width (VS Code standard)
- **Content**: Responsive width
- **Responsive**: Works on all screens

### API
Uses the same endpoints:
- `/api/files` - File list
- `/api/file/view` - View text
- `/api/file/pdf` - View PDF
- `/api/repos` - Repository list

### Libraries
- Marked.js - Markdown
- Mermaid.js - Diagrams
- Highlight.js - Code highlighting
- PDF.js - PDF viewing

## 🔍 Comparison with Modal Window

| Feature | Modal Window | File Browser |
|---------|--------------|--------------|
| Screen Size | Limited | Full screen |
| Navigation | No | Breadcrumbs + Sidebar |
| Folder Tree | Yes | Yes, improved |
| Viewing | Popup window | Separate page |
| Convenience | Quick view | Full-featured work |

## 📌 When to Use

**Modal Window** (on main page):
- Quick file view
- Don't want to leave dashboard
- Need to quickly check content

**File Browser** (separate page):
- Working with documentation
- Exploring project structure
- Extended file viewing
- Navigating through multiple files

## 🐛 Troubleshooting

### File Tree Not Showing

**Problem:** Empty file tree or "EXPLORER" header with no files

**Solutions:**
1. Check if backend is running (`python main.py`)
2. Verify API endpoint: `curl http://localhost:8000/api/files`
3. Check browser console for errors
4. Verify FileTree component receives props

### File Preview Not Working

**Problem:** Clicking file doesn't show preview

**Solutions:**
1. Check event handlers are implemented
2. Verify `handleFileSelect` function exists
3. Check browser console for errors
4. Verify API returns file content

### Layout Issues

**Problem:** Sidebar too wide or content not displaying

**Solutions:**
1. Verify sidebar width is 280px
2. Check CSS classes are applied correctly
3. Clear browser cache
4. Check for conflicting styles

## 🎯 Component Architecture

```
FileBrowser.vue (Main View)
├── Header
│   ├── Title
│   ├── Search Input
│   └── Refresh Button
├── Sidebar (280px)
│   ├── Explorer Header
│   └── FileTree Component
│       └── TreeNode Components (recursive)
└── Preview Panel
    ├── File Header (when file selected)
    ├── FileViewer Component
    │   ├── MarkdownRenderer
    │   ├── CodeViewer
    │   └── PDFViewer
    └── Empty State (when no file selected)
```

## 📊 Data Flow

```
1. User navigates to /files
   ↓
2. FileBrowser.vue mounts
   ↓
3. onMounted() calls filesStore.fetchFiles('/')
   ↓
4. Store fetches from /api/files
   ↓
5. Files stored in filesStore.files
   ↓
6. FileTree receives files via props
   ↓
7. FileTree builds tree structure
   ↓
8. TreeNode components render recursively
   ↓
9. User clicks file
   ↓
10. file-select event emitted
   ↓
11. handleFileSelect() called
   ↓
12. filesStore.fetchFileContent() called
   ↓
13. Content fetched from /api/files/content
   ↓
14. FileViewer displays content
```

## 🔮 Future Enhancements

### Short Term
- Remove debug logging after verification
- Add keyboard shortcuts (arrow keys for navigation)
- Add file type icons (beyond emoji)
- Implement file size limits for preview

### Long Term
- Add backend search endpoint
- Implement lazy loading for large trees
- Add file upload functionality
- Add inline file editing
- Add file comparison view
- Add git diff integration

---

**Status:** ✅ COMPLETE  
**Version:** 3.2.0  
**Access:** http://localhost:8000/files  
**Part of:** LocalGitMirror v3.0
