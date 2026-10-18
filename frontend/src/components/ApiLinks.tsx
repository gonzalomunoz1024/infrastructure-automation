/**
 * Where the API documents itself: the OpenAPI document of every published workflow and
 * resource type, and the Swagger UI over it (outside /api/v1, with its own bearer token box).
 */
import { Icon, cx } from '@iaf/ui'
import { OPENAPI_URL, SWAGGER_UI_URL } from '../lib/api'

const LINK = 'inline-flex items-center gap-1 text-accent transition-opacity hover:opacity-70'

export function ApiLinks({ className, lead }: { className?: string; lead?: string }) {
  return (
    <span className={cx('inline-flex flex-wrap items-center gap-x-3 gap-y-1 text-caption', className)} data-testid="api-links">
      {lead && <span className="text-tertiary">{lead}</span>}
      <a href={SWAGGER_UI_URL} target="_blank" rel="noreferrer" className={LINK} title="Try every operation in the browser; a bearer token typed into the page goes with every request">
        Swagger UI
        <Icon name="external" size={12} />
      </a>
      <a href={OPENAPI_URL} target="_blank" rel="noreferrer" className={cx(LINK, 'font-mono')} title="OpenAPI 3.1 of every published workflow and resource type">
        openapi.json
        <Icon name="external" size={12} />
      </a>
    </span>
  )
}
