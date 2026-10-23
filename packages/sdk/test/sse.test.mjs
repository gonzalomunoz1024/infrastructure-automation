import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseServerSentEvents, retryAfterSeconds } from '@iaf/console-sdk';

function stream(...chunks) {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      for (const c of chunks) controller.enqueue(encoder.encode(c));
      controller.close();
    },
  });
}

async function collect(body) {
  const events = [];
  for await (const e of parseServerSentEvents(body)) events.push(e);
  return events;
}

test('events split across chunks, CRLF split between two chunks', async () => {
  const events = await collect(stream('id: 1\r', '\nevent: job\r\nda', 'ta: {"a":1}\r\n\r\n', 'data: two\n', 'data: lines\n\n'));
  assert.deepEqual(events, [
    { event: 'job', data: '{"a":1}', id: '1', retry: undefined },
    { event: 'message', data: 'two\nlines', id: '1', retry: undefined },
  ]);
});

test('comments, retry and a field without a colon', async () => {
  const events = await collect(stream(': keep-alive\n', 'retry: 2500\n', 'data\n', '\n'));
  assert.deepEqual(events, [{ event: 'message', data: '', id: undefined, retry: 2500 }]);
});

test('an event the stream ends before its blank line is dropped', async () => {
  assert.deepEqual(await collect(stream('data: done\n\n', 'data: cut')), [{ event: 'message', data: 'done', id: undefined, retry: undefined }]);
});

test('stopping early cancels the stream', async () => {
  let cancelled = false;
  const body = new ReadableStream({
    pull(controller) {
      controller.enqueue(new TextEncoder().encode('data: tick\n\n'));
    },
    cancel() {
      cancelled = true;
    },
  });
  for await (const e of parseServerSentEvents(body)) {
    assert.equal(e.data, 'tick');
    break;
  }
  assert.equal(cancelled, true);
});

test('Retry-After is seconds or a date', () => {
  const now = Date.parse('2026-10-23T12:00:00Z');
  assert.equal(retryAfterSeconds('7', now), 7);
  assert.equal(retryAfterSeconds('Fri, 23 Oct 2026 12:00:30 GMT', now), 30);
  assert.equal(retryAfterSeconds('Fri, 23 Oct 2026 11:00:00 GMT', now), 0);
  assert.equal(retryAfterSeconds(null, now), undefined);
  assert.equal(retryAfterSeconds('soon', now), undefined);
});
