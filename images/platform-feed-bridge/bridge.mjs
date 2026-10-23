// platform-feed-bridge: the Kafka half of the platform's event feed (P-01) for platform-fake.
//
// The platform publishes every feed event twice: over HTTP (GET /api/v1/events/stream) and on
// Kafka (platform.jobs, platform.resources, platform.content, platform.audit; asyncapi-target.yaml
// on the hub). platform-fake-app serves only the HTTP half, while the state service's production
// profile (`kafka`) reads the Kafka half. This bridge follows the fake's stream with the
// console-internal service account and writes each envelope, unchanged, to its channel's topic with
// the channel's partition key. Consumers de-duplicate on the envelope's `key`, so a replay after a
// restart is harmless.
//
// Settings (environment): PLATFORM_URL, PLATFORM_TOKEN, KAFKA_BROKERS, HEALTH_PORT (8080).
import http from 'node:http'
import { Kafka, logLevel } from 'kafkajs'

const platformUrl = (process.env.PLATFORM_URL ?? 'http://platform-fake:18090').replace(/\/+$/, '')
const token = process.env.PLATFORM_TOKEN ?? ''
const brokers = (process.env.KAFKA_BROKERS ?? 'kafka:9092').split(',')
const healthPort = Number(process.env.HEALTH_PORT ?? 8080)

if (!token) {
  console.error('PLATFORM_TOKEN is empty: the feed needs the console-internal service account')
  process.exit(2)
}

const stats = { connected: false, lastSeq: 0, published: 0, skipped: 0, lastError: null, byTopic: {} }

/** The channel of an event type and its partition key, as asyncapi-target.yaml binds them. */
export function route(envelope) {
  const type = envelope.type ?? ''
  const ctx = envelope.context ?? {}
  const data = envelope.data ?? {}
  if (type.startsWith('job.') || type.startsWith('batch.')) {
    const jobId = envelope.aggregate === 'job' ? envelope.aggregateId : (data.jobId ?? ctx.jobId ?? envelope.aggregateId)
    return { topic: 'platform.jobs', key: jobId }
  }
  if (type === 'resource.registered' || type === 'resource.retired') {
    return { topic: 'platform.resources', key: ctx.wfrn ?? data.wfrn ?? data.resourceId ?? envelope.aggregateId }
  }
  if (type.startsWith('audit.')) {
    return { topic: 'platform.audit', key: envelope.aggregateId ?? data.recordId }
  }
  if (/^(workflow|provider|resource-type|region|content|release)\./.test(type)) {
    return { topic: 'platform.content', key: envelope.aggregateId ?? data.name }
  }
  return null
}

const kafka = new Kafka({ clientId: 'platform-feed-bridge', brokers, logLevel: logLevel.WARN, retry: { retries: 30, initialRetryTime: 500, maxRetryTime: 5000 } })
const producer = kafka.producer({ allowAutoTopicCreation: true })

async function publish(envelope, raw) {
  const target = route(envelope)
  if (!target) {
    stats.skipped++
    return
  }
  await producer.send({ topic: target.topic, acks: -1, messages: [{ key: target.key == null ? null : String(target.key), value: raw }] })
  stats.published++
  stats.byTopic[target.topic] = (stats.byTopic[target.topic] ?? 0) + 1
}

/** Reads one SSE connection to its end; resolves when the server closes it. */
async function follow(after) {
  const headers = { Accept: 'text/event-stream', Authorization: `Bearer ${token}` }
  if (after > 0) headers['Last-Event-ID'] = String(after)
  const res = await fetch(`${platformUrl}/api/v1/events/stream?after=${after}`, { headers })
  if (!res.ok || !res.body) throw new Error(`the feed answered ${res.status}`)
  stats.connected = true
  console.log(`following ${platformUrl}/api/v1/events/stream from ${after}`)
  const decoder = new TextDecoder()
  let buffer = ''
  let event = { id: null, data: [] }
  for await (const chunk of res.body) {
    buffer += decoder.decode(chunk, { stream: true })
    let nl
    while ((nl = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, nl).replace(/\r$/, '')
      buffer = buffer.slice(nl + 1)
      if (line === '') {
        if (event.data.length) {
          const raw = event.data.join('\n')
          const envelope = JSON.parse(raw)
          await publish(envelope, raw)
          const seq = Number(envelope.seq ?? event.id ?? 0)
          if (seq > stats.lastSeq) stats.lastSeq = seq
        }
        event = { id: null, data: [] }
      } else if (line.startsWith(':')) {
        // a keepalive comment
      } else {
        const colon = line.indexOf(':')
        const field = colon < 0 ? line : line.slice(0, colon)
        const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
        if (field === 'data') event.data.push(value)
        else if (field === 'id') event.id = value
      }
    }
  }
  stats.connected = false
}

http
  .createServer((req, res) => {
    const healthy = stats.connected
    res.writeHead(healthy ? 200 : 503, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ status: healthy ? 'UP' : 'DOWN', ...stats }))
  })
  .listen(healthPort)

await producer.connect()
console.log(`connected to Kafka at ${brokers.join(',')}`)
let delay = 500
for (;;) {
  try {
    await follow(stats.lastSeq)
    delay = 500
  } catch (e) {
    stats.connected = false
    stats.lastError = String(e?.message ?? e)
    console.warn(`the feed stopped (${stats.lastError}); again in ${delay} ms from ${stats.lastSeq}`)
    await new Promise((r) => setTimeout(r, delay))
    delay = Math.min(delay * 2, 5000)
  }
}
