/*
 * The colours of a chart's series, as tokens. Atlas gives a chart one series colour, the accent;
 * a stacked cost chart needs up to seven named groups, so the console carries an eight-slot
 * categorical palette of its own. The slots are in a fixed order, checked for colour-vision
 * deficiency on neighbouring pairs and for contrast against Atlas's light (#ffffff) and dark
 * (#000000, #1c1c1e) surfaces; the dark column is the same hues stepped for the dark surface, not
 * an inverted set. Three light slots (3, 4 and 5) are under 3:1 against white, so a chart that
 * uses them names its series in a legend and offers its values as a table. A ninth group is never
 * a new hue: it folds into "Other", drawn in the tertiary ink.
 *
 * The hex values live here and nowhere else: SeriesStyles serves them once as CSS custom
 * properties (`--series-1` to `--series-8`) for `:root` and Atlas's `.dark`, and marks refer to
 * `var(--series-<n>)`.
 */

/** How many named series a chart can tell apart: the rest fold into "Other". */
export const SERIES_SLOTS = 8

/** The categorical palette, slot 1 first, for each theme. */
export const SERIES_PALETTE = {
  light: ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#6250d6', '#e34948'],
  dark: ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767'],
} as const

/** A series' slot (1 to 8), or the folded rest. */
export type SeriesSlot = number | 'other'

/** The CSS colour of a slot: its custom property, or Atlas's tertiary ink for "Other". */
export function seriesColor(slot: SeriesSlot): string {
  if (slot === 'other' || !Number.isInteger(slot) || slot < 1 || slot > SERIES_SLOTS) return 'var(--color-text-tertiary)'
  return `var(--series-${slot})`
}

const rules = (colors: readonly string[]) => colors.map((c, i) => `--series-${i + 1}:${c};`).join('')

/** The stylesheet SeriesStyles serves: the light slots on `:root`, the dark ones under `.dark`. */
export const SERIES_CSS = `:root{${rules(SERIES_PALETTE.light)}}.dark{${rules(SERIES_PALETTE.dark)}}`
