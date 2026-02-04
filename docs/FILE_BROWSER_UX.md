# File Browser UX Improvements

## Overview
Enhanced File Browser with VS Code-like interface, keyboard shortcuts, context menu, and improved navigation.

## New Features

### 1. Context Menu (Right Click)
Right-click on any file to access quick actions:
- **Open in Editor** - Opens file in Cursor/VS Code
- **Copy Path** - Copies file path to clipboard
- **Copy Content** - Copies file content to clipboard
- **Show in Explorer** - Opens file location in system file explorer
- **Download** - Downloads the file

### 2. Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+P` | Focus search / Quick Open |
| `Ctrl+B` | Toggle sidebar visibility |
| `Ctrl+F` | Search in file (planned) |
| `Ctrl+Shift+C` | Copy file path |
| `Enter` | Open selected file in editor |
| `Escape` | Close file preview |
| `Right Click` | Open context menu |

### 3. Breadcrumbs Navigation
- Shows current file path as clickable breadcrumbs
- Click any part of the path to navigate
- Home icon for root directory

### 4. File Info Panel
Displays detailed information about selected file:
- File name
- Full path
- File size (formatted)
- Last modified date
- File type
- Line count (for text files)

### 5. Resizable Sidebar
- Drag the right edge of sidebar to resize
- Min width: 200px
- Max width: 600px
- Smooth resize with visual feedback

### 6. Improved UI/UX
- VS Code-style layout
- Fixed sidebar width (280px default)
- Flexible preview panel
- Loading/error/empty states
- Smooth animations
- Better visual hierarchy

## Usage

### Basic Navigation
1. Click on folders to expand/collapse
2. Click on files to preview
3. Use breadcrumbs to navigate up the tree
4. Search files using the search box

### Quick Actions
1. **Right-click** on any file for context menu
2. Press **Enter** to open file in editor
3. Press **Ctrl+Shift+C** to copy path
4. Press **Escape** to close preview

### Sidebar Management
1. Press **Ctrl+B** to toggle sidebar
2. Drag the right edge to resize
3. File info panel shows at bottom when file is selected

## Components

### New Components
- `ContextMenu.vue` - Right-click context menu
- `Breadcrumbs.vue` - Path navigation
- `FileInfoPanel.vue` - File information display

### New Composables
- `useKeyboardShortcuts.js` - Keyboard shortcut handler

### Updated Components
- `FileBrowserEnhanced.vue` - Main file browser with all features
- `TreeNode.vue` - Added context menu support
- `FileTree.vue` - Added context menu event handling

## Technical Details

### Context Menu
- Teleported to body for proper positioning
- Closes on click outside or Escape
- Positioned at mouse cursor
- Emits events for all actions

### Keyboard Shortcuts
- Global event listeners
- Prevents default browser behavior
- Composable for reusability
- Easy to extend

### Sidebar Resize
- Mouse drag to resize
- Constrained min/max width
- Smooth visual feedback
- Persists during session

## Future Improvements
- [ ] Fuzzy search (Ctrl+P quick open)
- [ ] Search in file content (Ctrl+F)
- [ ] Git status indicators
- [ ] File icons by type
- [ ] Drag & drop support
- [ ] Multi-file selection
- [ ] Keyboard navigation in tree

## Migration
Old `FileBrowser.vue` is kept for reference. New `FileBrowserEnhanced.vue` is now the default.

To switch back to old version:
```javascript
// frontend/src/router/index.js
component: () => import('@/views/FileBrowser.vue')
```

## Testing
1. Start frontend: `npm run dev` in `frontend/`
2. Navigate to `/files`
3. Test all keyboard shortcuts
4. Test context menu on files
5. Test sidebar resize
6. Test breadcrumbs navigation
