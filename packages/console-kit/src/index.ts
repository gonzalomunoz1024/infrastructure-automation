/*
 * @iaf/console-kit: the console's components above Atlas. Forks of the platform's composites
 * (SchemaForm, ResourcePicker, LineageGraph, JsonEditor, PreviewCard, StepTimeline, ProblemList)
 * fed through a ConsoleKitProvider instead of the platform's API client, plus the pieces the
 * console adds: VirtualTable, WfrnText, LifecycleBadge, StatusBadge, PropertyFilterBar and CursorPager.
 */

export * from './types'
export { isApiErrorLike, errorMessage, type ApiErrorLike } from './errors'
export { ConsoleKitProvider, useConsoleKit, useAsync, DEFAULT_LINKS, EMPTY_DATA, type ConsoleKitData, type ConsoleKitLinks, type ConsoleKitValue, type ConsoleKitProviderProps, type ResourceRef, type AsyncState } from './provider'
export { Mono, CopyableId, When, Facts, Section, ErrorState, TextLink, ExternalLink, EffectTag, resourceTone, checkDot, titleCase, useNow, parseDuration, countdown, deadlineOf, shortJobId, summarize, type FactItem } from './common'
export { xConsoleOf, isXConsoleFormat, isXConsoleWidget, columnFields, searchableFields, sensitiveFields, mutableFields, groupFields, unitOf, type FieldGroup } from './xconsole'
export { formatValue, formatValueText, formatField, HIDDEN_VALUE, type FormatOptions } from './format'
export { ProblemList, normalizeField, fieldRoot, problemsFor, unfieldedProblems, type ProblemListProps } from './ProblemList'
export { JsonEditor, parseJsonText, type JsonEditorProps } from './JsonEditor'
export { ResourcePicker, type ResourcePickerProps } from './ResourcePicker'
export {
  SchemaForm,
  schemaType,
  createFormOf,
  isCreateRequest,
  defaultsFor,
  fieldNames,
  coerceScalar,
  coerceBySchema,
  validateAgainst,
  isComplete,
  splitProblems,
  WFRN_EXAMPLE,
  IMMUTABLE_NOTE,
  type SchemaValue,
  type SchemaFormProps,
  type CreateForm,
} from './SchemaForm'
export { LineageGraph, ResourceLineage, lineageGraph, lineageLayout, type LineageGraphProps, type ResourceLineageProps, type LineageColumn, type LineageLayout } from './LineageGraph'
export { PreviewCard, SKIPPED_NOTE, withoutSkippedNote, viaLabel, type PreviewCardProps } from './PreviewCard'
export { StepTimeline, buildOf, childJobIdOf, continuedAfterFailure, type StepTimelineProps } from './StepTimeline'
export { VirtualTable, sortRows, type VirtualColumn, type VirtualSort, type VirtualTableProps, type CellValue } from './VirtualTable'
export { WfrnText, displayWfrn, type WfrnTextProps } from './WfrnText'
export { LifecycleBadge, lifecycleOf, isLifecycleClass, type LifecycleBadgeProps } from './LifecycleBadge'
export { StatusBadge, statusTone, type StatusBadgeProps, type StatusTone } from './StatusBadge'
export { DotTag, type DotTagProps } from './DotTag'
export {
  PropertyFilterBar,
  parseToken,
  formatToken,
  matchesToken,
  applyFilters,
  tokensToQuery,
  OPERATOR_LABEL,
  ALL_OPERATORS,
  DEFAULT_OPERATORS,
  NUMERIC_OPERATORS,
  type FilterOperator,
  type FilterProperty,
  type FilterToken,
  type PropertyFilterBarProps,
} from './PropertyFilterBar'
export { CursorPager, useCursorPaging, nextCursorFromLink, PAGE_SIZES, type CursorPagerProps, type CursorPaging } from './CursorPager'
