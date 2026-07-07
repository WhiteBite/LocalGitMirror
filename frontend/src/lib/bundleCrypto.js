/**
 * Client-side "bundle dump" crypto — byte-for-byte compatible with the plugin's
 * Kotlin `BundleCrypto` and the backend `bundle_crypto.py`.
 *
 * This is the format the IDEA plugin uses for the cross-machine clipboard buffer
 * (BundleCrypto.encryptBundleBytes / decryptDumpBytes), so the web Buffer UI must
 * use the SAME format to interoperate with entries pushed from the plugin.
 *
 * Wire format (v2, the only one we write):
 *   version(1) = 0x01
 *   salt(16)
 *   nonce(12)
 *   ciphertext_len(8, big-endian)   // length of AES-GCM output incl. 16-byte tag
 *   ciphertext (AES-256-GCM, 128-bit tag appended)
 *
 * Key derivation: PBKDF2-HMAC-SHA256(password, salt, 200_000) -> 32 bytes.
 *
 * Legacy v1 (magic "LGMSTRL1") is supported for reads only, mirroring the
 * other implementations.
 */

const FORMAT_VERSION = 0x01
const SALT_SIZE = 16
const NONCE_SIZE = 12
const PBKDF2_ITERATIONS = 200000
const KEY_BITS = 256
const TAG_BYTES = 16
const LEGACY_MAGIC = [0x4c, 0x47, 0x4d, 0x53, 0x54, 0x52, 0x4c, 0x31] // "LGMSTRL1"

function _assertCrypto() {
  if (!globalThis.crypto || !globalThis.crypto.subtle) {
    throw new Error('WebCrypto unavailable (requires HTTPS or localhost)')
  }
}

async function deriveKey(password, salt) {
  if (!password) throw new Error('Sync password not configured')
  _assertCrypto()
  const baseKey = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(password),
    { name: 'PBKDF2' },
    false,
    ['deriveKey']
  )
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt, iterations: PBKDF2_ITERATIONS, hash: 'SHA-256' },
    baseKey,
    { name: 'AES-GCM', length: KEY_BITS },
    false,
    ['encrypt', 'decrypt']
  )
}

function bytesToBase64(bytes) {
  let binary = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk))
  }
  return btoa(binary)
}

function base64ToBytes(b64) {
  const binary = atob(b64)
  const out = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i)
  return out
}

/**
 * Encrypt a UTF-8 string into a BundleCrypto v2 dump, returned base64-encoded
 * (ready to send as `ciphertext_b64` to /api/buffer).
 */
export async function encryptText(plaintext, password) {
  _assertCrypto()
  const salt = crypto.getRandomValues(new Uint8Array(SALT_SIZE))
  const nonce = crypto.getRandomValues(new Uint8Array(NONCE_SIZE))
  const key = await deriveKey(password, salt)
  const data = new TextEncoder().encode(plaintext)
  const cipherBuf = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: nonce, tagLength: TAG_BYTES * 8 },
    key,
    data
  )
  const cipher = new Uint8Array(cipherBuf)

  const out = new Uint8Array(1 + SALT_SIZE + NONCE_SIZE + 8 + cipher.length)
  let off = 0
  out[off] = FORMAT_VERSION
  off += 1
  out.set(salt, off); off += SALT_SIZE
  out.set(nonce, off); off += NONCE_SIZE
  // 8-byte big-endian ciphertext length
  new DataView(out.buffer).setBigUint64(off, BigInt(cipher.length), false)
  off += 8
  out.set(cipher, off)

  return bytesToBase64(out)
}

/**
 * Decrypt a BundleCrypto dump (raw bytes, v1 or v2) back to a UTF-8 string.
 */
export async function decryptBytes(dumpBytes, password) {
  _assertCrypto()
  const bytes = dumpBytes instanceof Uint8Array ? dumpBytes : new Uint8Array(dumpBytes)
  const minLen = 1 + SALT_SIZE + NONCE_SIZE + 8 + TAG_BYTES
  if (bytes.length < minLen) throw new Error('Dump too small')

  let cursor = 0
  const first = bytes[0]

  if (first === LEGACY_MAGIC[0]) {
    // v1: 8-byte "LGMSTRL1" magic prefix
    for (let i = 0; i < LEGACY_MAGIC.length; i++) {
      if (bytes[i] !== LEGACY_MAGIC[i]) throw new Error('Unsupported format')
    }
    cursor = LEGACY_MAGIC.length
  } else if (first === FORMAT_VERSION) {
    cursor = 1
  } else {
    throw new Error('Unsupported format version')
  }

  const salt = bytes.slice(cursor, cursor + SALT_SIZE); cursor += SALT_SIZE
  const nonce = bytes.slice(cursor, cursor + NONCE_SIZE); cursor += NONCE_SIZE
  const len = Number(new DataView(bytes.buffer, bytes.byteOffset + cursor, 8).getBigUint64(0, false))
  cursor += 8
  if (len < TAG_BYTES || cursor + len > bytes.length) throw new Error('Corrupted payload')
  const cipher = bytes.slice(cursor, cursor + len)

  const key = await deriveKey(password, salt)
  const plainBuf = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: nonce, tagLength: TAG_BYTES * 8 },
    key,
    cipher
  )
  return new TextDecoder().decode(plainBuf)
}

/** Convenience: decrypt from a base64-encoded dump string. */
export async function decryptText(b64, password) {
  return decryptBytes(base64ToBytes(b64), password)
}
