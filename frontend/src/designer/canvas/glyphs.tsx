/**
 * The node glyphs. n8n draws a coloured brand icon in every node; the designer draws the kit's
 * line glyphs plus a few of its own (braces, globe, Ansible, Terraform, switch, fork, loop),
 * coloured by Atlas tone, never by brand: the registry, not the designer, knows the products.
 */
import type { ReactNode } from 'react'
import { cx, Icon, type IconName } from '@iaf/ui'
import type { ParsedStep, StepType } from '@iaf/workflow-checks'
import type { ProviderType, RegistryProvider } from '../../lib/types'

export type GlyphTone = 'accent' | 'healthy' | 'warning' | 'critical' | 'neutral'

export type CustomGlyph = 'openapi' | 'http' | 'ansible' | 'terraform' | 'switch' | 'fork' | 'loop' | 'wait' | 'script' | 'end' | 'api' | 'undo'

export type GlyphKind = CustomGlyph | IconName

export interface Glyph {
  kind: GlyphKind
  tone: GlyphTone
}

const CUSTOM: Record<CustomGlyph, ReactNode> = {
  openapi: (
    <>
      <path d="M8.5 4C6.6 4 6 5 6 7v2.2c0 1.3-.8 2.3-2.2 2.8 1.4.5 2.2 1.5 2.2 2.8V17c0 2 .6 3 2.5 3" />
      <path d="M15.5 4c1.9 0 2.5 1 2.5 3v2.2c0 1.3.8 2.3 2.2 2.8-1.4.5-2.2 1.5-2.2 2.8V17c0 2-.6 3-2.5 3" />
      <circle cx="12" cy="12" r="1.1" fill="currentColor" stroke="none" />
    </>
  ),
  http: (
    <>
      <circle cx="12" cy="12" r="8.6" />
      <path d="M12 3.4c2.4 2.4 3.6 5.3 3.6 8.6s-1.2 6.2-3.6 8.6C9.6 18.2 8.4 15.3 8.4 12S9.6 5.8 12 3.4z" />
      <path d="M3.8 9.2h16.4M3.8 14.8h16.4" />
    </>
  ),
  ansible: (
    <>
      <circle cx="12" cy="12" r="8.8" />
      <path d="M8.2 16.8 12 7.2l4.6 10.4" />
      <path d="M10.1 12.6l6.5 4.2" />
    </>
  ),
  terraform: (
    <>
      <path d="M3.6 3.8 8.6 6.7v5.8L3.6 9.6z" fill="currentColor" fillOpacity="0.18" />
      <path d="M9.8 7.4l5 2.9v5.8l-5-2.9z" fill="currentColor" fillOpacity="0.18" />
      <path d="M16 10.3l5-2.9v5.8l-5 2.9z" fill="currentColor" fillOpacity="0.18" />
      <path d="M9.8 14.3l5 2.9V23l-5-2.9z" fill="currentColor" fillOpacity="0.18" />
    </>
  ),
  switch: (
    <>
      <path d="M3.5 12h5" />
      <path d="M8.5 12 13 6.5h7" />
      <path d="M8.5 12 13 17.5h7" />
      <path d="M17.5 4l2.6 2.5-2.6 2.5" />
      <path d="M17.5 15l2.6 2.5-2.6 2.5" />
    </>
  ),
  fork: (
    <>
      <path d="M3.5 12h5.5" />
      <path d="M9 12c2.8 0 3-6 6-6h5" />
      <path d="M9 12c2.8 0 3 6 6 6h5" />
      <path d="M9 12h11" />
    </>
  ),
  loop: (
    <>
      <path d="M4.2 11a7.9 7.9 0 0 1 13.6-4.6L20 8.6" />
      <path d="M20 3.6v5h-5" />
      <path d="M19.8 13a7.9 7.9 0 0 1-13.6 4.6L4 15.4" />
      <path d="M4 20.4v-5h5" />
    </>
  ),
  wait: (
    <>
      <path d="M6.5 3.5h11M6.5 20.5h11" />
      <path d="M7.5 3.5c0 4.6 9 4.8 9 8.5s-9 3.9-9 8.5" />
      <path d="M16.5 3.5c0 4.6-9 4.8-9 8.5s9 3.9 9 8.5" />
    </>
  ),
  script: (
    <>
      <path d="M8 7.5 3.5 12 8 16.5" />
      <path d="M16 7.5l4.5 4.5-4.5 4.5" />
      <path d="M13.6 4.5 10.4 19.5" />
    </>
  ),
  end: <rect x="7" y="7" width="10" height="10" rx="2" />,
  api: (
    <>
      <path d="M9.5 8.2a3.2 3.2 0 1 1 4.6 2.9l2.6 4.6" />
      <path d="M8.2 18.6a3.2 3.2 0 1 1 .9-4.4h5.2" />
      <path d="M18.3 13.7a3.2 3.2 0 1 1-2.4 5.4l-2.6-4.6" />
    </>
  ),
  undo: (
    <>
      <path d="M8.5 6.5 4.5 10.5l4 4" />
      <path d="M4.5 10.5H15a4.5 4.5 0 0 1 0 9h-3" />
    </>
  ),
}

