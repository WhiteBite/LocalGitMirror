import { createI18n } from 'vue-i18n'
import ru from './locales/ru.json'

const i18n = createI18n({
  legacy: false, // Usage with Composition API
  globalInjection: true, // Inject $t globally
  locale: 'ru',
  fallbackLng: 'ru',
  messages: {
    ru
  }
})

export default i18n
