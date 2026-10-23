import { Card, Icon, PageHeader, Tag, type IconName } from '@iaf/ui'
import { Facts } from '@iaf/console-kit'
import { useConsole } from '../app/console'
import { PHASES } from '../app/Phase'
import type { PageProps } from '../app/router'

/*
 * Identity and access: who you are to the console, and the managed roles phase 2 brings. The
 * roles are listed now so teams can plan around them; nothing here grants anything yet.
 */

const ROLES: { name: string; icon: IconName; summary: string; can: string[] }[] = [
  { name: 'Viewer', icon: 'eye', summary: 'Sees every resource and request of the account.', can: ['Browse services, types and resources', 'Read request histories and step details', 'Export what is on screen'] },
  { name: 'Operator', icon: 'play', summary: 'Everything a viewer sees, plus the day-to-day changes.', can: ['Start the workflows of the catalog', 'Approve requests within their team', 'Restart, resize and patch resources'] },
  { name: 'Admin', icon: 'shield', summary: 'Runs the account: people, roles and destructive actions.', can: ['Grant and revoke roles', 'Delete resources and cancel requests', 'Change account-wide settings'] },
  { name: 'Auditor', icon: 'audit', summary: 'Read-only, including the records others cannot see.', can: ['Read every request, including denied ones', 'Read the audit trail and policy decisions', 'Export evidence for reviews'] },
]

export default function IamPage(_props: PageProps) {
  const { me, account } = useConsole()
  return (
    <>
      <PageHeader title="Identity and access" subtitle="Who you are to the console, and what the managed roles will let people do." />
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <Card title="You" subtitle="From the platform's identity call" className="lg:col-span-1">
          {me ? (
            <Facts
              columns={2}
              items={[
                { label: 'Name', value: me.name },
                { label: 'Id', value: <span className="font-mono">{me.id}</span> },
                { label: 'Email', value: me.email ?? 'Not set' },
                { label: 'Roles', value: me.roles?.length ? me.roles.join(', ') : 'None' },
                { label: 'Teams', value: me.teams?.length ? me.teams.join(', ') : 'None', wide: true },
                { label: 'Account', value: `${account.name} (${account.id})`, wide: true },
              ]}
            />
          ) : (
            <p className="text-sm text-secondary">The platform has not answered the identity call yet.</p>
          )}
        </Card>
        <Card title="Managed roles" subtitle={`Arrive with ${PHASES[2]}. Listed now so teams can plan.`} className="lg:col-span-2">
          <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {ROLES.map((r) => (
              <li key={r.name} className="rounded-md bg-surface-secondary p-4">
                <div className="flex items-center gap-2">
                  <span className="flex size-7 items-center justify-center rounded-md bg-accent-tint text-accent">
                    <Icon name={r.icon} size={15} />
                  </span>
                  <span className="text-sm font-semibold text-primary">{r.name}</span>
                  <Tag size="sm" outline className="ml-auto">
                    phase 2
                  </Tag>
                </div>
                <p className="mt-2 text-caption text-secondary">{r.summary}</p>
                <ul className="mt-2 space-y-1 text-caption text-secondary">
                  {r.can.map((c) => (
                    <li key={c} className="flex items-start gap-1.5">
                      <Icon name="check" size={12} className="mt-0.5 shrink-0 text-tertiary" />
                      {c}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        </Card>
      </div>
    </>
  )
}
