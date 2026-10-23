import { DotTag } from '@iaf/console-kit'
import { roleTone } from '../lib/session'

/** A managed role as a chip: the role's colour in the dot, the name in the text colour, and `data-role` for the journeys. */
export function RoleTag({ role, size = 'sm', className }: { role: string; size?: 'sm' | 'md'; className?: string }) {
  return (
    <span data-role={role} className={className ? `inline-flex ${className}` : 'inline-flex'}>
      <DotTag size={size} tone={roleTone(role)}>
        {role}
      </DotTag>
    </span>
  )
}
