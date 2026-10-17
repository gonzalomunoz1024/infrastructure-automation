import { cx } from '../lib/cx'

export function Skeleton({ className }: { className?: string }) {
  return <div className={cx('skeleton rounded-md', className)} aria-hidden="true" />
}

/** The standard loading block: n shimmer rows. */
export function SkeletonRows({ n = 3, className, rowClassName = 'h-14' }: { n?: number; className?: string; rowClassName?: string }) {
  return (
    <div className={cx('space-y-4', className)} role="status" aria-label="Loading">
      {Array.from({ length: n }, (_, i) => (
        <Skeleton key={i} className={rowClassName} />
      ))}
    </div>
  )
}

/** Text-shaped shimmer: a few lines, the last one shorter. */
export function SkeletonText({ lines = 3, className }: { lines?: number; className?: string }) {
  return (
    <div className={cx('space-y-2', className)} role="status" aria-label="Loading">
      {Array.from({ length: lines }, (_, i) => (
        <Skeleton key={i} className={cx('h-3.5', i === lines - 1 ? 'w-2/3' : 'w-full')} />
      ))}
    </div>
  )
}
