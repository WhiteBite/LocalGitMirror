# Quick Start Guide

## Installation

```bash
cd frontend
npm install
```

## Development

Start the development server:
```bash
npm run dev
```

The application will be available at: http://localhost:5173

**Important:** Make sure the FastAPI backend is running on port 8000 before starting the frontend.

## Backend Integration

The Vite dev server automatically proxies requests:
- API calls to `/api/*` are forwarded to `http://localhost:8000`
- WebSocket connections to `/ws` are forwarded to `ws://localhost:8000`

## First Steps

1. **Start Backend**: Ensure your FastAPI server is running on port 8000
2. **Install Dependencies**: Run `npm install` in the frontend directory
3. **Start Dev Server**: Run `npm run dev`
4. **Open Browser**: Navigate to http://localhost:5173

## Available Routes

- `/` - Dashboard (system overview, stats, recent activity)
- `/files` - File Browser (navigate and preview files)
- `/settings` - Settings (configuration, Git service control)

## Project Structure Overview

```
frontend/
├── src/
│   ├── main.js              # Entry point
│   ├── App.vue              # Root component with navigation
│   ├── style.css            # Global styles + Tailwind
│   ├── router/
│   │   └── index.js         # Route definitions
│   ├── stores/              # Pinia state management
│   │   ├── files.js         # File operations
│   │   ├── repos.js         # Repository management
│   │   └── system.js        # System status & settings
│   ├── views/               # Page components
│   │   ├── Dashboard.vue
│   │   ├── FileBrowser.vue
│   │   └── Settings.vue
│   ├── components/          # Reusable components (empty, ready for use)
│   └── assets/              # Static files (empty, ready for use)
├── index.html               # HTML template
├── vite.config.js           # Vite + proxy configuration
├── tailwind.config.js       # Tailwind CSS config
└── package.json             # Dependencies
```

## Key Features Implemented

### State Management (Pinia Stores)

**Files Store:**
- File listing and navigation
- File content preview
- Search functionality
- Breadcrumb navigation

**Repos Store:**
- Repository CRUD operations
- Branch and commit tracking
- Sync operations
- Repository statistics

**System Store:**
- System status monitoring
- Git service control (start/stop)
- Settings management
- WebSocket for real-time updates
- Notification system

### UI Components

All views are fully implemented with:
- Dark theme by default
- Responsive design
- Loading states
- Error handling
- Smooth transitions

## Development Tips

1. **Hot Module Replacement**: Changes are reflected instantly
2. **Vue DevTools**: Install browser extension for debugging
3. **Tailwind IntelliSense**: Use VS Code extension for class autocomplete
4. **API Errors**: Check browser console and Network tab if API calls fail

## Common Issues

### Port Already in Use
If port 5173 is busy, Vite will automatically try the next available port.

### API Connection Failed
- Verify backend is running on port 8000
- Check browser console for CORS errors
- Ensure proxy configuration in vite.config.js is correct

### WebSocket Connection Failed
- Backend must support WebSocket at `/ws` endpoint
- Check browser console for connection errors

## Next Steps

1. Add custom components in `src/components/`
2. Implement additional features in views
3. Extend stores with more API endpoints
4. Customize Tailwind theme in `tailwind.config.js`
5. Add more routes in `src/router/index.js`

## Build for Production

```bash
npm run build
```

Output will be in `dist/` directory, ready to serve statically.

Preview production build:
```bash
npm run preview
```
