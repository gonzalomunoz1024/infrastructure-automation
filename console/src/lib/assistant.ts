import { get, qs, request } from './api'
import { BFF_BASE } from './bff'
import type { ResourceState } from './types'

/*
 * The console copilot (WP 9.1), behind the BFF's AssistantPort (hub decision 27): a turn starts
 * with `POST /console/v1/assistant/turns` (202) and its answer is read from the conversation,
 * which the panel polls while it is `thinking`. The copilot never acts: it answers in words and
 * proposals the console opens for the person to review. Plain English becomes the list's own
 * filter through `GET /console/v1/search:interpret` (the search indexer's reading).
 */

const b = (path: string) => `${BFF_BASE}${path}`
const enc = encodeURIComponent

/** The list's own filter names; `q` holds only what they cannot say (free text, wfrn patterns, created:/updated: terms). */
export interface SearchFilter {
  q?: string
  state?: ResourceState
  drift?: boolean
  region?: string
  tags?: Record<string, string>
}

export type Confidence = 'high' | 'medium' | 'low'

export interface SearchInterpretation {
  /** the type the text names, when it names one */
  type?: string
  filter: SearchFilter
  /** name, -name, created, -created, updated, -updated */
  sort?: string
  /** one sentence saying what was understood */
  explanation: string
  confidence: Confidence
  /** words the reading left out */
  unparsed?: string[]
  /** the whole reading as one search query */
  q: string
  /** how many resources it lists now */
  total: number
}

export interface AssistantContext {
  /** the console route, such as /console/compute/vm */
  path?: string
  service?: string
  type?: string
  wfrn?: string
  filter?: SearchFilter
}

export interface AssistantAsk {
  conversationId?: string
  intent: 'ask' | 'explain'
  /** needed for `ask` */
  message?: string
  /** `explain` needs `wfrn` */
  context?: AssistantContext
}

export interface AssistantProposal {
  kind: 'filter' | 'create' | 'action' | 'open'
  /** what opening it does, such as "List the 12 stopped VMs in prod" */
  label: string
  service?: string
  type?: string
  filter?: SearchFilter
  sort?: string
  /** create and action: the inputs the copilot could fill */
  input?: Record<string, unknown>
  /** required inputs it could not fill */
  missing?: string[]
  wfrn?: string
  /** action: the verb to open */
  verb?: string
  /** open: a console route */
  path?: string
}

export interface ResourceExplanation {
  wfrn: string
  summary: string
  sections?: { title: string; text: string }[]
  facts?: {
    monthlyCost?: number
    currency?: string
    createdAt?: string
    createdBy?: string
    lastChangeAt?: string
    lastChangeBy?: string
    dependents?: number
    drift?: boolean
  }
}

export interface AssistantTurn {
  turnId: string
  role: 'person' | 'assistant'
  at: string
  status?: 'thinking' | 'answered' | 'failed'
  /** plain text in short paragraphs, no markup */
  text?: string
  proposals?: AssistantProposal[]
  explanation?: ResourceExplanation
  error?: { code: string; message: string }
}

export interface AssistantConversation {
  conversationId: string
  status: 'thinking' | 'ready' | 'failed'
  startedAt?: string
  /** oldest first */
  turns: AssistantTurn[]
}

export const assistantApi = {
  interpret: (q: string, type?: string) => get<SearchInterpretation>(`${b('/search:interpret')}${qs({ q, type })}`),
  ask: (body: AssistantAsk) => request<{ conversationId: string; turnId: string }>('POST', b('/assistant/turns'), body),
  conversation: (conversationId: string) => get<AssistantConversation>(b(`/assistant/conversations/${enc(conversationId)}`)),
}
