import type { PageProps } from '../app/router'
import { PlaceholderPage } from './placeholder'

export default function JobsPage(_: PageProps) {
  return (
    <PlaceholderPage
      title="Jobs"
      subtitle="Every run: queued, running, waiting on someone, done."
      icon="job"
      will="A live table of jobs with status, workflow, version, requester, channel, start time and duration; filters by workflow and status; batch jobs with their progress; and the way into each job's timeline."
    />
  )
}
