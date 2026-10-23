import type { IconName } from '@iaf/ui'
import { pathFor, type Route, type RouteName } from './router'

/*
 * The console's own tools, beside the services the platform's types make: pages that work across
 * every type of the account and region, such as resource groups and the Tag Editor. The services
 * menu, the command palette and each section's sidebar read this one list, so a tool added here
 * shows up in all three.
 */

export type ToolSectionId = 'tags'

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

export const TOOL_SECTIONS: ToolSection[] = [{ id: 'tags', title: 'Resource groups and tags', icon: 'tag', subtitle: 'Across every resource type' }]

export const TOOLS: ConsoleTool[] = [
  { route: 'groups', section: 'tags', label: 'Resource groups', icon: 'layers', hint: 'Resources that share tags, kept as one group', keywords: 'resource groups tag query collections', routes: ['groups', 'group'] },
  { route: 'tag-editor', section: 'tags', label: 'Tag Editor', icon: 'tag', hint: 'Find resources of every type and tag them together', keywords: 'tags bulk retag labels', routes: ['tag-editor'] },
  { route: 'tag-policy', section: 'tags', label: 'Required tags', icon: 'shield', hint: 'The tags every new resource must carry', keywords: 'tag policy owner cost centre data class environment guardrails', routes: ['tag-policy'] },
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
