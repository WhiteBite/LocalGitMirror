import i18next from 'i18next'
import LanguageDetector from 'i18next-browser-languagedetector'
import ru from './locales/ru.json'

i18next
  .use(LanguageDetector)
  .init({
    fallbackLng: 'ru',
    debug: false,
    resources: {
      ru: { translation: ru }
    }
  })

export default i18next
