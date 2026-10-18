import { useEffect, useRef, useState, type FormEvent } from 'react'
import {
  Button,
  Card,
  CardRow,
  Checkbox,
  CodeBlock,
  CopyButton,
  Field,
  Icon,
  Input,
  Modal,
  OverlayBody,
  OverlayFooter,
  OverlayHeader,
  PageHeader,
  Segmented,
  Skeleton,
  StatusDot,
  Table,
  Tag,
  cx,
  longDate,
  relativeTime,
  useOverlayClose,
  useTheme,
  useToast,
  type DotKind,
  type TableColumn,
} from '@iaf/ui'
import type { PageProps } from '../app/router'
import { API_BASE, api, errorMessage, isApiError, setCurrentUser, useCurrentUser } from '../lib/api'
import { useMutation, useQuery } from '../lib/queries'
import type { ActuatorHealth, CreateServiceAccount, Me, ServiceAccount, ServiceAccountCreated, ServiceAccountScope } from '../lib/types'
import { ApiLinks } from '../components/ApiLinks'
import { ExternalLink } from '../components/common'
import { IDENTITY_DOC, docsUrl } from '../lib/docs'
import { PRESETS, PRESET_INFO, SCOPE_GROUPS, fitsPreset, orderScopes, presetOf, scopesRequest, type PresetChoice } from '../components/service-account-scopes'

const DEV_USERS = ['gonzalo', 'ana', 'mateo', 'junior'] as const
const ROLES = ['ops', 'sre', 'admin'] as const

const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1)

/**
 * Only a platform admin lists or creates service accounts; anyone else gets a 403 (docs/api.md,
 * Identity). Known from /me, or from the 403 itself while /me has not answered.
 */
export function adminsOnly(me: Me | undefined, error: unknown): boolean {
  return me?.admin === false || (isApiError(error) && error.status === 403)
}

