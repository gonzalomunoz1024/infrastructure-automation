/**
 * An ansible step's `inventory` and `limit` as the runner reads them (AnsibleRunnerAutomation;
 * docs/config-as-code.md, the ansible step; StepTypeSchemas): the inventory is `dynamic` (the
 * dynamic inventory the platform configures), a path inside the playbook's source, a list of
 * hosts, or `{ hosts, group?, vars? }` whose hosts are a list or one comma-separated string; a
 * step without one runs on ansible's implicit localhost. The limit is a host pattern, or a list
 * of hosts the runner joins with commas.
 */
import type { JsonObject, JsonValue } from '@iaf/workflow-checks'

export type InventoryKind = 'dynamic' | 'path' | 'hosts' | 'group' | 'none'

/** Which of the runner's shapes an inventory value is. */
export function inventoryKind(v: JsonValue | undefined): InventoryKind {
  if (v === undefined || v === null) return 'none'
  if (Array.isArray(v)) return 'hosts'
  if (typeof v === 'object') return 'group'
  return String(v).trim() === 'dynamic' ? 'dynamic' : 'path'
}

/** Host names typed with commas; a `{{ }}` expression stays whole, commas and all. */
export function splitHosts(text: string): string[] {
  const out: string[] = []
  let depth = 0
  let current = ''
  for (let i = 0; i < text.length; i++) {
    if (text.startsWith('{{', i)) {
      depth++
      current += '{{'
      i++
    } else if (depth > 0 && text.startsWith('}}', i)) {
      depth--
      current += '}}'
      i++
    } else if (text[i] === ',' && depth === 0) {
      out.push(current)
      current = ''
    } else current += text[i]
  }
  out.push(current)
  return out.map((s) => s.trim()).filter(Boolean)
}

/** Hosts as a field shows them: a list joined with commas, a comma-separated string as written, anything else not at all. */
export function hostsText(v: JsonValue | undefined): string {
  if (Array.isArray(v)) return v.map((h) => (typeof h === 'string' ? h : JSON.stringify(h))).join(', ')
  return typeof v === 'string' ? v : ''
}

/**
 * What typed hosts write: a list, or the comma-separated string when `keepText` and the value was
 * one (a group's hosts take both); nothing when no host is left, never an empty list.
 */
export function hostsValue(text: string, previous: JsonValue | undefined, keepText = false): JsonValue | undefined {
  const hosts = splitHosts(text)
  if (hosts.length === 0) return undefined
  return keepText && typeof previous === 'string' ? hosts.join(',') : hosts
}

const isObject = (v: JsonValue | undefined): v is JsonObject => typeof v === 'object' && v !== null && !Array.isArray(v)

/**
 * The `{ hosts, group?, vars? }` inventory with one key set, or removed when `value` is undefined;
 * a list of hosts becomes the group's hosts. Undefined when nothing is left: no empty object.
 */
export function withGroupKey(current: JsonValue | undefined, key: 'hosts' | 'group' | 'vars', value: JsonValue | undefined): JsonObject | undefined {
  const next: JsonObject = isObject(current) ? { ...current } : Array.isArray(current) ? { hosts: current } : {}
  if (value === undefined) delete next[key]
  else next[key] = value
  return Object.keys(next).length > 0 ? next : undefined
}

/**
 * What choosing a kind writes at once: `dynamic`, or no inventory at all; the hosts a list or a
 * group already names move to the other shape. A path, or hosts not typed yet, write nothing until
 * their field is filled (`pending`), so the step never holds an empty inventory meanwhile.
 */
export function inventoryFor(kind: InventoryKind, current: JsonValue | undefined): { value: JsonValue | undefined } | 'pending' {
  switch (kind) {
    case 'dynamic':
      return { value: 'dynamic' }
    case 'none':
      return { value: undefined }
    case 'hosts': {
      if (!isObject(current)) return 'pending'
      const hosts = Array.isArray(current.hosts) ? current.hosts : typeof current.hosts === 'string' ? splitHosts(current.hosts) : []
      return hosts.length > 0 ? { value: hosts } : 'pending'
    }
    case 'group':
      return Array.isArray(current) && current.length > 0 ? { value: { hosts: current } } : 'pending'
    case 'path':
      return 'pending'
  }
}
