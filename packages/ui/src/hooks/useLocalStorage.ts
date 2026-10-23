import { useCallback, useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react'

function resolve<T>(initial: T | (() => T)): T {
  return typeof initial === 'function' ? (initial as () => T)() : initial
}

/**
 * useState persisted as JSON under `key`, synced across tabs. Falls back to the
 * initial value when storage is unavailable (private mode, server render).
 */
export function useLocalStorage<T>(
  key: string,
  initial: T | (() => T),
): [T, Dispatch<SetStateAction<T>>, () => void] {
  const initialRef = useRef(initial)
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = localStorage.getItem(key)
      if (raw !== null) return JSON.parse(raw) as T
    } catch {
      /* unavailable or unparsable: use the initial value */
    }
    return resolve(initial)
  })

  useEffect(() => {
    try {
      localStorage.setItem(key, JSON.stringify(value))
    } catch {
      /* ignore */
    }
  }, [key, value])

  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key !== key || e.newValue === null) return
      try {
        setValue(JSON.parse(e.newValue) as T)
      } catch {
        /* ignore */
      }
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [key])

  const remove = useCallback(() => {
    try {
      localStorage.removeItem(key)
    } catch {
      /* ignore */
    }
    setValue(resolve(initialRef.current))
  }, [key])

  return [value, setValue, remove]
}