export default function SettingsPage({ route }: PageProps) {
  const { mode, setMode } = useTheme()
  const user = useCurrentUser()
  const me = useQuery(['me', user ?? 'default'], () => api.me())
  const health = useQuery('health', () => api.health(), { refetchInterval: 30_000 })
  const info = useQuery('info', () => api.info())
  // the list depends on who asks: a non-admin's is a 403, so it is not asked for once /me says so
  const accounts = useQuery(['service-accounts', user ?? 'default'], () => api.serviceAccounts(), { enabled: me.data?.admin !== false })
  const notAdmin = adminsOnly(me.data, accounts.error)
  const [creating, setCreating] = useState(false)
  const [created, setCreated] = useState<ServiceAccountCreated | null>(null)

  // "Switch user…" from the user menu lands on the identity card
  const identityRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (route.query.section === 'identity') identityRef.current?.scrollIntoView({ block: 'start', behavior: 'smooth' })
  }, [route.query.section])

  const columns: TableColumn<ServiceAccount>[] = [
    {
      key: 'name',
      header: 'Name',
      sortable: true,
      accessor: (a) => a.name,
      render: (a) => (
        <div className="min-w-0">
          <div className="font-medium text-primary">{a.name}</div>
          <div className="truncate font-mono text-caption text-tertiary">{a.id}</div>
        </div>
      ),
    },
    {
      key: 'roles',
      header: 'Roles',
      render: (a) => (
        <div className="flex flex-wrap gap-1">
          {a.roles.map((r) => (
            <Tag key={r} tone={r === 'admin' ? 'accent' : 'neutral'}>
              {r}
            </Tag>
          ))}
        </div>
      ),
    },
    {
      key: 'teams',
      header: 'Teams',
      render: (a) =>
        a.teams && a.teams.length > 0 ? (
          <div className="flex flex-wrap gap-1">
            {a.teams.map((t) => (
              <Tag key={t} outline>
                {t}
              </Tag>
            ))}
          </div>
        ) : (
          <span className="text-tertiary">—</span>
        ),
    },
    {
      key: 'scopes',
      header: 'Scopes',
      render: (a) => <ScopeTags scopes={a.scopes} />,
    },
    {
      key: 'createdAt',
      header: 'Created',
      sortable: true,
      align: 'right',
      accessor: (a) => a.createdAt,
      render: (a) => (
        <span className="tabular-nums" title={longDate(a.createdAt)}>
          {relativeTime(a.createdAt)}
        </span>
      ),
    },
  ]

  return (
    <>
      <PageHeader title="Settings" subtitle="Appearance, who you act as, service accounts and the environment this shell talks to." />

      <div className="max-w-3xl space-y-5">
        <Card title="Appearance" subtitle="Follows the system unless you say otherwise.">
          <CardRow label="Theme" description="Light, dark, or whatever the OS is set to.">
            <Segmented
              ariaLabel="Theme"
              value={mode}
              onChange={setMode}
              options={[
                { value: 'light', label: 'Light', icon: 'sun' },
                { value: 'dark', label: 'Dark', icon: 'moon' },
                { value: 'system', label: 'System', icon: 'laptop' },
              ]}
            />
          </CardRow>
        </Card>

        <div ref={identityRef}>
          <Card title="Identity" subtitle="Who the API runs your requests as. Locally that is the person the X-User header names, and this shell sends it.">
            <CardRow label="Act as" description={user ? `Sending X-User: ${user}` : 'No header: the backend default applies'}>
              <Segmented
                ariaLabel="Development user"
                value={user ?? 'default'}
                onChange={(v) => setCurrentUser(v === 'default' ? null : v)}
                options={[{ value: 'default', label: 'Default' }, ...DEV_USERS.map((u) => ({ value: u, label: capitalize(u) }))]}
              />
            </CardRow>
            <CardRow label="Resolved identity" description="What GET /me says about that header.">
              {me.loading ? <Skeleton className="h-5 w-48" /> : me.error ? <ErrorTag error={me.error} /> : me.data ? <IdentityTags me={me.data} /> : null}
            </CardRow>
            <SignInRow />
          </Card>
        </div>

        <Card
          title="Service accounts"
          subtitle="Non-human callers: automation, agents. The token is shown once, right after creation."
          actions={
            notAdmin ? undefined : (
              <Button variant="primary" size="sm" icon="plus" onClick={() => setCreating(true)}>
                New service account
              </Button>
            )
          }
          flush
        >
          <Table
            columns={columns}
            rows={notAdmin ? [] : (accounts.data ?? [])}
            rowKey={(a) => a.id}
            loading={accounts.loading && !notAdmin}
            bordered={false}
            stickyHeader={false}
            defaultSort={{ key: 'createdAt', dir: 'desc' }}
            emptyIcon={notAdmin ? 'lock' : 'key'}
            emptyTitle={notAdmin ? 'Only admins see service accounts' : accounts.error ? 'Could not load service accounts' : 'No service accounts yet'}
            emptyMessage={
              notAdmin
                ? 'A platform admin creates them and hands out their tokens: ask one for an account for your pipeline or agent.'
                : accounts.error
                  ? errorMessage(accounts.error)
                  : 'Create one for a CI pipeline or an agent; it signs requests with a bearer token.'
            }
            caption="Service accounts"
          />
        </Card>

        <Card title="Environment" subtitle="Where the API lives and whether it answers.">
          <CardRow label="Backend" description={`${API_BASE} → the API, through the dev server's proxy or the image's nginx`}>
            <BackendStatus health={health.data} error={health.error} loading={health.loading} />
            <Button size="sm" icon="refresh" loading={health.fetching && !health.loading} onClick={() => void health.refetch()}>
              Check
            </Button>
          </CardRow>
          <CardRow label="Live streams" description="Job events, history replayed first">
            <span className="font-mono text-caption text-tertiary">/jobs/{'{id}'}/events · /ws/jobs/{'{id}'}</span>
          </CardRow>
        </Card>

        <Card title="About">
          <CardRow label="Shell" description="@iaf/frontend">
            <span className="font-mono text-primary">{__APP_VERSION__}</span>
          </CardRow>
          <CardRow label="Backend" description="/actuator/info">
            {info.loading ? <Skeleton className="h-4 w-16" /> : <span className="font-mono text-primary">{info.data?.build?.version ?? '—'}</span>}
          </CardRow>
          <CardRow label="Design system" description="@iaf/ui">
            <span className="text-primary">Atlas</span>
          </CardRow>
          <CardRow label="API reference" description="OpenAPI 3.1 of every published workflow and resource type, and Swagger UI to try it">
            <ApiLinks />
          </CardRow>
        </Card>
      </div>

      {creating && (
        <CreateServiceAccountModal
          onClose={() => setCreating(false)}
          onCreated={(c) => {
            setCreating(false)
            setCreated(c)
          }}
        />
      )}
      {created && <TokenModal created={created} onClose={() => setCreated(null)} />}
    </>
  )
}

/**
 * How a person signs in where the dev header does not count. The shell cannot tell which profile
 * the API runs (GET /me does not say), so it describes both and links the README.
 */
export function SignInRow() {
  return (
    <CardRow
      stacked
      label="In production"
      description={
        <>
          An auth proxy in front of the app and the API signs you in and adds the bearer token the API verifies under the{' '}
          <code className="font-mono">oidc</code> profile. The API ignores X-User there, so Act as changes nothing.
        </>
      }
    >
      <ExternalLink href={docsUrl(IDENTITY_DOC)} className="inline-flex items-center gap-1 text-caption">
        README: Identity and OIDC
        <Icon name="external" size={12} />
      </ExternalLink>
    </CardRow>
  )
}

