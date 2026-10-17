import type { PageProps } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function ReleasesPage(_: PageProps) {
  return (
    <PlaceholderPage
      title="Releases"
      subtitle="Canaries in flight and the versions they promote."
      icon="release"
      will="Releases with workflow, from → to, strategy, stage and percent, gate results and time in stage; promote, pause, resume or roll back; the history of each one."
    />
  )
}
