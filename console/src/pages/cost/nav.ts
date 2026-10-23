import type { NavSection } from '../../app/ServiceSidebar'
import { pathFor, type Route } from '../../app/router'

/** The left navigation of cost and budgets: the explorer, budgets and chargeback. */
export function costNav(route: Route, accountName: string): NavSection {
  const on = (...names: string[]) => names.includes(route.name)
  return {
    title: 'Cost and budgets',
    icon: 'chart',
    subtitle: accountName,
    items: [
      { id: 'cost', label: 'Cost and usage', icon: 'chart', href: pathFor('cost'), active: on('cost') },
      { id: 'budgets', label: 'Budgets', icon: 'bell', href: pathFor('budgets'), active: on('budgets', 'budget') },
      { id: 'chargeback', label: 'Chargeback', icon: 'document', href: pathFor('chargeback'), active: on('chargeback') },
    ],
  }
}
