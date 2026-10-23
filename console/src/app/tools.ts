import type { IconName } from '@iaf/ui'
import { pathFor, type Route, type RouteName } from './router'

/*
 * The console's own tools, beside the services the platform's types make: pages that work across
 * every type of the account and region, such as resource groups and the Tag Editor. The services
 * menu, the command palette and each section's sidebar read this one list, so a tool added here
 * shows up in all three.
 */

export type ToolSectionId = 'tags' | 'operations' | 'automation' | 'compliance' | 'quotas' | 'organization' | 'developer' | 'history' | 'insights'

export interface ToolSection {
  id: ToolSectionId
  title: string
  icon: IconName
  subtitle: string
}

export interface ConsoleTool {
  /** the page it opens */
  route: RouteName
  section: ToolSectionId
  label: string
  icon: IconName
  /** one line for the services menu and the palette */
  hint: string
  /** extra words the palette matches */
  keywords: string
  /** the routes that mark its sidebar entry current, its own first */
  routes: RouteName[]
}

export const TOOL_SECTIONS: ToolSection[] = [
  { id: 'tags', title: 'Resource groups and tags', icon: 'tag', subtitle: 'Across every resource type' },
  { id: 'operations', title: 'Operations', icon: 'pulse', subtitle: 'Drift, discovery, alarms and rightsizing' },
  { id: 'organization', title: 'Organization', icon: 'grid', subtitle: 'Across every account' },
  { id: 'developer', title: 'Developer tools', icon: 'terminal', subtitle: 'The shell, the API, the CLI and SDKs' },
  { id: 'history', title: 'Activity and history', icon: 'history', subtitle: 'The trail, the past and the map' },
  { id: 'quotas', title: 'Service Quotas', icon: 'chart', subtitle: 'The account’s limits and requests to raise them' },
  { id: 'automation', title: 'Automation', icon: 'workflow', subtitle: 'Blueprints, stacks and event subscriptions' },
  { id: 'compliance', title: 'Compliance', icon: 'shield', subtitle: 'Scorecards and the rules they check' },
  { id: 'insights', title: 'Dashboards and health', icon: 'grid', subtitle: 'Team dashboards and how the services are doing' },
]

export const TOOLS: ConsoleTool[] = [
  { route: 'groups', section: 'tags', label: 'Resource groups', icon: 'layers', hint: 'Resources that share tags, kept as one group', keywords: 'resource groups tag query collections', routes: ['groups', 'group'] },
  { route: 'tag-editor', section: 'tags', label: 'Tag Editor', icon: 'tag', hint: 'Find resources of every type and tag them together', keywords: 'tags bulk retag labels', routes: ['tag-editor'] },
  { route: 'tag-policy', section: 'tags', label: 'Required tags', icon: 'shield', hint: 'The tags every new resource must carry', keywords: 'tag policy owner cost centre data class environment guardrails', routes: ['tag-policy'] },
  { route: 'drift', section: 'operations', label: 'Drift', icon: 'pulse', hint: 'Where what the providers report differs from what was asked for', keywords: 'drift findings revert accept ignore config compliance', routes: ['drift'] },
  { route: 'unmanaged', section: 'operations', label: 'Unmanaged resources', icon: 'eye', hint: 'What the providers have that no account manages', keywords: 'discovery shadow infrastructure import assign owner', routes: ['unmanaged'] },
  { route: 'alarms', section: 'operations', label: 'Alarms', icon: 'bell', hint: 'Metric alarms on the account’s resources', keywords: 'monitoring metrics cpu memory disk thresholds', routes: ['alarms'] },
  { route: 'org-accounts', section: 'organization', label: 'Accounts', icon: 'grid', hint: 'Every account of the organization: emergency access and closing', keywords: 'organization accounts close break glass emergency support access', routes: ['org-accounts'] },
  { route: 'shell', section: 'developer', label: 'Console shell', icon: 'terminal', hint: 'Run CLI commands in the browser, signed in as you', keywords: 'cli cloudshell terminal command line ls get run jobs', routes: ['shell'] },
  { route: 'portal', section: 'developer', label: 'Developer portal', icon: 'document', hint: 'The console API, the CLI and SDKs, sample calls and portal tiles', keywords: 'api openapi sdk cli idp backstage tiles integration', routes: ['portal'] },
  { route: 'rightsizing', section: 'operations', label: 'Rightsizing', icon: 'chart', hint: 'Idle and oversized resources, and what resizing saves', keywords: 'idle oversized savings cost optimisation resize', routes: ['rightsizing'] },
  { route: 'activity', section: 'history', label: 'Activity trail', icon: 'audit', hint: 'Every change to the account’s resources, sealed and signed', keywords: 'audit trail cloudtrail events who changed evidence verify hash chain', routes: ['activity'] },
  { route: 'as-of', section: 'history', label: 'Inventory as of a date', icon: 'calendar', hint: 'The account’s resources as they were on a day', keywords: 'time travel history past snapshot point in time', routes: ['as-of'] },
  { route: 'map', section: 'history', label: 'Resource map', icon: 'git-branch', hint: 'What is built from what, across types and accounts', keywords: 'graph dependencies lineage topology map', routes: ['map'] },
  { route: 'service-quotas', section: 'quotas', label: 'Service Quotas', icon: 'chart', hint: 'What the account may hold, and requests to raise a limit', keywords: 'quotas limits increase raise request approve rules', routes: ['service-quotas'] },
  { route: 'blueprints', section: 'automation', label: 'Blueprints', icon: 'layers', hint: 'Stacks of resources launched and torn down as one', keywords: 'blueprints templates stacks launch catalog', routes: ['blueprints', 'blueprint', 'blueprint-launch'] },
  { route: 'stacks', section: 'automation', label: 'Stacks', icon: 'resource', hint: 'What was launched from a blueprint, and where it is', keywords: 'stacks teardown resume', routes: ['stacks', 'stack'] },
  { route: 'subscriptions', section: 'automation', label: 'Event subscriptions', icon: 'bell', hint: 'Send resource events to a webhook, Kafka or Slack', keywords: 'events webhook kafka slack notify subscribe', routes: ['subscriptions'] },
  { route: 'compliance', section: 'compliance', label: 'Scorecard', icon: 'shield', hint: 'How the account scores against each rule pack', keywords: 'compliance scorecard rules packs score remediate', routes: ['compliance', 'compliance-rule'] },
  { route: 'compliance-org', section: 'compliance', label: 'Organization', icon: 'chart', hint: 'Every account’s score by pack', keywords: 'heat map organization accounts compliance', routes: ['compliance-org'] },
  { route: 'dashboards', section: 'insights', label: 'Dashboards', icon: 'grid', hint: 'Boards of charts and counts, kept for yourself or shared with your team', keywords: 'dashboards cloudwatch charts widgets team board', routes: ['dashboards', 'dashboard'] },
  { route: 'health', section: 'insights', label: 'Service health', icon: 'pulse', hint: 'Whether the platform and the console’s services meet their objectives', keywords: 'status slo availability latency incidents uptime', routes: ['health'] },
]

export function toolSectionOf(route: Route): ToolSection | undefined {
  const tool = TOOLS.find((t) => t.routes.includes(route.name))
  return tool ? TOOL_SECTIONS.find((s) => s.id === tool.section) : undefined
}

export function toolsIn(section: ToolSectionId): ConsoleTool[] {
  return TOOLS.filter((t) => t.section === section)
}

export function toolPath(tool: ConsoleTool): string {
  return pathFor(tool.route)
}
