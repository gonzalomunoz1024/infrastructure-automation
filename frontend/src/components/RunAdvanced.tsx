/**
 * The Advanced disclosure of a run form: "Run exactly version" pins the run to one published or
 * retired version (X-Workflow-Version), bypassing the release router's canary split; input,
 * access and policy are judged against that version. Drafts cannot run and are not offered.
 */
import { useState, type ReactNode } from 'react'
import { Field, Icon, Select, Tag, type SelectOption } from '@iaf/ui'
import { api, errorMessage } from '../lib/api'
import { useQuery } from '../lib/queries'
import type { WorkflowVersion } from '../lib/types'

/** The empty choice: the release decides which version a run gets. */
export const ROUTED = ''

/** The versions a run may be pinned to, as the API orders them, behind the routed choice. */
export function versionOptions(versions: readonly WorkflowVersion[] | undefined, current: string): SelectOption[] {
  const runnable = (versions ?? []).filter((v) => v.status === 'published' || v.status === 'retired')
  return [{ value: ROUTED, label: 'Routed by the release', hint: `${current}, or its canary for a share of runs` }, ...runnable.map((v) => ({ value: v.version, label: v.version, hint: v.status }))]
}

export interface RunAdvancedProps {
  workflow: string
  /** the catalog's version: what the router gives when nothing is pinned */
  current: string
  version: string
  onVersion: (version: string) => void
  children?: ReactNode
}

export function RunAdvanced({ workflow, current, version, onVersion, children }: RunAdvancedProps) {
  const [open, setOpen] = useState(false)
  // the versions load when the disclosure opens; most runs never need them
  const versions = useQuery(['workflow-versions', workflow], () => api.workflowVersions(workflow), { enabled: open })
  const options = versionOptions(versions.data, current)
  return (
    <details className="group rounded-lg border border-stroke-light bg-surface" data-testid="run-advanced" onToggle={(e) => setOpen(e.currentTarget.open)}>
      <summary className="flex cursor-pointer items-center gap-2 px-5 py-3 text-sm text-secondary transition-colors hover:text-primary">
        <Icon name="settings" size={15} className="text-tertiary" />
        Advanced
        {version && (
          <Tag size="sm" tone="accent" mono>
            exactly {version}
          </Tag>
        )}
        <Icon name="chevron-down" size={13} className="ml-auto text-tertiary transition-transform group-open:rotate-180" />
      </summary>
      <div className="space-y-4 px-5 pb-5">
        <Field
          label="Run exactly version"
          hint={
            version
              ? `The call goes with X-Workflow-Version: ${version}, bypassing the canary split; input, access and policy are judged against that version.`
              : 'Leave it routed and the release decides: the published version, or its canary for a share of runs. Pinning bypasses the canary split.'
          }
        >
          <Select ariaLabel="Run exactly version" value={version} onChange={onVersion} options={options} mono placeholder={versions.loading ? 'Loading versions' : 'Routed by the release'} disabled={versions.loading && !versions.data} className="sm:max-w-sm" />
        </Field>
        {versions.error ? (
          <p className="text-caption text-critical" role="alert">
            Could not list the versions: {errorMessage(versions.error)}
          </p>
        ) : null}
        {children}
      </div>
    </details>
  )
}
