// hint_enc plaintext: {"s":"h"|"w","h":"<preview>"}; legacy bare strings render as-is.

export const SIDE_HOME = 'h'
export const SIDE_WORK = 'w'

export function buildHintMeta(text) {
  const first = (text.split('\n')[0] || '').trim().slice(0, 80)
  return JSON.stringify({ s: SIDE_HOME, h: first })
}

export function parseMeta(decrypted, field) {
  try {
    const obj = JSON.parse(decrypted)
    if (obj && (obj.s === SIDE_HOME || obj.s === SIDE_WORK) && typeof obj[field] === 'string') {
      return { side: obj.s, text: obj[field] }
    }
  } catch {
    // legacy bare-string metadata
  }
  return { side: '', text: decrypted }
}