const TONE: Record<GlyphTone, string> = {
  accent: 'text-accent',
  healthy: 'text-healthy',
  warning: 'text-warning',
  critical: 'text-critical',
  neutral: 'text-secondary',
}

export function toneClass(tone: GlyphTone): string {
  return TONE[tone]
}

function isCustom(kind: GlyphKind): kind is CustomGlyph {
  return Object.hasOwn(CUSTOM, kind)
}

export function NodeGlyph({ glyph, size = 40, className }: { glyph: Glyph; size?: number; className?: string }) {
  if (isCustom(glyph.kind)) {
    return (
      <svg viewBox="0 0 24 24" width={size} height={size} fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round" className={cx(TONE[glyph.tone], className)} aria-hidden="true">
        {CUSTOM[glyph.kind]}
      </svg>
    )
  }
  return <Icon name={glyph.kind} size={size} strokeWidth={1.5} className={cx(TONE[glyph.tone], className)} />
}

const PROVIDER_GLYPH: Record<ProviderType, Glyph> = {
  openapi: { kind: 'openapi', tone: 'accent' },
  http: { kind: 'http', tone: 'accent' },
  sdk: { kind: 'provider', tone: 'accent' },
  ansible: { kind: 'ansible', tone: 'critical' },
  terraform: { kind: 'terraform', tone: 'accent' },
  idp: { kind: 'grid', tone: 'healthy' },
}

export function providerGlyph(type: ProviderType | string | undefined): Glyph {
  return (type && PROVIDER_GLYPH[type as ProviderType]) || { kind: 'provider', tone: 'accent' }
}

const TYPE_GLYPH: Record<StepType, Glyph> = {
  provider: { kind: 'provider', tone: 'accent' },
  http: { kind: 'http', tone: 'accent' },
  ansible: { kind: 'ansible', tone: 'critical' },
  terraform: { kind: 'terraform', tone: 'accent' },
  idp: { kind: 'grid', tone: 'healthy' },
  activity: { kind: 'settings', tone: 'neutral' },
  run: { kind: 'workflow', tone: 'accent' },
  approval: { kind: 'approval', tone: 'healthy' },
  switch: { kind: 'switch', tone: 'warning' },
  fork: { kind: 'fork', tone: 'warning' },
  for: { kind: 'loop', tone: 'warning' },
  try: { kind: 'shield', tone: 'warning' },
  wait: { kind: 'wait', tone: 'warning' },
  set: { kind: 'edit', tone: 'neutral' },
  raise: { kind: 'alert-triangle', tone: 'critical' },
  use: { kind: 'resource', tone: 'accent' },
  register: { kind: 'database', tone: 'accent' },
  notify: { kind: 'send', tone: 'healthy' },
  script: { kind: 'script', tone: 'neutral' },
}

