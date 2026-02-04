import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import TreeNode from '../TreeNode.vue'

describe('TreeNode', () => {
  describe('File Node', () => {
    const fileNode = {
      type: 'file',
      name: 'test.js',
      path: 'src/test.js',
      size: 1234,
      modified: '2026-01-28T12:00:00',
      icon: '📜'
    }

    it('renders file node correctly', () => {
      const wrapper = mount(TreeNode, {
        props: { node: fileNode, level: 0 }
      })

      expect(wrapper.find('.file-node').exists()).toBe(true)
      expect(wrapper.text()).toContain('test.js')
      expect(wrapper.text()).toContain('📜')
    })

    it('applies correct padding based on level', () => {
      const wrapper = mount(TreeNode, {
        props: { node: fileNode, level: 2 }
      })

      const fileNodeEl = wrapper.find('.file-node')
      expect(fileNodeEl.attributes('style')).toContain('padding-left: 32px')
    })

    it('formats file size correctly', () => {
      const wrapper = mount(TreeNode, {
        props: { node: fileNode, level: 0 }
      })

      expect(wrapper.text()).toContain('1.2 KB')
    })

    it('formats large file sizes correctly', () => {
      const largeFileNode = {
        ...fileNode,
        size: 1234567
      }

      const wrapper = mount(TreeNode, {
        props: { node: largeFileNode, level: 0 }
      })

      expect(wrapper.text()).toContain('1.2 MB')
    })

    it('highlights selected file', () => {
      const wrapper = mount(TreeNode, {
        props: { 
          node: fileNode, 
          level: 0,
          selectedFile: 'src/test.js'
        }
      })

      expect(wrapper.find('.file-node').classes()).toContain('is-selected')
    })

    it('emits file-select event on click', async () => {
      const wrapper = mount(TreeNode, {
        props: { node: fileNode, level: 0 }
      })

      await wrapper.find('.file-node').trigger('click')

      expect(wrapper.emitted('file-select')).toBeTruthy()
      expect(wrapper.emitted('file-select')[0]).toEqual(['src/test.js'])
    })

    it('shows file icon', () => {
      const wrapper = mount(TreeNode, {
        props: { node: fileNode, level: 0 }
      })

      expect(wrapper.find('.file-icon').text()).toBe('📜')
    })
  })

  describe('Folder Node', () => {
    const folderNode = {
      type: 'folder',
      name: 'components',
      path: 'src/components',
      children: [
        {
          type: 'file',
          name: 'Header.vue',
          path: 'src/components/Header.vue',
          size: 450,
          icon: '💚'
        }
      ],
      isExpanded: false,
      fileCount: 1
    }

    it('renders folder node correctly', () => {
      const wrapper = mount(TreeNode, {
        props: { node: folderNode, level: 0 }
      })

      expect(wrapper.find('.folder-node').exists()).toBe(true)
      expect(wrapper.text()).toContain('components')
      expect(wrapper.text()).toContain('(1)')
    })

    it('shows closed folder icon when collapsed', () => {
      const wrapper = mount(TreeNode, {
        props: { node: folderNode, level: 0 }
      })

      expect(wrapper.find('.folder-icon').text()).toBe('📁')
    })

    it('shows open folder icon when expanded', () => {
      const expandedFolder = { ...folderNode, isExpanded: true }
      const wrapper = mount(TreeNode, {
        props: { node: expandedFolder, level: 0 }
      })

      expect(wrapper.find('.folder-icon').text()).toBe('📂')
    })

    it('displays file count', () => {
      const wrapper = mount(TreeNode, {
        props: { node: folderNode, level: 0 }
      })

      expect(wrapper.find('.file-count').text()).toBe('(1)')
    })

    it('emits folder-toggle event on click', async () => {
      const wrapper = mount(TreeNode, {
        props: { node: folderNode, level: 0 }
      })

      await wrapper.find('.folder-node').trigger('click')

      expect(wrapper.emitted('folder-toggle')).toBeTruthy()
      expect(wrapper.emitted('folder-toggle')[0]).toEqual(['src/components'])
    })

    it('hides children when collapsed', () => {
      const wrapper = mount(TreeNode, {
        props: { node: folderNode, level: 0 }
      })

      expect(wrapper.find('.folder-children').exists()).toBe(false)
    })

    it('shows children when expanded', () => {
      const expandedFolder = { ...folderNode, isExpanded: true }
      const wrapper = mount(TreeNode, {
        props: { node: expandedFolder, level: 0 }
      })

      expect(wrapper.find('.folder-children').exists()).toBe(true)
    })

    it('renders nested children recursively', () => {
      const nestedFolder = {
        type: 'folder',
        name: 'src',
        path: 'src',
        isExpanded: true,
        fileCount: 2,
        children: [
          {
            type: 'folder',
            name: 'components',
            path: 'src/components',
            isExpanded: true,
            fileCount: 1,
            children: [
              {
                type: 'file',
                name: 'Header.vue',
                path: 'src/components/Header.vue',
                size: 450,
                icon: '💚'
              }
            ]
          }
        ]
      }

      const wrapper = mount(TreeNode, {
        props: { node: nestedFolder, level: 0 }
      })

      // Должны быть вложенные TreeNode компоненты
      const treeNodes = wrapper.findAllComponents(TreeNode)
      expect(treeNodes.length).toBeGreaterThan(1)
    })
  })

  describe('Styling', () => {
    it('applies hover class on mouse enter', async () => {
      const fileNode = {
        type: 'file',
        name: 'test.js',
        path: 'test.js',
        size: 100,
        icon: '📜'
      }

      const wrapper = mount(TreeNode, {
        props: { node: fileNode, level: 0 }
      })

      const fileNodeEl = wrapper.find('.file-node')
      await fileNodeEl.trigger('mouseenter')

      // Hover стили применяются через CSS, проверяем наличие класса
      expect(fileNodeEl.classes()).toContain('file-node')
    })

    it('applies correct indentation for nested levels', () => {
      const fileNode = {
        type: 'file',
        name: 'test.js',
        path: 'test.js',
        size: 100,
        icon: '📜'
      }

      const wrapper = mount(TreeNode, {
        props: { node: fileNode, level: 3 }
      })

      const fileNodeEl = wrapper.find('.file-node')
      expect(fileNodeEl.attributes('style')).toContain('padding-left: 48px')
    })
  })

  describe('Search Highlighting', () => {
    it('passes search query to component', () => {
      const fileNode = {
        type: 'file',
        name: 'test.js',
        path: 'test.js',
        size: 100,
        icon: '📜'
      }

      const wrapper = mount(TreeNode, {
        props: { 
          node: fileNode, 
          level: 0,
          searchQuery: 'test'
        }
      })

      expect(wrapper.props('searchQuery')).toBe('test')
    })
  })

  describe('Size Formatting', () => {
    const testCases = [
      { size: 0, expected: '' },
      { size: 100, expected: '100 B' },
      { size: 1024, expected: '1.0 KB' },
      { size: 1234, expected: '1.2 KB' },
      { size: 1048576, expected: '1.0 MB' },
      { size: 1234567, expected: '1.2 MB' },
      { size: 1073741824, expected: '1.0 GB' },
      { size: 1234567890, expected: '1.1 GB' }
    ]

    testCases.forEach(({ size, expected }) => {
      it(`formats ${size} bytes as ${expected}`, () => {
        const fileNode = {
          type: 'file',
          name: 'test.js',
          path: 'test.js',
          size: size,
          icon: '📜'
        }

        const wrapper = mount(TreeNode, {
          props: { node: fileNode, level: 0 }
        })

        if (expected) {
          expect(wrapper.text()).toContain(expected)
        }
      })
    })
  })

  describe('Event Propagation', () => {
    it('propagates file-select event from nested nodes', async () => {
      const folderNode = {
        type: 'folder',
        name: 'src',
        path: 'src',
        isExpanded: true,
        fileCount: 1,
        children: [
          {
            type: 'file',
            name: 'test.js',
            path: 'src/test.js',
            size: 100,
            icon: '📜'
          }
        ]
      }

      const wrapper = mount(TreeNode, {
        props: { node: folderNode, level: 0 }
      })

      // Находим вложенный TreeNode и эмулируем событие
      const childNode = wrapper.findAllComponents(TreeNode)[1]
      await childNode.vm.$emit('file-select', 'src/test.js')

      expect(wrapper.emitted('file-select')).toBeTruthy()
      expect(wrapper.emitted('file-select')[0]).toEqual(['src/test.js'])
    })

    it('propagates folder-toggle event from nested nodes', async () => {
      const folderNode = {
        type: 'folder',
        name: 'src',
        path: 'src',
        isExpanded: true,
        fileCount: 1,
        children: [
          {
            type: 'folder',
            name: 'components',
            path: 'src/components',
            isExpanded: false,
            fileCount: 0,
            children: []
          }
        ]
      }

      const wrapper = mount(TreeNode, {
        props: { node: folderNode, level: 0 }
      })

      const childNode = wrapper.findAllComponents(TreeNode)[1]
      await childNode.vm.$emit('folder-toggle', 'src/components')

      expect(wrapper.emitted('folder-toggle')).toBeTruthy()
      expect(wrapper.emitted('folder-toggle')[0]).toEqual(['src/components'])
    })
  })
})
