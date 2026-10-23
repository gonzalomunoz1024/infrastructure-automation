import { useCallback, useMemo } from 'react'
import { CommandPalette, useTheme, useToast, type PaletteItem, type ThemeMode } from '@iaf/ui'
import { isApiError } from '../lib/api'
import { bff, signOut, switchAccount, switchRegion } from '../lib/bff'
import { findType, partsOfWfrn, singularOf } from '../lib/descriptors'
import { useQuery } from '../lib/queries'
import { useRecent } from '../lib/recent'
import { pathOfWfrn, useConsole } from './console'
import { useRefusal } from './Refusal'
import { createPath, navigate, pathFor, resourcePath, type Route } from './router'
import { TOOLS, toolPath } from './tools'

/*
 * The unified search, keyboard first (Cmd+K or Ctrl+K, or /): the Atlas command palette over the
 * recently visited, every service and resource type, the actions (create a resource of any type;
 * on a resource's page, the type's actions on it), the pages and the settings (appearance,
 * account, region, sign out), plus resources from the BFF's search index as you type and any
 * wfrn pasted whole. An action runs the same decision as its button: a refused one opens the
 * refusal instead.
 */

const GROUPS = ['Recent', 'Resources', 'Services', 'Resource types', 'Actions', 'Pages', 'Settings']

const APPEARANCE: { mode: ThemeMode; label: string }[] = [
  { mode: 'light', label: 'Use the light appearance' },
  { mode: 'dark', label: 'Use the dark appearance' },
  { mode: 'system', label: 'Match the system appearance' },
]

