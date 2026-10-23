import { DotTag } from '@iaf/console-kit'
import { roleLabel, roleTone } from '../lib/session'

/** A role as a chip: the role's colour in the dot, its label in the text colour (Admin for account-admin), and `data-role` (the role's name) for the journeys. */
export function RoleTag({ role, size = 'sm', className }: { role: string; size?: 'sm' | 'md'; className?: string }) {
  return (
    <span data-role={role} className={className ? `inline-flex ${className}` : 'inline-flex'}>
      <DotTag size={size} tone={roleTone(role)}>
        {roleLabel(role)}
      </DotTag>
    </span>
  )
}
