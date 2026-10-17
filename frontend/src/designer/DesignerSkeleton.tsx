/** The designer while it loads: its header and a row of node-shaped shimmers on the dot grid. */
import { cx, Skeleton } from '@iaf/ui'

export function DesignerSkeleton({ label = 'Loading the workflow' }: { label?: string }) {
  return (
    <div className="flex h-full flex-col" role="status" aria-label={label}>
      <div className="flex h-14 shrink-0 items-center gap-3 border-b border-stroke-light px-3">
        <Skeleton className="h-7 w-7 rounded-full" />
        <Skeleton className="h-5 w-44" />
        <Skeleton className="h-4 w-14 rounded-full" />
        <div className="mx-auto flex gap-2">
          <Skeleton className="h-7 w-16" />
          <Skeleton className="h-7 w-24" />
          <Skeleton className="h-7 w-20" />
        </div>
        <Skeleton className="h-8 w-24 rounded-full" />
        <Skeleton className="h-8 w-16" />
        <Skeleton className="h-8 w-20" />
      </div>
      <div className="bg-dot-grid relative min-h-0 flex-1 overflow-hidden">
        <div className="absolute left-1/2 top-1/2 flex -translate-x-1/2 -translate-y-1/2 items-center gap-[150px]">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="flex flex-col items-center gap-3">
              <Skeleton className={cx('h-[100px] w-[100px] rounded-lg', i === 0 && 'rounded-l-[36px]')} />
              <Skeleton className="h-3 w-20" />
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
