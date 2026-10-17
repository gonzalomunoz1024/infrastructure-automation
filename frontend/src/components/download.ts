/** Hands a blob to the browser as a file download (exports, evidence packs, CSV templates). */
export function downloadBlob(blob: Blob, filename: string) {
  if (typeof document === 'undefined') return
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function downloadText(text: string, filename: string, type = 'text/plain;charset=utf-8') {
  downloadBlob(new Blob([text], { type }), filename)
}

export function downloadJson(value: unknown, filename: string) {
  downloadText(JSON.stringify(value, null, 2), filename, 'application/json')
}

/** A file name that is safe on every OS: spaces and odd characters become dashes. */
export function safeFilename(s: string): string {
  return s.replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'download'
}
