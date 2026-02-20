import { createApp } from 'vue'
import { createPinia } from 'pinia'
import axios from 'axios'
import App from './App.vue'
import router from './router'
import './style.css'
import i18n from './i18n'
import 'tippy.js/dist/tippy.css'
import { plugin as VueTippy } from 'vue-tippy'

// Set default axios headers for API Key
const apiKey = import.meta.env.VITE_API_KEY || 'stealth-bridge-token-2026'
axios.defaults.headers.common['X-API-Key'] = apiKey

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(i18n)
app.use(VueTippy, {
  defaultProps: { placement: 'top', allowHTML: true },
})

app.mount('#app')
