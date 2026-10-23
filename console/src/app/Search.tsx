import { useCallback, useMemo } from 'react'
import { CommandPalette, type PaletteItem } from '@iaf/ui'
import { api } from '../lib/api'
import { wfrnOf } from '../lib/descriptors'
import { useRecent } from '../lib/recent'
import { useConsole } from './console'
import { navigate, pathFor, resourcePath } from './router'

/*
 * The unified search: the Atlas command palette over services, resource types, pages and the
 * recently visited, plus resources by name from the platform as you type.
 */

export function Search({ onClose }: { onClose: () => void }) {
  const { services, types, wfrnContext } = useConsole()
  const recent = useRecent()

  const items = useMemo<PaletteItem[]>(() => {
    const out: PaletteItem[] = []
    for (const r of recent) out.push({ id: `recent-${r.href}`, group: 'Recent', label: r.label, hint: r.hint, icon: r.kind === 'resource' ? 'resource' : r.kind === 'service' ? 'grid' : r.kind === 'type' ? 'list' : 'document', run: () => navigate(r.href) })
    for (const s of services) out.push({ id: `service-${s.id}`, group: 'Services', label: s.title, hint: `${s.types.length} ${s.types.length === 1 ? 'type' : 'types'}`, icon: s.icon, keywords: s.types.map((t) => t.title).join(' '), run: () => navigate(pathFor('service', { service: s.id })) })
    for (const t of types) out.push({ id: `type-${t.service}-${t.name}`, group: 'Resource types', label: t.title, hint: t.service, icon: t.icon, keywords: `${t.name} ${t.plural} ${t.service}`, run: () => navigate(pathFor('type', { service: t.service, type: t.name })) })
    out.push(
      { id: 'page-home', group: 'Pages', label: 'Console home', icon: 'home', run: () => navigate(pathFor('home')) },
      { id: 'page-requests', group: 'Pages', label: 'Requests', icon: 'job', keywords: 'jobs runs', run: () => navigate(pathFor('requests')) },
      { id: 'page-iam', group: 'Pages', label: 'Identity and access', icon: 'shield', keywords: 'iam roles permissions', run: () => navigate(pathFor('iam')) },
      { id: 'page-iam-users', group: 'Pages', label: 'Users', icon: 'users', keywords: 'iam people members', run: () => navigate(pathFor('iam-users')) },
      { id: 'page-iam-groups', group: 'Pages', label: 'Groups', icon: 'users', keywords: 'iam teams roles', run: () => navigate(pathFor('iam-groups')) },
      { id: 'page-iam-roles', group: 'Pages', label: 'Roles', icon: 'shield', keywords: 'iam managed policies statements', run: () => navigate(pathFor('iam-roles')) },
      { id: 'page-iam-keys', group: 'Pages', label: 'Access keys', icon: 'key', keywords: 'iam cli api credentials', run: () => navigate(pathFor('iam-keys')) },
      { id: 'page-iam-requests', group: 'Pages', label: 'Access requests', icon: 'approval', keywords: 'iam request access approvals', run: () => navigate(pathFor('iam-requests')) },
      { id: 'page-settings', group: 'Pages', label: 'Settings', icon: 'settings', keywords: 'appearance theme identity', run: () => navigate(pathFor('settings')) },
    )
    return out
  }, [recent, services, types])

  const search = useCallback(
    async (q: string): Promise<PaletteItem[]> => {
      const needle = q.trim()
      if (needle.length < 2) return []
      const page = await api.resources({ q: needle, limit: 8 }).catch(() => ({ items: [], next: null }))
      return page.items.flatMap((r) => {
        const t = types.find((x) => x.name === r.type)
        if (!t) return []
        const { wfrn } = wfrnOf(r, t, wfrnContext)
        return [{ id: `resource-${r.id}`, group: 'Resources', label: r.name ?? r.id, hint: `${t.title} · ${r.status}`, icon: t.icon, keywords: r.id, run: () => navigate(resourcePath(t.service, t.name, wfrn)) }]
      })
    },
    [types, wfrnContext],
  )

  return <CommandPalette onClose={onClose} items={items} groups={['Recent', 'Services', 'Resource types', 'Resources', 'Pages']} search={search} placeholder="Search services, resources and pages…" />
}
