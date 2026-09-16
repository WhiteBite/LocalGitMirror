import { createI18n } from 'vue-i18n'
import ru from './locales/ru.json'
import en from './locales/en.json'

const i18n = createI18n({
  legacy: false, // Usage with Composition API
  globalInjection: true, // Inject $t globally
  locale: 'ru',
  fallbackLocale: 'ru',
  messages: {
    ru,
    en
  }
})

export default i18n