export function typeGlyph(type: StepType): Glyph {
  return TYPE_GLYPH[type]
}

/** A step's glyph: a provider call takes its provider's glyph (by provider type). */
export function stepGlyph(step: Pick<ParsedStep, 'type' | 'with'>, providers: readonly RegistryProvider[] = []): Glyph {
  if (step.type === 'provider') {
    const w = step.with
    const name = w && typeof w === 'object' && !Array.isArray(w) && typeof w.provider === 'string' ? w.provider : null
    const p = name ? providers.find((x) => x.name === name) : undefined
    if (p) return providerGlyph(p.type)
  }
  return TYPE_GLYPH[step.type]
}

/** Small interface glyphs the kit does not have: canvas controls, pins, sticky notes, the test flask. */
export type UiGlyphName = 'fit' | 'zoom-reset' | 'tidy' | 'flask' | 'pin' | 'sticky' | 'drag'

const UI: Record<UiGlyphName, ReactNode> = {
  fit: (
    <>
      <path d="M4 9V5.5A1.5 1.5 0 0 1 5.5 4H9" />
      <path d="M15 4h3.5A1.5 1.5 0 0 1 20 5.5V9" />
      <path d="M20 15v3.5a1.5 1.5 0 0 1-1.5 1.5H15" />
      <path d="M9 20H5.5A1.5 1.5 0 0 1 4 18.5V15" />
    </>
  ),
  'zoom-reset': (
    <>
      <circle cx="10.5" cy="10.5" r="6.5" />
      <path d="m20 20-4.9-4.9" />
      <path d="M9.2 8.6 10.8 7.5v6" />
    </>
  ),
  tidy: (
    <>
      <path d="m4 20 11-11" />
      <path d="m14.5 4.2.9 1.9 1.9.9-1.9.9-.9 1.9-.9-1.9-1.9-.9 1.9-.9z" />
      <path d="m19 11.6.5 1 1 .5-1 .5-.5 1-.5-1-1-.5 1-.5z" />
    </>
  ),
  flask: (
    <>
      <path d="M9 3h6" />
      <path d="M10 3v6.2L4.8 18.4A1.8 1.8 0 0 0 6.4 21h11.2a1.8 1.8 0 0 0 1.6-2.6L14 9.2V3" />
      <path d="M7.4 15h9.2" />
    </>
  ),
  pin: <path d="M15 4.5 19.5 9l-3 1.5-3.8 3.8.6 3.7-1.3 1.3-3.4-3.4L4.5 19.5M8.6 12.6l-3.4-3.4 1.3-1.3 3.7.6 3.8-3.8z" />,
  sticky: (
    <>
      <path d="M5 4h14v10l-6 6H5z" />
      <path d="M13 20v-6h6" />
    </>
  ),
  drag: (
    <>
      <circle cx="9" cy="6" r="1" />
      <circle cx="15" cy="6" r="1" />
      <circle cx="9" cy="12" r="1" />
      <circle cx="15" cy="12" r="1" />
      <circle cx="9" cy="18" r="1" />
      <circle cx="15" cy="18" r="1" />
    </>
  ),
}

export function UiGlyph({ name, size = 16, className, strokeWidth = 1.8 }: { name: UiGlyphName; size?: number; className?: string; strokeWidth?: number }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">
      {UI[name]}
    </svg>
  )
}

export function triggerGlyph(kind: 'api' | 'alert' | 'event' | 'other' | 'error'): Glyph {
  switch (kind) {
    case 'api':
      return { kind: 'api', tone: 'accent' }
    case 'alert':
      return { kind: 'bell', tone: 'warning' }
    case 'event':
      return { kind: 'pulse', tone: 'healthy' }
    case 'error':
      return { kind: 'warning', tone: 'critical' }
    default:
      return { kind: 'bolt', tone: 'neutral' }
  }
}