function IdentityTags({ me }: { me: Me }) {
  return (
    <div className="flex flex-wrap items-center justify-end gap-1.5">
      <span className="text-sm font-medium text-primary">{me.name}</span>
      {me.email && <span className="text-caption text-tertiary">{me.email}</span>}
      {me.admin && (
        <Tag tone="accent" size="sm">
          admin
        </Tag>
      )}
      {me.roles.filter((r) => !(me.admin && r === 'admin')).map((r) => (
        <Tag key={r} size="sm">
          {r}
        </Tag>
      ))}
      {me.teams.map((t) => (
        <Tag key={t} size="sm" outline>
          {t}
        </Tag>
      ))}
    </div>
  )
}

function ErrorTag({ error }: { error: unknown }) {
  return (
    <Tag tone="critical" icon="alert-triangle" title={errorMessage(error)}>
      {isApiError(error) && error.unreachable ? 'Backend unreachable' : errorMessage(error)}
    </Tag>
  )
}

function BackendStatus({ health, error, loading }: { health?: ActuatorHealth; error: unknown; loading: boolean }) {
  if (loading) return <Skeleton className="h-4 w-24" />
  let kind: DotKind = 'neutral'
  let label = 'Unknown'
  if (error) {
    kind = 'critical'
    label = isApiError(error) && error.unreachable ? 'Unreachable' : `HTTP ${isApiError(error) ? error.status : '?'}`
  } else if (health) {
    kind = health.status === 'UP' ? 'healthy' : health.status === 'DOWN' ? 'critical' : 'warning'
    label = health.status === 'UP' ? 'Up' : health.status === 'DOWN' ? 'Down' : capitalize(health.status.toLowerCase().replace(/_/g, ' '))
  }
  return <StatusDot kind={kind} label={label} pulse={kind === 'healthy'} />
}

function CancelButton({ children = 'Cancel', disabled }: { children?: string; disabled?: boolean }) {
  const close = useOverlayClose()
  return (
    <Button onClick={close} disabled={disabled}>
      {children}
    </Button>
  )
}

/** An account's scopes as tags, in the docs' order; the preset they make, when they make one, comes first. */
function ScopeTags({ scopes, size = 'sm' }: { scopes?: ServiceAccountScope[]; size?: 'sm' | 'md' }) {
  if (!scopes || scopes.length === 0) return <span className="text-tertiary">not reported</span>
  const preset = presetOf(scopes)
  return (
    <div className="flex flex-wrap gap-1" aria-label="Scopes">
      {preset && (
        <Tag size={size} tone="accent" title={`The ${preset} preset`}>
          {preset}
        </Tag>
      )}
      {orderScopes(scopes).map((s) => (
        <Tag key={s} size={size} mono outline tone={s === 'admin' ? 'warning' : 'neutral'}>
          {s}
        </Tag>
      ))}
    </div>
  )
}

