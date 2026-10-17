import { Button, Card, EmptyState, PageHeader } from '@iaf/ui'
import type { PageProps } from '../app/router'
import { navigate, pathFor } from '../app/router'

export default function NotFoundPage({ route }: PageProps) {
  return (
    <>
      <PageHeader title="Page not found" subtitle="Nothing lives at this address." />
      <Card flush>
        <EmptyState
          icon="ring"
          title={route.path}
          message="Check the link, or start again from the workflows."
          action={
            <Button variant="primary" onClick={() => navigate(pathFor('workflows'))}>
              Go to workflows
            </Button>
          }
        />
      </Card>
    </>
  )
}
