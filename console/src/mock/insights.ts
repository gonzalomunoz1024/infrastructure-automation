import type { RouteAnswer, RouteContext } from './commands'
import type { MockData } from './data'

/*
 * Phase 9 in the mock BFF: The executive home's numbers (GET /console/v1/insights) and grouped counts (GET /console/v1/search:counts).
 * Answers null for any path it does not serve, so the server tries the next routes.
 */

export function insightRoutes(_data: MockData, _ctx: RouteContext, _path: string, _params: URLSearchParams, _method: string): RouteAnswer | null {
  return null
}
