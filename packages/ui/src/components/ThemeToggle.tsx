import { resolveTheme, type ThemeMode } from '../hooks/useTheme'
import { Icon } from './Icon'
import { IconButton } from './Button'

/** Sun in light mode, moon in dark mode; click flips between them. */
export function ThemeToggle({ mode, onCycle, className }: { mode: ThemeMode; onCycle: () => void; className?: string }) {
  const dark = resolveTheme(mode) === 'dark'
  return (
    <IconButton
      label={dark ? 'Switch to light mode' : 'Switch to dark mode'}
      onClick={onCycle}
      className={className ?? 'text-secondary'}
    >
      <Icon name={dark ? 'moon' : 'sun'} size={18} />
    </IconButton>
  )
}
