// Type tests of the typed client, checked by `npm run typecheck` (never run): each
// `@ts-expect-error` must be an error, and everything else must compile.

import { createConsoleClient, type ConsoleApiError, type Denial, type PageItem, type ResponseData, type Schema } from '@iaf/console-sdk';

const client = createConsoleClient({ endpoint: 'https://console.example.com', credentials: 'token' });

export async function typedCalls(): Promise<void> {
  const job: Schema<'JobDetail'> = await client.call('getJob', { path: { jobId: 'j-1' } });
  const status: 'queued' | 'running' | 'waiting-approval' | 'needs-attention' | 'succeeded' | 'failed' | 'cancelled' | 'rolled-back' = job.status;
  void status;

  // @ts-expect-error getJob needs its path parameter
  await client.call('getJob');
  // @ts-expect-error the path parameter is jobId
  await client.call('getJob', { path: { id: 'j-1' } });
  // @ts-expect-error no such operation
  await client.call('getJobs');

  // nothing required: the input is optional
  const page = await client.call('listJobs');
  const next: string | null = page.next;
  void next;
  await client.call('listJobs', { query: { status: 'failed', limit: 10 }, account: '123456789012', region: 'eu-west-1' });

  // a pattern parameter takes any name it allows
  await client.call('listResources', { path: { type: 'vm' }, query: { 'tag.env': 'prod', state: 'active', drift: true } });
  // @ts-expect-error state is one of the contract's
  await client.call('listResources', { path: { type: 'vm' }, query: { state: 'asleep' } });

  // a required body
  const accepted = await client.call('createResource', { path: { type: 'vm' }, body: { input: { size: 'small' } }, idempotencyKey: 'k-1' });
  const jobId: string = accepted.jobId;
  void jobId;
  // @ts-expect-error createResource needs its body
  await client.call('createResource', { path: { type: 'vm' } });

  // a 204 answers nothing
  const none: undefined = await client.call('deleteAlarm', { path: { alarmId: 'a-1' } });
  void none;

  // text
  const csv: string = await client.call('exportChargeback', { query: { month: '2026-09' } });
  void csv;
}

export async function paging(): Promise<void> {
  for await (const resource of client.paginate('listResources', { path: { type: 'vm' } })) {
    const wfrn: string = resource.wfrn;
    void wfrn;
  }
  for await (const page of client.pages('listJobs', { query: { status: 'running' } })) {
    const items: Schema<'JobSummary'>[] = page.items;
    void items;
  }
  // @ts-expect-error getJob does not page
  client.paginate('getJob', { path: { jobId: 'j-1' } });

  type Item = PageItem<'listFindings'>;
  const finding: Item | undefined = undefined;
  void finding;
}

export async function events(): Promise<void> {
  for await (const event of client.events('streamEvents', { query: { types: 'job' } })) {
    const type: string = event.event;
    void type;
  }
  // @ts-expect-error listJobs is not a stream
  client.events('listJobs');
}

export function errors(e: ConsoleApiError): Denial | undefined {
  const status: number = e.status;
  const code: string = e.error;
  const fields: (string | undefined)[] = e.problems.map((p) => p.field);
  void [status, code, fields, e.stepUp, e.requestable];
  if (e.denied) {
    const layer: 'console-iam' | 'platform' = e.denied.layer;
    const requestable: boolean = e.denied.requestable;
    void [layer, requestable];
  }
  return e.denied;
}

export type SessionAnswer = ResponseData<'getSession'>;
