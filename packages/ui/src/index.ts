// lib
export { cx } from './lib/cx'
export { atlasColors } from './lib/tokens'
export { fuzzyMatch, fuzzyFilter, type FuzzyResult, type FuzzyHit } from './lib/fuzzy'
export {
  relativeTime,
  duration,
  formatNumber,
  percent,
  bytes,
  shortDate,
  dayLabel,
  longDate,
  isoDate,
  truncateMiddle,
  plural,
  type DateInput,
  type NumberOptions,
  type ShortDateOptions,
} from './lib/format'
export { nextId, uid, shortId } from './lib/id'

// hooks
export {
  useTheme,
  getThemeMode,
  setThemeMode,
  applyTheme,
  resolveTheme,
  systemPrefersDark,
  THEME_STORAGE_KEY,
  type Theme,
  type ThemeMode,
  type ResolvedTheme,
} from './hooks/useTheme'
export { useDismiss } from './hooks/useDismiss'
export { useHotkey, parseHotkey, matchesHotkey, isEditableTarget, type Hotkey, type HotkeyOptions } from './hooks/useHotkey'
export { useMediaQuery } from './hooks/useMediaQuery'
export { useLocalStorage } from './hooks/useLocalStorage'
export { useDebounce, useDebouncedCallback } from './hooks/useDebounce'

// components
export { Icon, ICON_NAMES, type IconName, type IconProps } from './components/Icon'
export { Button, IconButton, Spinner, type ButtonProps, type ButtonVariant, type ButtonSize, type IconButtonProps } from './components/Button'
export {
  Portal,
  Modal,
  Drawer,
  PopoverPanel,
  OverlayHeader,
  OverlayBody,
  OverlayFooter,
  useOverlayClose,
  useOverlayLifecycle,
  type ModalProps,
  type DrawerProps,
  type OverlayHeaderProps,
  type OverlayLifecycle,
} from './components/Overlay'
export { Select, type SelectOption, type SelectProps } from './components/Select'
export { Segmented, type SegmentedOption, type SegmentedProps } from './components/Segmented'
export { Tabs, type TabItem, type TabsProps } from './components/Tabs'
export {
  Field,
  Input,
  Textarea,
  Checkbox,
  Switch,
  inputClass,
  type FieldProps,
  type InputProps,
  type TextareaProps,
  type CheckboxProps,
  type SwitchProps,
} from './components/Field'
export { StatusDot, type DotKind, type StatusDotProps } from './components/StatusDot'
export { Tag, StateTag, jobState, stateTone, type Tone, type TagProps, type JobState, type StateTagProps } from './components/Tag'
export { Badge, type BadgeProps } from './components/Badge'
export { EmptyState, type EmptyStateProps } from './components/EmptyState'
export { Skeleton, SkeletonRows, SkeletonText } from './components/Skeleton'
export { ToastProvider, useToast, type ToastApi, type ToastOptions, type ToastTone, type ToastAction } from './components/Toast'
export { Table, type TableColumn, type TableProps, type TableSort, type CellValue } from './components/Table'
export { Card, CardRow, type CardProps, type CardRowProps } from './components/Card'
export { Kbd, modKeyLabel, isApplePlatform } from './components/Kbd'
export { Tooltip, type TooltipProps, type TooltipSide } from './components/Tooltip'
export { CommandPalette, useCommandK, type PaletteItem, type CommandPaletteProps } from './components/CommandPalette'
export { AppHeader, type AppHeaderProps } from './components/AppHeader'
export { Sidebar, type SidebarProps, type SidebarGroup, type SidebarItem } from './components/Sidebar'
export { PageHeader, type PageHeaderProps } from './components/PageHeader'
export { Stat, type StatProps, type StatDelta } from './components/Stat'
export {
  Sparkline,
  LineChart,
  BarChart,
  niceTicks,
  type SparklineProps,
  type LineChartProps,
  type BarChartProps,
  type ChartSeries,
} from './components/Charts'
export { ProgressBar, type ProgressBarProps, type ProgressTone } from './components/ProgressBar'
export { Stepper, type StepperProps, type StepperStep } from './components/Stepper'
export {
  DiffView,
  parseUnifiedDiff,
  diffStats,
  type DiffViewProps,
  type DiffMode,
  type DiffFile,
  type DiffHunk,
  type DiffLine,
  type DiffLineType,
  type DiffStats,
} from './components/DiffView'
export { CodeBlock, type CodeBlockProps } from './components/CodeBlock'
export { JsonView, type JsonViewProps } from './components/JsonView'
export { ConfirmDialog, type ConfirmDialogProps } from './components/ConfirmDialog'
export { UserMenu, type UserMenuProps, type UserMenuUser, type UserMenuItem } from './components/UserMenu'
export { CopyButton, CopyIconButton } from './components/CopyButton'
export { ErrorBoundary, type ErrorBoundaryProps } from './components/ErrorBoundary'
export { ThemeToggle } from './components/ThemeToggle'
