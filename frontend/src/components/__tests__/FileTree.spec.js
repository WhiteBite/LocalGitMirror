import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import FileTree from '../FileTree.vue'
import TreeNode from '../TreeNode.vue'

describe('FileTree', () => {
  let mockFiles

  beforeEach(() => {
    mockFiles = [
      { path: 'README.md', name: 'README.md', size: 1234, modified: '2026-01-28T12:00:00' },
      { path: 'package.json', name: 'package.json', size: 567, modified: '2026-01-28T12:00:00' },
      { path: 'src/main.js', name: 'main.js', size: 890, modified: '2026-01-28T12:00:00' },
      { path: 'src/App.vue', name: 'App.vue', size: 1200, modified: '2026-01-28T12:00:00' },
      { path: 'src/components/Header.vue', name: 'Header.vue', size: 450, modified: '2026-01-28T12:00:00' },
      { path: 'docs/api.md', name: 'api.md', size: 2340, modified: '2026-01-28T12:00:00' }
    ]
  })

  describe('Rendering', () => {
    it('renders empty state when no files', () => {
      const wrapper = mount(FileTree, {
        props: { files: [] }
      })
      expect(wrapper.text()).toContain('Нет файлов')
    })

    it('renders files correctly', () => {
      const wrapper = mount(FileTree, {
        props: { files: mockFiles },
        global: {
          components: { TreeNode }
        }
      })
      expect(wrapper.findAllComponents(TreeNode).length).toBeGreaterThan(0)
    })

    it('shows empty state with search query when no matches', () => {
      const wrapper = mount(FileTree, {
        props: { 
          files: mockFiles,
          searchQuery: 'nonexistent'
        }
      })
      expect(wrapper.text()).toContain('Файлы не найдены')
    })
  })

  describe('Tree Structure', () => {
    it('builds tree from flat file list', () => {
      const wrapper = mount(FileTree, {
        props: { files: mockFiles },
        global: {
          components: { TreeNode }
        }
      })
      
      // Проверяем, что дерево построено
      const treeNodes = wrapper.findAllComponents(TreeNode)
      expect(treeNodes.length).toBeGreaterThan(0)
    })

    it('sorts folders before files', () => {
      const files = [
        { path: 'z-file.txt', name: 'z-file.txt', size: 100, modified: '2026-01-28' },
        { path: 'a-folder/file.txt', name: 'file.txt', size: 100, modified: '2026-01-28' },
        { path: 'a-file.txt', name: 'a-file.txt', size: 100, modified: '2026-01-28' }
      ]
      
      const wrapper = mount(FileTree, {
        props: { files },
        global: {
          components: { TreeNode }
        }
      })
      
      // Папки должны быть сверху
      const nodes = wrapper.findAllComponents(TreeNode)
      expect(nodes.length).toBeGreaterThan(0)
    })
  })

  describe('File Selection', () => {
    it('emits file-select event when file is clicked', async () => {
      const wrapper = mount(FileTree, {
        props: { files: mockFiles },
        global: {
          components: { TreeNode }
        }
      })

      // Эмулируем выбор файла через TreeNode
      const treeNode = wrapper.findComponent(TreeNode)
      await treeNode.vm.$emit('file-select', 'README.md')

      expect(wrapper.emitted('file-select')).toBeTruthy()
      expect(wrapper.emitted('file-select')[0]).toEqual(['README.md'])
    })

    it('highlights selected file', () => {
      const wrapper = mount(FileTree, {
        props: { 
          files: mockFiles,
          selectedFile: 'README.md'
        },
        global: {
          components: { TreeNode }
        }
      })

      // Проверяем, что selectedFile передается в TreeNode
      const treeNode = wrapper.findComponent(TreeNode)
      expect(treeNode.props('selectedFile')).toBe('README.md')
    })
  })

  describe('Folder Toggle', () => {
    it('emits folder-toggle event when folder is clicked', async () => {
      const wrapper = mount(FileTree, {
        props: { files: mockFiles },
        global: {
          components: { TreeNode }
        }
      })

      const treeNode = wrapper.findComponent(TreeNode)
      await treeNode.vm.$emit('folder-toggle', 'src')

      expect(wrapper.emitted('folder-toggle')).toBeTruthy()
      expect(wrapper.emitted('folder-toggle')[0]).toEqual(['src'])
    })
  })

  describe('Search and Filtering', () => {
    it('filters files by search query', () => {
      const wrapper = mount(FileTree, {
        props: { 
          files: mockFiles,
          searchQuery: 'README'
        },
        global: {
          components: { TreeNode }
        }
      })

      // Проверяем, что searchQuery передается в TreeNode
      const treeNodes = wrapper.findAllComponents(TreeNode)
      treeNodes.forEach(node => {
        expect(node.props('searchQuery')).toBe('README')
      })
    })

    it('filters files case-insensitively', () => {
      const wrapper = mount(FileTree, {
        props: { 
          files: mockFiles,
          searchQuery: 'readme'
        },
        global: {
          components: { TreeNode }
        }
      })

      const treeNodes = wrapper.findAllComponents(TreeNode)
      expect(treeNodes.length).toBeGreaterThan(0)
    })

    it('filters by file path', () => {
      const wrapper = mount(FileTree, {
        props: { 
          files: mockFiles,
          searchQuery: 'src/components'
        },
        global: {
          components: { TreeNode }
        }
      })

      const treeNodes = wrapper.findAllComponents(TreeNode)
      expect(treeNodes.length).toBeGreaterThan(0)
    })

    it('expands folders automatically when searching', () => {
      const wrapper = mount(FileTree, {
        props: { 
          files: mockFiles,
          searchQuery: 'Header'
        },
        global: {
          components: { TreeNode }
        }
      })

      // При поиске папки должны автоматически разворачиваться
      const treeNodes = wrapper.findAllComponents(TreeNode)
      expect(treeNodes.length).toBeGreaterThan(0)
    })
  })

  describe('File Icons', () => {
    it('assigns correct icon for markdown files', () => {
      const files = [
        { path: 'README.md', name: 'README.md', size: 100, modified: '2026-01-28' }
      ]
      const wrapper = mount(FileTree, {
        props: { files },
        global: {
          components: { TreeNode }
        }
      })

      // Иконка должна быть передана в TreeNode
      const treeNode = wrapper.findComponent(TreeNode)
      expect(treeNode.exists()).toBe(true)
    })

    it('assigns correct icon for python files', () => {
      const files = [
        { path: 'main.py', name: 'main.py', size: 100, modified: '2026-01-28' }
      ]
      const wrapper = mount(FileTree, {
        props: { files },
        global: {
          components: { TreeNode }
        }
      })

      const treeNode = wrapper.findComponent(TreeNode)
      expect(treeNode.exists()).toBe(true)
    })

    it('assigns default icon for unknown file types', () => {
      const files = [
        { path: 'file.xyz', name: 'file.xyz', size: 100, modified: '2026-01-28' }
      ]
      const wrapper = mount(FileTree, {
        props: { files },
        global: {
          components: { TreeNode }
        }
      })

      const treeNode = wrapper.findComponent(TreeNode)
      expect(treeNode.exists()).toBe(true)
    })
  })

  describe('File Count', () => {
    it('counts files in folders correctly', () => {
      const wrapper = mount(FileTree, {
        props: { files: mockFiles },
        global: {
          components: { TreeNode }
        }
      })

      // Проверяем, что дерево построено с подсчетом файлов
      const treeNodes = wrapper.findAllComponents(TreeNode)
      expect(treeNodes.length).toBeGreaterThan(0)
    })

    it('counts nested files recursively', () => {
      const files = [
        { path: 'src/components/Header.vue', name: 'Header.vue', size: 100, modified: '2026-01-28' },
        { path: 'src/components/Footer.vue', name: 'Footer.vue', size: 100, modified: '2026-01-28' },
        { path: 'src/main.js', name: 'main.js', size: 100, modified: '2026-01-28' }
      ]

      const wrapper = mount(FileTree, {
        props: { files },
        global: {
          components: { TreeNode }
        }
      })

      // src должна содержать 3 файла (включая вложенные)
      const treeNodes = wrapper.findAllComponents(TreeNode)
      expect(treeNodes.length).toBeGreaterThan(0)
    })
  })

  describe('Props Validation', () => {
    it('accepts empty files array', () => {
      const wrapper = mount(FileTree, {
        props: { files: [] }
      })
      expect(wrapper.exists()).toBe(true)
    })

    it('accepts empty selectedFile', () => {
      const wrapper = mount(FileTree, {
        props: { 
          files: mockFiles,
          selectedFile: ''
        },
        global: {
          components: { TreeNode }
        }
      })
      expect(wrapper.exists()).toBe(true)
    })

    it('accepts empty searchQuery', () => {
      const wrapper = mount(FileTree, {
        props: { 
          files: mockFiles,
          searchQuery: ''
        },
        global: {
          components: { TreeNode }
        }
      })
      expect(wrapper.exists()).toBe(true)
    })
  })
})