function CreateServiceAccountModal({ onClose, onCreated }: { onClose: () => void; onCreated: (created: ServiceAccountCreated) => void }) {
  const toast = useToast()
  const [name, setName] = useState('')
  const [roles, setRoles] = useState<string[]>(['ops'])
  const [teams, setTeams] = useState('')
  // the preset fills the scopes; a scope outside it turns the choice into a custom set
  const [choice, setChoice] = useState<PresetChoice>('invoker')
  const [scopes, setScopes] = useState<ServiceAccountScope[]>(PRESETS.invoker)
  const create = useMutation((body: CreateServiceAccount) => api.createServiceAccount(body), {
    invalidates: ['service-accounts'],
    onSuccess: (data, body) => {
      toast.success('Service account created', body.name)
      onCreated(data)
    },
  })
  const valid = name.trim().length >= 2 && roles.length > 0 && scopes.length > 0

  const pickPreset = (next: PresetChoice) => {
    setChoice(next)
    if (next !== 'custom') setScopes(PRESETS[next])
  }
  const toggleScope = (scope: ServiceAccountScope, on: boolean) => {
    const next = orderScopes(on ? [...scopes, scope] : scopes.filter((s) => s !== scope))
    setScopes(next)
    if (choice !== 'custom' && !fitsPreset(next, choice)) setChoice('custom')
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!valid || create.loading) return
    void create.mutate({
      name: name.trim(),
      roles,
      teams: teams
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean),
      ...scopesRequest(choice, scopes),
    })
  }
  const presetText = choice === 'custom' ? 'Exactly the scopes ticked below.' : (PRESET_INFO.find((p) => p.preset === choice)?.text ?? '')

  return (
    <Modal onClose={onClose} width="max-w-md" label="New service account">
      <OverlayHeader title="New service account" subtitle="The token is shown once, right after creation." icon="key" />
      <form onSubmit={submit} className="flex min-h-0 flex-1 flex-col">
        <OverlayBody className="space-y-4">
          <Field label="Name" required htmlFor="sa-name" hint="Something that says who calls: ci-deployer, cost-bot.">
            <Input id="sa-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="ci-deployer" autoComplete="off" data-autofocus />
          </Field>
          <Field label="Roles" hint="What the account may invoke; workflows list the roles they accept.">
            <div className="flex flex-wrap gap-5 py-1">
              {ROLES.map((r) => (
                <Checkbox key={r} label={r} checked={roles.includes(r)} onChange={(c) => setRoles(c ? [...roles, r] : roles.filter((x) => x !== r))} />
              ))}
            </div>
          </Field>
          <Field label="Teams" htmlFor="sa-teams" hint="Comma-separated: the teams it acts for, which scope what it sees and the jobs it may start. Left empty, it takes yours.">
            <Input id="sa-teams" value={teams} onChange={(e) => setTeams(e.target.value)} placeholder="team-compute, team-platform" autoComplete="off" />
          </Field>
          <Field label="Preset" hint={presetText}>
            <Segmented
              ariaLabel="Preset"
              full
              size="sm"
              value={choice}
              onChange={pickPreset}
              options={[...PRESET_INFO.map((p) => ({ value: p.preset as PresetChoice, label: p.label })), { value: 'custom' as PresetChoice, label: 'Custom' }]}
            />
          </Field>
          <Field label="Scopes" hint="Every call needs the scope of its endpoint; one outside the account's scopes is a 403 that names it.">
            <div className="space-y-3 py-1" data-testid="scope-groups">
              {SCOPE_GROUPS.map((g) => (
                <fieldset key={g.group} className="space-y-2">
                  <legend className="mb-1 text-caption font-medium uppercase tracking-wide text-tertiary">{g.group}</legend>
                  {g.scopes.map((info) => (
                    <Checkbox key={info.scope} id={`sa-scope-${info.scope}`} label={<span className="font-mono">{info.scope}</span>} description={info.allows} checked={scopes.includes(info.scope)} onChange={(on) => toggleScope(info.scope, on)} />
                  ))}
                </fieldset>
              ))}
            </div>
          </Field>
          {create.error ? (
            <p className="text-caption text-critical" role="alert">
              {errorMessage(create.error)}
            </p>
          ) : null}
        </OverlayBody>
        <OverlayFooter>
          <CancelButton disabled={create.loading} />
          <Button type="submit" variant="primary" disabled={!valid} loading={create.loading} title={scopes.length === 0 ? 'Tick at least one scope' : undefined}>
            Create
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}

/**
 * The teams a new account acts for: the ones given, or its creator's when none were, which the
 * backend's note says; a note on an account left with no team warns that the policy refuses
 * every job it starts.
 */
export function ActsFor({ teams, note }: { teams: string[]; note?: string }) {
  const none = teams.length === 0
  return (
    <div className="space-y-1" data-testid="acts-for">
      <p className="text-caption text-secondary">It acts for:</p>
      {none ? (
        <p className="text-caption font-medium text-warning">No team</p>
      ) : (
        <div className="flex flex-wrap gap-1" aria-label="Teams">
          {teams.map((t) => (
            <Tag key={t} size="sm" mono outline>
              {t}
            </Tag>
          ))}
        </div>
      )}
      {note && (
        <p className={cx('flex items-start gap-1.5 text-caption', none ? 'text-warning' : 'text-tertiary')} data-tone={none ? 'warning' : 'neutral'}>
          <Icon name={none ? 'alert-triangle' : 'info'} size={13} className="mt-0.5 shrink-0" />
          <span>{note}</span>
        </p>
      )}
    </div>
  )
}

function TokenModal({ created, onClose }: { created: ServiceAccountCreated; onClose: () => void }) {
  return (
    <Modal onClose={onClose} width="max-w-lg" label="Service account token">
      <OverlayHeader title="Copy the token now" subtitle={`${created.id} · it will not be shown again`} icon="key" />
      <OverlayBody className="space-y-3">
        <CodeBlock code={created.token} wrap />
        <p className="text-caption text-tertiary">
          Send it as <span className="font-mono text-secondary">Authorization: Bearer …</span>. The platform keeps only a hash of it.
        </p>
        {created.scopes && created.scopes.length > 0 && (
          <div className="space-y-1">
            <p className="text-caption text-secondary">It may call:</p>
            <ScopeTags scopes={created.scopes} />
          </div>
        )}
        <ActsFor teams={created.teams ?? []} note={created.note} />
      </OverlayBody>
      <OverlayFooter>
        <CopyButton text={created.token} label="Copy token" size="md" />
        <CancelButton>Done</CancelButton>
      </OverlayFooter>
    </Modal>
  )
}
