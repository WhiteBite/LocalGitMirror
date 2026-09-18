/**
 * Side metadata for exchange entries, shared convention with the IDEA plugin.
 *
 * The plaintext inside hint_enc (buffer) / path_enc (postbox) is a JSON object
 * {"s":"h"|"w", "h"|"n":"<preview or real name>"} where s is the sender side
 * (web client = "h" home, plugin = "w" work). Anything that does not parse as
 * that object is a legacy bare string: rendered as-is with unknown side.
 */

export const SIDE_HOME = 'h'
export const SIDE_WORK = 'w'

export function buildHintMeta(text) {
  const first = (text.split('\n')[0] || '').trim().slice(0, 80)
  return JSON.stringify({ s: SIDE_HOME, h: first })
}

export function buildPathMeta(name) {
  return JSON.stringify({ s: SIDE_HOME, n: name })
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
