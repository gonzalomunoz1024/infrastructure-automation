import type { ReactNode } from 'react'
import { Card, EmptyState, PageHeader, type IconName } from '@iaf/ui'

export interface PlaceholderPageProps {
  title: ReactNode
  subtitle?: ReactNode
  icon: IconName
  /** what the finished page will show */
  will: string
  eyebrow?: ReactNode
  back?: { href: string; label: string }
  actions?: ReactNode
}

/** A page that says what it will show. The pages agent replaces each one. */
export function PlaceholderPage({ title, subtitle, icon, will, eyebrow, back, actions }: PlaceholderPageProps) {
  return (
    <>
      <PageHeader title={title} subtitle={subtitle} eyebrow={eyebrow} back={back} actions={actions} />
      <Card flush>
        <EmptyState icon={icon} title="This page is on its way" message={will} />
      </Card>
    </>
  )
}
