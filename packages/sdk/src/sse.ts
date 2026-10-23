/** One server-sent event (`/console/v1/events`): `event:` the type, `id:` the cursor, `data:` the event. */
export interface ServerSentEvent {
  event: string;
  data: string;
  id?: string;
  retry?: number;
}

/** Reads server-sent events from a response body, as the HTML standard's parser does. */
export async function* parseServerSentEvents(body: ReadableStream<Uint8Array>): AsyncGenerator<ServerSentEvent> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let event = '';
  let data: string[] = [];
  let id: string | undefined;
  let retry: number | undefined;

  const field = (line: string): ServerSentEvent | undefined => {
    if (line === '') {
      const dispatched = data.length > 0 ? { event: event || 'message', data: data.join('\n'), id, retry } : undefined;
      event = '';
      data = [];
      retry = undefined;
      return dispatched;
    }
    if (line.startsWith(':')) return undefined;
    const colon = line.indexOf(':');
    const name = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (name === 'event') event = value;
    else if (name === 'data') data.push(value);
    else if (name === 'id' && !value.includes('\0')) id = value;
    else if (name === 'retry' && /^\d+$/.test(value)) retry = Number(value);
    return undefined;
  };

  let ended = false;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      ended = done;
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
      // a CR at the end may be the first half of a CRLF: keep it for the next chunk
      for (;;) {
        const match = /\r\n|\r|\n/.exec(buffer);
        if (!match || (!done && match[0] === '\r' && match.index === buffer.length - 1)) break;
        const line = buffer.slice(0, match.index);
        buffer = buffer.slice(match.index + match[0].length);
        const dispatched = field(line);
        if (dispatched) yield dispatched;
      }
      if (done) {
        // the standard drops an event that the stream ends before its blank line
        return;
      }
    }
  } finally {
    // the reader stopped early (a break, an error): close the connection
    if (!ended) await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}
