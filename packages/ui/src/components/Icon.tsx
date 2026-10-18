import type { ReactNode } from 'react'

/**
 * The Atlas icon set — one voice for every glyph in the app.
 * SF-Symbols-adjacent: 24 viewBox, 1.7 stroke, round caps and joins.
 * Add glyphs here; never inline one-off SVGs in components.
 */
export type IconName =
  | 'search'
  | 'plus'
  | 'minus'
  | 'close'
  | 'close-circle'
  | 'check'
  | 'chevron-down'
  | 'chevron-right'
  | 'chevron-left'
  | 'chevron-up'
  | 'arrow-right'
  | 'arrow-left'
  | 'play'
  | 'pause'
  | 'stop'
  | 'refresh'
  | 'trash'
  | 'copy'
  | 'link'
  | 'external'
  | 'expand'
  | 'settings'
  | 'user'
  | 'users'
  | 'shield'
  | 'key'
  | 'lock'
  | 'bell'
  | 'clock'
  | 'history'
  | 'calendar'
  | 'filter'
  | 'list'
  | 'grid'
  | 'layers'
  | 'folder'
  | 'tag'
  | 'workflow'
  | 'provider'
  | 'job'
  | 'approval'
  | 'resource'
  | 'database'
  | 'audit'
  | 'document'
  | 'release'
  | 'sparkles'
  | 'terminal'
  | 'git-branch'
  | 'alert-triangle'
  | 'warning'
  | 'info'
  | 'help'
  | 'eye'
  | 'edit'
  | 'upload'
  | 'download'
  | 'send'
  | 'bolt'
  | 'chart'
  | 'pulse'
  | 'ring'
  | 'sun'
  | 'moon'
  | 'laptop'
  | 'home'
  | 'menu'
  | 'sidebar'
  | 'command'
  | 'more-horizontal'
  | 'drag-handle'

const DOC = (
  <>
    <path d="M6.5 3.5h7.6L18.5 8v12.5h-12z" />
    <path d="M14 3.8V8h4.3M9 12h6M9 15.5h6" />
  </>
)

const TRIANGLE = (
  <>
    <path d="M12 4.2 21 19.8H3z" />
    <path d="M12 10v4.2" />
    <circle cx="12" cy="16.9" r="0.45" fill="currentColor" stroke="none" />
  </>
)

