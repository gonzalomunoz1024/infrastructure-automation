import { EmptyState } from '@iaf/ui'
import type { TabProps } from './tabs'

/* The Monitoring tab (WP 9.21): the resource's CPU, memory and disk from its provider's metrics read, and its alarms. */

export function MonitoringTab(_props: TabProps) {
  return <EmptyState icon="chart" title="Monitoring" message="This tab is being built." />
}
