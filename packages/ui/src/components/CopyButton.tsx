import { useEffect, useRef, useState } from 'react'
import { Button, IconButton, type ButtonSize } from './Button'
import { Icon } from './Icon'

async function copyText(text: string): Promise<boolean> {
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* fall through to the legacy path */
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

function useCopied() {
  const [copied, setCopied] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current)
    },
    [],
  )
  const copy = async (text: string) => {
    if (await copyText(text)) {
      setCopied(true)
      if (timer.current) clearTimeout(timer.current)
      timer.current = setTimeout(() => setCopied(false), 1400)
    }
  }
  return { copied, copy }
}

export function CopyButton({ text, label = 'Copy', size = 'sm' }: { text: string; label?: string; size?: ButtonSize }) {
  const { copied, copy } = useCopied()
  return (
    <Button size={size} onClick={() => void copy(text)}>
      <Icon name={copied ? 'check' : 'copy'} size={12} className={copied ? 'text-healthy' : undefined} />
      {copied ? 'Copied' : label}
    </Button>
  )
}

/** Icon-only variant for dense rows — flashes a check on copy. */
export function CopyIconButton({ text, label = 'Copy', className }: { text: string; label?: string; className?: string }) {
  const { copied, copy } = useCopied()
  return (
    <IconButton label={copied ? 'Copied' : label} size="sm" className={className} onClick={() => void copy(text)}>
      <Icon name={copied ? 'check' : 'copy'} size={13} className={copied ? 'text-healthy' : 'text-tertiary'} />
    </IconButton>
  )
}