const GLYPHS: Record<IconName, ReactNode> = {
  search: (
    <>
      <circle cx="11" cy="11" r="6.5" />
      <path d="M19.8 19.8l-4.2-4.2" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  minus: <path d="M5 12h14" />,
  close: <path d="M6 6l12 12M18 6L6 18" />,
  'close-circle': (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M9.2 9.2l5.6 5.6M14.8 9.2l-5.6 5.6" />
    </>
  ),
  check: <path d="M5 12.8l4.6 4.6L19 7" />,
  'chevron-down': <path d="M5 9.5l7 7 7-7" />,
  'chevron-right': <path d="M9.5 5l7 7-7 7" />,
  'chevron-left': <path d="M14.5 5l-7 7 7 7" />,
  'chevron-up': <path d="M5 14.5l7-7 7 7" />,
  'arrow-right': <path d="M4 12h15M13.5 6l6 6-6 6" />,
  'arrow-left': <path d="M20 12H5M10.5 6l-6 6 6 6" />,
  play: <path d="M7.5 4.8v14.4L19 12z" />,
  pause: <path d="M8 5v14M16 5v14" />,
  stop: <rect x="6" y="6" width="12" height="12" rx="2.5" />,
  refresh: <path d="M20.5 15a8.5 8.5 0 1 1-2-8.8L20.5 10M20.5 4.5V10H15" />,
  trash: <path d="M4.5 7h15M9.5 7V4.5h5V7M6.5 7l.7 12.6h9.6L17.5 7M10 11v5.5M14 11v5.5" />,
  copy: (
    <>
      <rect x="9" y="9" width="11" height="11" rx="2.5" />
      <path d="M5.5 14.5A2.5 2.5 0 0 1 4 12.2V6.5A2.5 2.5 0 0 1 6.5 4h5.7a2.5 2.5 0 0 1 2.3 1.5" />
    </>
  ),
  link: (
    <path d="M10 14a3.8 3.8 0 0 0 5.4 0l3-3a3.8 3.8 0 0 0-5.4-5.4l-1.3 1.3M14 10a3.8 3.8 0 0 0-5.4 0l-3 3a3.8 3.8 0 0 0 5.4 5.4l1.3-1.3" />
  ),
  external: (
    <path d="M18.5 13.5v5a1.5 1.5 0 0 1-1.5 1.5H5.5A1.5 1.5 0 0 1 4 18.5V7a1.5 1.5 0 0 1 1.5-1.5h5M15 4.5h4.5V9M19.5 4.5L11 13" />
  ),
  expand: <path d="M14 4.5h5.5V10M10 19.5H4.5V14M19.5 4.5L13.5 10.5M4.5 19.5l6-6" />,
  settings: (
    <>
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 11-2.83 2.83l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 11-2.83-2.83l.06-.06a1.65 1.65 0 00.33-1.82 1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 112.83-2.83l.06.06a1.65 1.65 0 001.82.33H9a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 112.83 2.83l-.06.06a1.65 1.65 0 00-.33 1.82V9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z" />
    </>
  ),
  user: (
    <>
      <circle cx="12" cy="8" r="4" />
      <path d="M4.5 20.5a7.5 7.5 0 0 1 15 0" />
    </>
  ),
  users: (
    <>
      <circle cx="9" cy="8" r="3.5" />
      <path d="M2.5 19.5a6.5 6.5 0 0 1 13 0M15.5 4.6a3.5 3.5 0 0 1 0 6.8M18 12.9a6.5 6.5 0 0 1 3.5 5.6" />
    </>
  ),
  shield: <path d="M12 3l7 3v5.5c0 4.4-3 7.7-7 9.5-4-1.8-7-5.1-7-9.5V6l7-3z" />,
  key: (
    <>
      <circle cx="8" cy="15.5" r="4" />
      <path d="M10.8 12.7L19.5 4M16.3 7.2l2.5 2.5M13.8 9.7l2.5 2.5" />
    </>
  ),
  lock: (
    <>
      <rect x="5" y="10.5" width="14" height="10" rx="2.5" />
      <path d="M8 10.5V7.5a4 4 0 0 1 8 0v3" />
    </>
  ),
  bell: <path d="M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15zM10 20.5a2 2 0 0 0 4 0" />,
  clock: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M12 7.5V12l3 2" />
    </>
  ),
  history: <path d="M3.5 12a8.5 8.5 0 1 0 2.5-6L3.5 8.5M3.5 4v4.5H8M12 8v4.2l2.8 1.6" />,
  calendar: (
    <>
      <rect x="3.5" y="5" width="17" height="15.5" rx="2.5" />
      <path d="M3.5 9.5h17M8 3v4M16 3v4" />
    </>
  ),
  filter: <path d="M4 5.5h16l-6.2 7.2v5.3l-3.6 1.8v-7.1z" />,
  list: (
    <>
      <path d="M8.5 6.5h12M8.5 12h12M8.5 17.5h12" />
      <circle cx="4.2" cy="6.5" r="0.9" fill="currentColor" stroke="none" />
      <circle cx="4.2" cy="12" r="0.9" fill="currentColor" stroke="none" />
      <circle cx="4.2" cy="17.5" r="0.9" fill="currentColor" stroke="none" />
    </>
  ),
  grid: (
    <>
      <rect x="4" y="4" width="6.5" height="6.5" rx="1.8" />
      <rect x="13.5" y="4" width="6.5" height="6.5" rx="1.8" />
      <rect x="4" y="13.5" width="6.5" height="6.5" rx="1.8" />
      <rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.8" />
    </>
  ),
  layers: <path d="M12 3.5l8.5 4.5L12 12.5 3.5 8zM3.5 12l8.5 4.5 8.5-4.5M3.5 16l8.5 4.5 8.5-4.5" />,
  folder: (
    <path d="M3.5 6.5A1.5 1.5 0 0 1 5 5h4.5l2 2.5H19a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5H5a1.5 1.5 0 0 1-1.5-1.5z" />
  ),
  tag: (
    <>
      <path d="M3.5 12.5V4.5h8l9 9-8 8z" />
      <circle cx="7.5" cy="8.5" r="1" fill="currentColor" stroke="none" />
    </>
  ),
  workflow: (
    <>
      <circle cx="5.5" cy="12" r="2.5" />
      <circle cx="18.5" cy="6.5" r="2.5" />
      <circle cx="18.5" cy="17.5" r="2.5" />
      <path d="M8 12h3.5M11.5 12l5-4M11.5 12l5 4" />
    </>
  ),
  provider: <path d="M9 3v4M15 3v4M6.5 7h11v3.5a5.5 5.5 0 0 1-11 0zM12 16v5" />,
  job: (
    <>
      <rect x="4" y="4" width="16" height="16" rx="4" />
      <path d="M10 9v6l5-3z" />
    </>
  ),
  approval: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M8.5 12.3l2.4 2.4 4.6-4.9" />
    </>
  ),
  resource: <path d="M12 3l8 4.5v9L12 21l-8-4.5v-9zM4 7.5l8 4.5 8-4.5M12 12v9" />,
  database: (
    <>
      <ellipse cx="12" cy="6" rx="7.5" ry="3" />
      <path d="M4.5 6v12c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3V6M4.5 12c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3" />
    </>
  ),
  audit: DOC,
  document: DOC,
  release: (
    <>
      <path d="M12 3.5c2.6 2 4 5.3 4 9v3H8v-3c0-3.7 1.4-7 4-9z" />
      <circle cx="12" cy="10" r="1.6" />
      <path d="M8 13l-2.5 2.5v3L8 17M16 13l2.5 2.5v3L16 17M10.5 18.5h3L12 21.5z" />
    </>
  ),
  sparkles: (
    <path d="M12 3.5c.7 3.9 2.1 5.3 6 6-3.9.7-5.3 2.1-6 6-.7-3.9-2.1-5.3-6-6 3.9-.7 5.3-2.1 6-6zM18.7 14.6c.35 1.95 1.05 2.65 3 3-1.95.35-2.65 1.05-3 3-.35-1.95-1.05-2.65-3-3 1.95-.35 2.65-1.05 3-3z" />
  ),
  terminal: <path d="M4 6.5l5.5 5L4 16.5M12 17h8" />,
  'git-branch': (
    <>
      <circle cx="6.5" cy="5.5" r="2.5" />
      <circle cx="6.5" cy="18.5" r="2.5" />
      <circle cx="17.5" cy="8.5" r="2.5" />
      <path d="M6.5 8v8M17.5 11a5.5 5.5 0 0 1-5.5 5.5h-1a4.5 4.5 0 0 0-4.5 1.5" />
    </>
  ),
  'alert-triangle': TRIANGLE,
  warning: TRIANGLE,
  info: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11.5v4.5" />
      <circle cx="12" cy="8" r="0.5" fill="currentColor" stroke="none" />
    </>
  ),
  help: (
    <>
      <circle cx="12" cy="12" r="8.5" />
      <path d="M9.6 9.3a2.5 2.5 0 1 1 3.4 2.3c-.7.3-1 .8-1 1.4v.5" />
      <circle cx="12" cy="16.9" r="0.45" fill="currentColor" stroke="none" />
    </>
  ),
  eye: (
    <>
      <path d="M2.8 12S6.5 5.8 12 5.8 21.2 12 21.2 12 17.5 18.2 12 18.2 2.8 12 2.8 12z" />
      <circle cx="12" cy="12" r="2.9" />
    </>
  ),
  edit: <path d="M4.5 19.5l4.3-1L19.5 7.8a2.1 2.1 0 0 0-3-3L5.8 15.5zM14.3 7l3 3" />,
  upload: <path d="M12 16V4M6.5 9.5L12 4l5.5 5.5M4.5 20h15" />,
  download: <path d="M12 4v12M6.5 10.5L12 16l5.5-5.5M4.5 20h15" />,
  send: <path d="M20.5 3.5L3.5 10.5l7 2.5 2.5 7zM10.5 13l10-9.5" />,
  bolt: <path d="M13 3L5 13.5h6l-1 7.5 9-11h-6z" />,
  chart: <path d="M5 20v-8M12 20V5M19 20v-5" />,
  pulse: <path d="M3 12.5h4l2.6-6.5 4.2 12 2.6-6.5H21" />,
  ring: <circle cx="12" cy="12" r="8" />,
  sun: (
    <>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4" />
    </>
  ),
  moon: <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />,
  laptop: (
    <>
      <rect x="4.5" y="5" width="15" height="10.5" rx="2" />
      <path d="M2.5 18.5h19" />
    </>
  ),
  home: <path d="M4 11l8-7 8 7v8.5a1 1 0 0 1-1 1h-4.5V15h-5v5.5H5a1 1 0 0 1-1-1z" />,
  menu: <path d="M4 7h16M4 12h16M4 17h16" />,
  sidebar: (
    <>
      <rect x="3.5" y="4.5" width="17" height="15" rx="2.5" />
      <path d="M9.5 4.5v15" />
    </>
  ),
  command: (
    <path d="M9 9V6a3 3 0 1 0-3 3h3zm0 0v6m0-6h6m-6 6H6a3 3 0 1 0 3 3v-3zm6-6V6a3 3 0 1 1 3 3h-3zm0 0v6m0 0h3a3 3 0 1 1-3 3v-3z" />
  ),
  'more-horizontal': (
    <>
      <circle cx="6" cy="12" r="1.2" fill="currentColor" stroke="none" />
      <circle cx="12" cy="12" r="1.2" fill="currentColor" stroke="none" />
      <circle cx="18" cy="12" r="1.2" fill="currentColor" stroke="none" />
    </>
  ),
  'drag-handle': (
    <>
      <circle cx="9" cy="6" r="1.2" fill="currentColor" stroke="none" />
      <circle cx="15" cy="6" r="1.2" fill="currentColor" stroke="none" />
      <circle cx="9" cy="12" r="1.2" fill="currentColor" stroke="none" />
      <circle cx="15" cy="12" r="1.2" fill="currentColor" stroke="none" />
      <circle cx="9" cy="18" r="1.2" fill="currentColor" stroke="none" />
      <circle cx="15" cy="18" r="1.2" fill="currentColor" stroke="none" />
    </>
  ),
}

/** Every glyph name, for pickers and tests. */
export const ICON_NAMES = Object.keys(GLYPHS) as IconName[]

export interface IconProps {
  name: IconName
  /** rendered square size in px (default 18) */
  size?: number
  className?: string
  strokeWidth?: number
  /** screen-reader text; icons are decorative unless given one */
  title?: string
}

export function Icon({ name, size = 18, className, strokeWidth = 1.7, title }: IconProps) {
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden={title ? undefined : 'true'}
      role={title ? 'img' : undefined}
    >
      {title && <title>{title}</title>}
      {GLYPHS[name]}
    </svg>
  )
}
