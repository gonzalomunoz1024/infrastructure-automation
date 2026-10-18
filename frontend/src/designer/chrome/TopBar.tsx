/**
 * The designer's header, n8n's in Atlas: back to Workflows, the editable title, the status and
 * the owner on the left; Editor · Executions · Settings in the middle; the live issue count,
 * YAML, the assistant, Save and Publish on the right.
 */
import { useEffect, useRef, useState } from 'react'
import { Badge, Button, cx, Icon, IconButton, Kbd, modKeyLabel, StateTag, Tabs, Tag, Tooltip } from '@iaf/ui'
import type { WorkflowMetadata } from '@iaf/workflow-checks'
import { DeprecatedTag } from '../../components/Deprecation'
import { useDesigner, useDesignerStore, type DesignerTab } from '../model/store'

function EditableTitle({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const [editing, setEditing] = useState(false)
  // the draft exists only while editing; it starts from the title when editing begins
  const [text, setText] = useState(value)
  const ref = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (editing) ref.current?.select()
  }, [editing])
  const commit = () => {
    setEditing(false)
    const next = text.trim()
    if (next && next !== value) onChange(next)
  }
  if (editing) {
    return (
      <input
        ref={ref}
        aria-label="Workflow title"
        value={text}
        onChange={(e) => setText(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') commit()
          else if (e.key === 'Escape') {
            setText(value)
            setEditing(false)
          }
        }}
        className="no-focus-ring min-w-0 max-w-[320px] rounded-sm border border-accent bg-surface px-1.5 py-0.5 text-subheadline font-semibold text-primary outline-none"
      />
    )
  }
  return (
    <button
      type="button"
      onClick={() => {
        setText(value)
        setEditing(true)
      }}
      title="Rename workflow"
      className="min-w-0 max-w-[320px] truncate rounded-sm px-1.5 py-0.5 text-left text-subheadline font-semibold text-primary transition-colors hover:bg-surface-secondary"
    >
      {value || 'Untitled workflow'}
    </button>
  )
}

export interface TopBarProps {
  onBack: () => void
  onIssues: () => void
  onYaml: () => void
  onAssistant: () => void
  onSave: () => void
  onPublish: () => void
  /** run every check on the backend, publish checks included */
  onValidate: () => void
  validating?: boolean
  assistantOpen: boolean
}

