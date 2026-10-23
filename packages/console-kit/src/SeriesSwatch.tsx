import { cx } from '@iaf/ui'
import { SERIES_CSS, seriesColor, type SeriesSlot } from './palette'

/*
 * The series palette in the page: SeriesStyles serves its custom properties (React hoists the
 * style into the head and keeps one copy, however many charts ask for it), and SeriesSwatch is
 * the key that sits beside a series' name in a legend, a tooltip or a table. The name itself is
 * always in a text colour: the swatch carries the identity, the text never wears the series hue.
 */

/** Serves the palette's custom properties once per document. */
export function SeriesStyles() {
  return (
    <style href="iaf-series-palette" precedence="default">
      {SERIES_CSS}
    </style>
  )
}

/** A series' key: a square for bars and areas, a short line for lines and tooltips. */
export function SeriesSwatch({ slot, shape = 'square', className }: { slot: SeriesSlot; shape?: 'square' | 'line'; className?: string }) {
  return (
    <>
      <SeriesStyles />
      {shape === 'line' ? (
        <svg width="14" height="6" aria-hidden="true" className={cx('shrink-0', className)}>
          <line x1="1" y1="3" x2="13" y2="3" strokeWidth="2" strokeLinecap="round" style={{ stroke: seriesColor(slot) }} />
        </svg>
      ) : (
        <svg width="10" height="10" aria-hidden="true" className={cx('shrink-0', className)}>
          <rect width="10" height="10" rx="2" style={{ fill: seriesColor(slot) }} />
        </svg>
      )}
    </>
  )
}
