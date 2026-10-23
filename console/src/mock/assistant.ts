import type { RouteAnswer, RouteContext } from './commands'
import type { MockData } from './data'

/*
 * Phase 9 in the mock BFF: The copilot (POST /console/v1/assistant/turns, GET …/conversations/{id}) and plain-English search (GET /console/v1/search:interpret).
 * Answers null for any path it does not serve, so the server tries the next routes.
 */

export function assistantRoutes(_data: MockData, _ctx: RouteContext, _path: string, _params: URLSearchParams, _method: string): RouteAnswer | null {
  return null
}