export function TopBar({ onBack, onIssues, onYaml, onAssistant, onSave, onPublish, onValidate, validating, assistantOpen }: TopBarProps) {
  const store = useDesignerStore()
  const s = useDesigner()
  const meta: WorkflowMetadata = s.doc.metadata ?? {}
  const title = typeof meta.title === 'string' ? meta.title : (s.name ?? '')
  const owner = typeof meta.owner === 'string' ? meta.owner : (s.detail?.owner ?? '')
  const status = s.isNew ? 'draft' : (s.detail?.status ?? 'draft')
  const version = s.detail?.publishedVersion ?? s.detail?.latestVersion ?? (typeof meta.version === 'string' ? meta.version : null)
  const deprecated = s.doc.api?.deprecated
  const errors = s.problems.filter((p) => p.level === 'error').length
  const warnings = s.problems.length - errors
  const m = modKeyLabel()
  const tabs: { id: DesignerTab; label: string; disabled?: boolean }[] = [
    { id: 'editor', label: 'Editor' },
    { id: 'executions', label: 'Executions', disabled: s.isNew },
    { id: 'settings', label: 'Settings' },
  ]
  return (
    <header className="@container relative z-20 flex h-14 shrink-0 items-center gap-3 border-b border-stroke-light bg-surface px-3">
      <div className="flex min-w-0 flex-1 items-center gap-2">
        <IconButton label="Back to workflows" size="sm" onClick={onBack}>
          <Icon name="arrow-left" size={16} />
        </IconButton>
        <EditableTitle
          value={title}
          onChange={(t) =>
            store.update((d) => {
              d.metadata = { ...(d.metadata ?? {}), title: t }
            }, 'title')
          }
        />
        {/* sized by the header, not the window (the shell sidebar may be collapsed or not): the tags give way first, then the title truncates */}
        <span className="hidden shrink-0 @4xl:inline-flex">
          <StateTag status={status} size="sm">
            {status === 'published' && version ? `published ${version}` : status}
          </StateTag>
        </span>
        {deprecated && (
          <span className="hidden shrink-0 @4xl:inline-flex" data-testid="deprecated-tag">
            <DeprecatedTag deprecated={deprecated} />
          </span>
        )}
        {owner && (
          <span className="hidden shrink-0 @6xl:inline-flex">
            <Tag size="sm" icon="users" outline>
              {owner}
            </Tag>
          </span>
        )}
        {s.dirty && !s.saving && <span className="hidden truncate text-caption text-tertiary @7xl:inline">Unsaved changes</span>}
      </div>

      <Tabs<DesignerTab> tabs={tabs} value={s.tab} onChange={(t) => store.setTab(t)} ariaLabel="Designer views" className="shrink-0 flex-nowrap" />

      {/* never narrower than its buttons: when space runs out the title truncates, not this */}
      <div className="flex min-w-max flex-1 items-center justify-end gap-1.5">
        <Tooltip label={s.problems.length === 0 ? 'No problems found' : `${errors} errors, ${warnings} warnings`} side="bottom">
          <button
            type="button"
            onClick={onIssues}
            aria-label={`Issues: ${s.problems.length}`}
            data-testid="issues-button"
            className={cx(
              'flex h-8 items-center gap-1.5 rounded-full border px-2.5 text-caption font-medium transition-colors',
              errors > 0 ? 'border-critical/40 text-critical hover:bg-critical-tint' : warnings > 0 ? 'border-warning/40 text-warning hover:bg-warning-tint' : 'border-stroke-light text-secondary hover:bg-surface-secondary',
            )}
          >
            <Icon name={s.problems.length === 0 ? 'check' : 'alert-triangle'} size={13} strokeWidth={2} />
            <span className="hidden @3xl:inline">Issues</span>
            <Badge count={s.problems.length} tone={errors > 0 ? 'critical' : 'neutral'} showZero />
          </button>
        </Tooltip>
        <Tooltip label="Run every check on the backend, including what publishing would refuse" side="bottom">
          <Button variant="ghost" size="sm" icon="shield" onClick={onValidate} loading={validating} aria-label="Validate" data-testid="validate-button">
            <span className="hidden @6xl:inline">Validate</span>
          </Button>
        </Tooltip>
        <Tooltip label="The workflow as YAML" side="bottom">
          <Button variant="ghost" size="sm" icon="document" onClick={onYaml} aria-label="YAML">
            <span className="hidden @6xl:inline">YAML</span>
          </Button>
        </Tooltip>
        <Tooltip label="Build or change the workflow by describing it" side="bottom">
          <Button variant="ghost" size="sm" icon="sparkles" onClick={onAssistant} aria-label="Assistant" aria-pressed={assistantOpen} className={cx(assistantOpen && 'bg-accent-tint text-accent')}>
            <span className="hidden @6xl:inline">Assistant</span>
          </Button>
        </Tooltip>
        <span className="mx-1 h-5 w-px bg-stroke-light" aria-hidden="true" />
        <Tooltip label={<span className="flex items-center gap-1.5">Save the draft <Kbd>{m}S</Kbd></span>} side="bottom">
          <Button variant="secondary" size="sm" loading={s.saving} onClick={onSave} disabled={!s.dirty && !s.isNew} data-testid="save-button">
            {s.saving ? 'Saving' : s.dirty || s.isNew ? 'Save' : 'Saved'}
          </Button>
        </Tooltip>
        <Tooltip label={s.isNew ? 'Save the workflow first' : 'Open a change request to publish'} side="bottom">
          <Button variant="primary" size="sm" icon="release" onClick={onPublish} disabled={s.isNew} aria-label="Publish">
            <span className="hidden @3xl:inline">Publish</span>
          </Button>
        </Tooltip>
      </div>
    </header>
  )
}
