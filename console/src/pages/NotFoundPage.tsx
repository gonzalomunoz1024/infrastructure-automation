import { EmptyState } from '@iaf/ui'
import { Link, pathFor, type PageProps } from '../app/router'

export default function NotFoundPage({ route }: PageProps) {
  return (
    <EmptyState
      icon="help"
      title="There is no page here"
      message={
        <>
          Nothing answers to <span className="font-mono">{route.path}</span>. The address may be old, or the service it named may have gone.
        </>
      }
      action={
        <Link to={pathFor('home')} className="text-sm font-medium text-accent transition-opacity hover:opacity-70">
          Go to the console home
        </Link>
      }
    />
  )
}
