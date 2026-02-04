# LocalGitMirror Frontend

Vue.js 3 frontend application for LocalGitMirror.

## Tech Stack

- **Vue 3** - Progressive JavaScript framework with Composition API
- **Vite** - Next generation frontend tooling
- **Vue Router** - Official router for Vue.js
- **Pinia** - State management for Vue
- **TailwindCSS** - Utility-first CSS framework
- **Axios** - HTTP client for API requests

## Additional Libraries

- **marked** - Markdown parser and compiler
- **mermaid** - Diagram and flowchart generation
- **highlight.js** - Syntax highlighting
- **pdfjs-dist** - PDF rendering

## Project Structure

```
frontend/
├── src/
│   ├── assets/          # Static assets (images, fonts, etc.)
│   ├── components/      # Reusable Vue components
│   ├── views/           # Page components
│   │   ├── Dashboard.vue
│   │   ├── FileBrowser.vue
│   │   └── Settings.vue
│   ├── stores/          # Pinia stores
│   │   ├── files.js     # File management state
│   │   ├── repos.js     # Repository management state
│   │   └── system.js    # System state and settings
│   ├── router/          # Vue Router configuration
│   │   └── index.js
│   ├── App.vue          # Root component
│   ├── main.js          # Application entry point
│   └── style.css        # Global styles with Tailwind
├── index.html           # HTML entry point
├── vite.config.js       # Vite configuration
├── tailwind.config.js   # Tailwind CSS configuration
├── postcss.config.js    # PostCSS configuration
└── package.json         # Dependencies and scripts
```

## Setup

1. Install dependencies:
```bash
npm install
```

2. Start development server:
```bash
npm run dev
```

The application will be available at `http://localhost:5173`

## Development

### API Proxy

The Vite dev server is configured to proxy API requests:
- `/api/*` → `http://localhost:8000/api/*`
- `/ws` → `ws://localhost:8000/ws`

Make sure the FastAPI backend is running on port 8000.

### Hot Module Replacement

Vite provides instant HMR for a smooth development experience. Changes to Vue components, styles, and JavaScript will be reflected immediately without full page reloads.

## Build

Build for production:
```bash
npm run build
```

Preview production build:
```bash
npm run preview
```

## Features

### Dashboard
- System status overview
- Repository statistics
- Recent activity logs
- Storage usage monitoring

### File Browser
- Navigate repository files
- File preview
- Search functionality
- Breadcrumb navigation

### Settings
- General configuration
- Git service management
- Appearance customization
- System information

## State Management

### Files Store (`stores/files.js`)
- File listing and navigation
- File content fetching
- Search functionality
- Current folder tracking

### Repos Store (`stores/repos.js`)
- Repository management
- Branch and commit tracking
- Sync operations
- Repository CRUD operations

### System Store (`stores/system.js`)
- System status monitoring
- Settings management
- Git service control
- WebSocket connection for real-time updates
- Notification system

## Styling

The application uses TailwindCSS with a dark theme by default. Custom utility classes are defined in `src/style.css`:

- `.btn`, `.btn-primary`, `.btn-secondary` - Button styles
- `.card` - Card container
- `.input` - Form input styles

## WebSocket Integration

The system store includes WebSocket support for real-time updates:
- System status changes
- Log streaming
- Notifications

Connect to WebSocket on component mount:
```javascript
import { useSystemStore } from '@/stores/system'

const systemStore = useSystemStore()
systemStore.connectWebSocket()
```

## Contributing

When adding new features:
1. Create components in `src/components/`
2. Add new pages in `src/views/`
3. Update routes in `src/router/index.js`
4. Add state management in appropriate store
5. Follow Vue 3 Composition API patterns
6. Use TailwindCSS for styling
