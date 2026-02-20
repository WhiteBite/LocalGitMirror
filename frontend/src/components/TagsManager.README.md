# TagsManager Component

Modal component for managing file tags with validation, autocomplete, and color coding.

## Features

- ✅ Modal overlay with smooth transitions
- ✅ Current tags display with color-coded badges
- ✅ Add/remove tags with animations
- ✅ Input validation (alphanumeric + hyphens only)
- ✅ Autocomplete with popular tags
- ✅ Keyboard navigation (Enter, Esc, Arrow keys)
- ✅ Maximum 10 tags limit
- ✅ Duplicate prevention
- ✅ Consistent color generation from tag names

## Usage

```vue
<template>
  <div>
    <button @click="showTagsModal = true">Manage Tags</button>
    
    <TagsManager
      :visible="showTagsModal"
      :folder="currentFolder"
      :file-path="currentFile"
      :current-tags="fileTags"
      @close="showTagsModal = false"
      @save="handleSaveTags"
    />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import TagsManager from '@/components/TagsManager.vue'

const showTagsModal = ref(false)
const currentFolder = ref('/projects')
const currentFile = ref('README.md')
const fileTags = ref(['important', 'documentation'])

function handleSaveTags({ tags }) {
  console.log('Saving tags:', tags)
  fileTags.value = tags
  showTagsModal.value = false
  
  // TODO: Call API to save tags
  // await axios.post('/api/files/tags', {
  //   path: currentFile.value,
  //   tags: tags
  // })
}
</script>
```

## Props

| Prop | Type | Required | Description |
|------|------|----------|-------------|
| `visible` | Boolean | Yes | Controls modal visibility |
| `folder` | String | Yes | Current folder path |
| `filePath` | String | Yes | File path being edited |
| `currentTags` | Array | No | Initial tags (default: []) |

## Events

| Event | Payload | Description |
|-------|---------|-------------|
| `close` | - | Emitted when modal is closed |
| `save` | `{ tags: string[] }` | Emitted when Save is clicked |

## Tag Validation Rules

- Minimum 2 characters
- Maximum 30 characters
- Only letters, numbers, and hyphens allowed
- No duplicates
- Maximum 10 tags per file

## Keyboard Shortcuts

- `Enter` - Add tag / Select suggestion
- `Esc` - Close suggestions / Close modal
- `↓` - Navigate suggestions down
- `↑` - Navigate suggestions up

## Popular Tags (Autocomplete)

The component includes these popular tags for autocomplete:

- important, todo, bug, feature
- documentation, refactor, test, review
- urgent, archived, draft, approved
- deprecated, experimental
- production, development

## Color Coding

Tags are automatically color-coded based on their name using a hash function. The same tag will always have the same color across the application.

## API Integration

To integrate with backend, add these endpoints:

```python
# Get file tags
@router.get("/api/files/tags")
async def get_file_tags(path: str):
    return {"tags": [...]}

# Update file tags
@router.post("/api/files/tags")
async def update_file_tags(path: str, tags: List[str]):
    return {"success": True, "tags": tags}
```

## Styling

The component uses dark theme colors matching the project's VS Code-like aesthetic:
- Background: `#1e1e1e`
- Borders: `#333`
- Accent: `#007acc`
- Text: `#e0e0e0`

All styles are scoped and use TailwindCSS-like utility patterns.