export function Search({ route, onClose }: { route: Route; onClose: () => void }) {
  const { services, types, decide, session, account, region, regions } = useConsole()
  const recent = useRecent()
  const refusal = useRefusal()
  const toast = useToast()
  const { mode, setMode } = useTheme()
  const accounts = useQuery(['accounts', session.user.userId], () => bff.accounts())

  const items = useMemo<PaletteItem[]>(() => {
    const out: PaletteItem[] = []
    for (const r of recent) out.push({ id: `recent-${r.href}`, group: 'Recent', label: r.label, hint: r.hint, icon: r.kind === 'resource' ? 'resource' : r.kind === 'service' ? 'grid' : r.kind === 'type' ? 'list' : 'document', run: () => navigate(r.href) })
    for (const s of services) out.push({ id: `service-${s.id}`, group: 'Services', label: s.title, hint: `${s.types.length} ${s.types.length === 1 ? 'type' : 'types'}`, icon: s.icon, keywords: s.types.map((t) => t.title).join(' '), run: () => navigate(pathFor('service', { service: s.id })) })
    for (const t of types) out.push({ id: `type-${t.service}-${t.name}`, group: 'Resource types', label: t.title, hint: t.service, icon: t.icon, keywords: `${t.name} ${t.plural} ${t.service}`, run: () => navigate(pathFor('type', { service: t.service, type: t.name })) })

    // actions: what the buttons do, with the same decisions
    for (const t of types) {
      const title = t.create?.title ?? `Create ${singularOf(t.title).toLowerCase()}`
      const decision = decide(t, 'create')
      out.push({
        id: `create-${t.service}-${t.name}`,
        group: 'Actions',
        label: title,
        hint: decision.allowed ? t.title : 'not allowed',
        icon: decision.allowed ? 'plus' : 'lock',
        keywords: `create new add ${t.title} ${t.name}`,
        run: () => (decision.denied ? refusal.show({ denied: decision.denied, what: title }) : navigate(createPath(t.service, t.name))),
      })
    }
    if (route.name === 'resource' && route.params.wfrn) {
      const t = findType(types, route.params.service ?? '', route.params.type ?? '')
      const wfrn = route.params.wfrn
      for (const a of t?.actions ?? []) {
        if (!t || a.verb === 'create') continue
        const decision = decide(t, a.verb)
        out.push({
          id: `action-${a.verb}`,
          group: 'Actions',
          label: `${a.title} (this ${singularOf(t.title).toLowerCase()})`,
          hint: decision.allowed ? (a.destructive ? 'asks to confirm' : 'this resource') : 'not allowed',
          icon: decision.allowed ? (a.destructive ? 'trash' : 'bolt') : 'lock',
          keywords: `${a.verb} ${a.workflow}`,
          run: () => (decision.denied ? refusal.show({ denied: decision.denied, what: a.title }) : navigate(pathFor('resource', { service: t.service, type: t.name, wfrn }, { action: a.verb }))),
        })
      }
    }

    out.push(
      { id: 'page-home', group: 'Pages', label: 'Console home', icon: 'home', run: () => navigate(pathFor('home')) },
      { id: 'page-requests', group: 'Pages', label: 'Requests', icon: 'job', keywords: 'jobs runs', run: () => navigate(pathFor('requests')) },
      { id: 'page-iam', group: 'Pages', label: 'Identity and access', icon: 'shield', keywords: 'iam roles permissions', run: () => navigate(pathFor('iam')) },
      { id: 'page-iam-users', group: 'Pages', label: 'Users', icon: 'users', keywords: 'iam people members', run: () => navigate(pathFor('iam-users')) },
      { id: 'page-iam-groups', group: 'Pages', label: 'Groups', icon: 'users', keywords: 'iam teams roles', run: () => navigate(pathFor('iam-groups')) },
      { id: 'page-iam-roles', group: 'Pages', label: 'Roles', icon: 'shield', keywords: 'iam managed policies statements', run: () => navigate(pathFor('iam-roles')) },
      { id: 'page-iam-policies', group: 'Pages', label: 'Policies', icon: 'document', keywords: 'iam custom policy statements json', run: () => navigate(pathFor('iam-policies')) },
      { id: 'page-iam-simulator', group: 'Pages', label: 'Policy simulator', icon: 'play', keywords: 'iam simulate allowed denied why', run: () => navigate(pathFor('iam-simulator')) },
      { id: 'page-iam-guardrails', group: 'Pages', label: 'Guardrails', icon: 'lock', keywords: 'iam organization boundary deny', run: () => navigate(pathFor('iam-guardrails')) },
      { id: 'page-iam-keys', group: 'Pages', label: 'Access keys', icon: 'key', keywords: 'iam cli api credentials', run: () => navigate(pathFor('iam-keys')) },
      { id: 'page-iam-requests', group: 'Pages', label: 'Access requests', icon: 'approval', keywords: 'iam request access approvals', run: () => navigate(pathFor('iam-requests')) },
      { id: 'page-settings', group: 'Pages', label: 'Settings', icon: 'settings', keywords: 'preferences appearance identity', run: () => navigate(pathFor('settings')) },
      ...TOOLS.map((t) => ({ id: `page-${t.route}`, group: 'Pages', label: t.label, icon: t.icon, keywords: t.keywords, run: () => navigate(toolPath(t)) })),
    )

    // settings: appearance, account, region, sign out
    for (const a of APPEARANCE) {
      if (a.mode === mode) continue
      out.push({ id: `theme-${a.mode}`, group: 'Settings', label: a.label, icon: a.mode === 'dark' ? 'moon' : a.mode === 'light' ? 'sun' : 'laptop', keywords: 'theme appearance dark light mode', run: () => setMode(a.mode) })
    }
    for (const a of accounts.data?.items ?? []) {
      if (a.accountId === account.id) continue
      out.push({
        id: `account-${a.accountId}`,
        group: 'Settings',
        label: `Switch to the account ${a.name}`,
        hint: a.accountId,
        icon: 'users',
        keywords: `account switch ${a.accountId}`,
        run: () => void switchAccount(a.accountId).catch((e: unknown) => toast.error('Could not switch account', isApiError(e) ? e.error : String(e))),
      })
    }
    for (const r of regions) {
      if (r.name === region.name) continue
      out.push({ id: `region-${r.name}`, group: 'Settings', label: `Switch to the region ${r.title}`, hint: r.name, icon: 'grid', keywords: `region switch ${r.name}`, run: () => void switchRegion(r.name).catch((e: unknown) => toast.error('Could not switch region', isApiError(e) ? e.error : String(e))) })
    }
    out.push({ id: 'sign-out', group: 'Settings', label: 'Sign out', icon: 'arrow-left', keywords: 'log out leave', run: () => void signOut().then(() => navigate(pathFor('signin'))) })
    return out
  }, [recent, services, types, decide, refusal, route, mode, setMode, accounts.data, account.id, regions, region.name, toast])

  const search = useCallback(
    async (q: string): Promise<PaletteItem[]> => {
      const needle = q.trim()
      const out: PaletteItem[] = []
      // a wfrn pasted whole opens its resource
      if (needle.startsWith('wfrn:')) {
        const to = pathOfWfrn(needle, types)
        if (to) out.push({ id: `wfrn-${needle}`, group: 'Resources', label: needle, hint: 'open by wfrn', icon: 'link', run: () => navigate(to) })
      }
      if (needle.length < 2) return out
      const page = await bff.search({ q: needle, limit: 8 }).catch(() => null)
      for (const hit of page?.items ?? []) {
        const parts = partsOfWfrn(hit.wfrn)
        const t = findType(types, hit.service ?? parts?.service ?? '', hit.type)
        if (!t || out.some((o) => o.id === `wfrn-${hit.wfrn}`)) continue
        out.push({ id: `resource-${hit.wfrn}`, group: 'Resources', label: hit.name ?? parts?.id ?? hit.wfrn, hint: [singularOf(t.title), hit.state].filter(Boolean).join(' · '), icon: t.icon, keywords: hit.wfrn, run: () => navigate(resourcePath(t.service, t.name, hit.wfrn)) })
      }
      return out
    },
    [types],
  )

  return <CommandPalette onClose={onClose} items={items} groups={GROUPS} search={search} placeholder="Search resources, services, actions and settings…" emptyMessage="Nothing matches. Try a name, a wfrn, a type or an action." />
}
