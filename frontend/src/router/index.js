import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'dashboard',
      component: () => import('@/views/Dashboard.vue'),
      meta: { title: 'Dashboard' }
    },
    {
      path: '/files',
      name: 'files',
      component: () => import('@/views/FileBrowser.vue'),
      meta: { title: 'File Browser' }
    },
    {
      path: '/search',
      name: 'search',
      component: () => import('@/views/SearchView.vue'),
      meta: { title: 'Global Search' }
    },
    {
      path: '/history',
      name: 'history',
      component: () => import('@/views/CommitsView.vue'),
      meta: { title: 'Commit History' }
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/Settings.vue'),
      meta: { title: 'Settings' }
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      redirect: '/'
    }
  ]
})

// Update document title on route change
router.beforeEach((to, from, next) => {
  document.title = to.meta.title ? `${to.meta.title} - LocalGitMirror` : 'LocalGitMirror'
  next()
})

export default router
