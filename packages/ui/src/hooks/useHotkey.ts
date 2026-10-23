import { useEffect, useRef } from 'react'

export interface Hotkey {
  key: string
  /** ⌘ on Mac, Ctrl elsewhere (either is accepted) */
  mod?: boolean
  meta?: boolean
  ctrl?: boolean
  shift?: boolean
  alt?: boolean
}

const KEY_ALIASES: Record<string, string> = {
  esc: 'escape',
  return: 'enter',
  space: ' ',
  up: 'arrowup',
  down: 'arrowdown',
  left: 'arrowleft',
  right: 'arrowright',
  del: 'delete',
  plus: '+',
}

/** 'mod+k' · 'shift+?' · 'escape' · 'ctrl+enter' */
export function parseHotkey(combo: string): Hotkey {
  const hk: Hotkey = { key: '' }
  for (const raw of combo.toLowerCase().split('+')) {
    const p = raw.trim()
    if (!p) continue
    if (p === 'mod') hk.mod = true
    else if (p === 'meta' || p === 'cmd' || p === 'command') hk.meta = true
    else if (p === 'ctrl' || p === 'control') hk.ctrl = true
    else if (p === 'shift') hk.shift = true
    else if (p === 'alt' || p === 'option') hk.alt = true
    else hk.key = KEY_ALIASES[p] ?? p
  }
  return hk
}

export function matchesHotkey(e: KeyboardEvent, hk: Hotkey): boolean {
  if (!e.key || e.key.toLowerCase() !== hk.key) return false
  if (hk.mod) {
    if (!(e.metaKey || e.ctrlKey)) return false
  } else {
    if (!!hk.meta !== e.metaKey) return false
    if (!!hk.ctrl !== e.ctrlKey) return false
  }
  if (!!hk.alt !== e.altKey) return false
  // symbols such as '?' or '/' already imply the shift state of the layout
  const symbol = hk.key.length === 1 && !/[a-z0-9]/.test(hk.key)
  if (!symbol && !!hk.shift !== e.shiftKey) return false
  return true
}

/** true for inputs, textareas, selects and contenteditable regions */
export function isEditableTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  if (!el || typeof el.closest !== 'function') return false
  const tag = el.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  return !!el.closest('[contenteditable=""], [contenteditable="true"]')
}

export interface HotkeyOptions {
  enabled?: boolean
  /** default true */
  preventDefault?: boolean
  /** fire even while typing in a field (modifier combos always fire) */
  allowInInputs?: boolean
}

/** Global keyboard shortcut. The handler always sees the latest closure. */
export function useHotkey(
  combo: string | string[],
  handler: (e: KeyboardEvent) => void,
  options: HotkeyOptions = {},
) {
  const { enabled = true, preventDefault = true, allowInInputs = false } = options
  const handlerRef = useRef(handler)
  useEffect(() => {
    handlerRef.current = handler
  })
  const combos = Array.isArray(combo) ? combo.join('|') : combo

  useEffect(() => {
    if (!enabled) return
    const parsed = combos.split('|').map(parseHotkey)
    const onKey = (e: KeyboardEvent) => {
      const hit = parsed.find((p) => matchesHotkey(e, p))
      if (!hit) return
      if (!allowInInputs && isEditableTarget(e.target) && !(hit.mod || hit.meta || hit.ctrl)) return
      if (preventDefault) e.preventDefault()
      handlerRef.current(e)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [combos, enabled, preventDefault, allowInInputs])
}
