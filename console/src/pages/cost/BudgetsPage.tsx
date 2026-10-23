import { EmptyState, PageHeader } from '@iaf/ui'
import type { PageProps } from '../../app/router'

/* Phase 9: the pages arrive with their work package. */

export default function BudgetsPage(_props: PageProps) {
  return (
    <>
      <PageHeader title="Budgets" subtitle="A monthly limit per account or tag, forecast to the month's end, with alerts and an optional block on creates." />
      <EmptyState icon="sparkles" title="Arrives with phase 9" message="This page is being built." />
    </>
  )
}

export function BudgetPage(_props: PageProps) {
  return (
    <>
      <PageHeader title="Budget" />
      <EmptyState icon="sparkles" title="Arrives with phase 9" message="This page is being built." />
    </>
  )
}
