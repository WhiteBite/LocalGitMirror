import { createApp } from 'vue'
import { createPinia } from 'pinia'
import axios from 'axios'
import App from './App.vue'
import router from './router'
import './style.css'

// Set default axios headers for API Key
const apiKey = import.meta.env.VITE_API_KEY || 'stealth-bridge-token-2026'
axios.defaults.headers.common['X-API-Key'] = apiKey

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
