# SharedContextMenu Component

Универсальный компонент контекстного меню для файлов, папок и множественного выбора.

## Использование

```vue
<template>
  <div @contextmenu.prevent="showMenu">
    Right-click me
  </div>

  <SharedContextMenu
    :visible="menu.visible"
    :x="menu.x"
    :y="menu.y"
    :item="menu.item"
    :selected-count="menu.selectedCount"
    @close="menu.visible = false"
    @action="handleAction"
  />
</template>

<script setup>
import { ref } from 'vue'
import SharedContextMenu from '@/components/SharedContextMenu.vue'

const menu = ref({
  visible: false,
  x: 0,
  y: 0,
  item: null,
  selectedCount: 0
})

const showMenu = (event) => {
  menu.value = {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    item: {
      type: 'file',
      name: 'example.txt',
      path: '/path/to/file'
    },
    selectedCount: 0
  }
}

const handleAction = ({ type, item }) => {
  console.log('Action:', type, item)
  // Обработка действий
}
</script>
```

## Props

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `visible` | Boolean | `false` | Показывать/скрыть меню |
| `x` | Number | `0` | X координата позиции меню |
| `y` | Number | `0` | Y координата позиции меню |
| `item` | Object | `null` | Объект файла/папки |
| `selectedCount` | Number | `0` | Количество выбранных элементов |

## Events

### `@close`
Вызывается при закрытии меню (клик вне меню или Escape).

### `@action`
Вызывается при выборе действия из меню.

Payload: `{ type: string, item: object }`

## Типы меню

### File (файл)
Показывается когда `item.type === 'file'` или `selectedCount <= 1`:
- `view` - Просмотреть
- `rename` - Переименовать
- `tags` - Теги
- `history` - История
- `download` - Скачать
- `copy-path` - Копировать путь
- `delete` - Удалить

### Folder (папка)
Показывается когда `item.type === 'folder'` или `item.isDirectory === true`:
- `open` - Открыть
- `rename` - Переименовать
- `stats` - Статистика
- `delete` - Удалить

### Multiple (множественный выбор)
Показывается когда `selectedCount > 1`:
- `delete-multiple` - Удалить выбранные (N файлов)
- `download-zip` - Скачать как ZIP

## Особенности

- **Автоматическое позиционирование**: Меню автоматически корректирует позицию, чтобы не выходить за границы viewport
- **Анимации**: Плавное появление/исчезновение с использованием Vue Transition
- **Закрытие**: Автоматически закрывается при клике вне меню или нажатии Escape
- **Teleport**: Рендерится в `body` для корректного z-index

## Пример с FileTree

```vue
<template>
  <div 
    class="file-item"
    @contextmenu.prevent="showContextMenu($event, file)"
  >
    {{ file.name }}
  </div>

  <SharedContextMenu
    :visible="contextMenu.visible"
    :x="contextMenu.x"
    :y="contextMenu.y"
    :item="contextMenu.item"
    @close="contextMenu.visible = false"
    @action="handleContextAction"
  />
</template>

<script setup>
const contextMenu = ref({
  visible: false,
  x: 0,
  y: 0,
  item: null
})

const showContextMenu = (event, file) => {
  contextMenu.value = {
    visible: true,
    x: event.clientX,
    y: event.clientY,
    item: file
  }
}

const handleContextAction = async ({ type, item }) => {
  switch (type) {
    case 'view':
      // Открыть файл
      break
    case 'download':
      // Скачать файл
      break
    case 'delete':
      // Удалить файл
      break
  }
}
</script>
```
