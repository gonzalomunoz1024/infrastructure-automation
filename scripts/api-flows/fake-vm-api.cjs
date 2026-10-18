// A stand-in VM API for the onboarding journey (journey-vm-provider.cjs): an OpenAPI document and
// the four calls it describes, in two environments, behind a bearer token. Nothing is real but the
// HTTP: the platform imports the spec, probes each environment and calls the operations as it would
// call any vendor's API.
// Usage: node fake-vm-api.cjs [port]   (FAKE_VM_TOKEN sets the token it accepts)
const http = require('http')

const PORT = Number(process.argv[2] || process.env.FAKE_VM_PORT || 18090)
const TOKEN = process.env.FAKE_VM_TOKEN || 'journey-demo-token'
const ENVIRONMENTS = ['dev', 'prod']

const vm = {
  type: 'object',
  required: ['id', 'name', 'state'],
  properties: {
    id: { type: 'string' },
    name: { type: 'string' },
    state: { type: 'string', enum: ['provisioning', 'running', 'stopped'] },
    image: { type: 'string' },
    cpu: { type: 'integer' },
    memoryGb: { type: 'integer' },
    ip: { type: 'string' },
    createdAt: { type: 'string', format: 'date-time' },
  },
}
const id = { name: 'id', in: 'path', required: true, schema: { type: 'string' } }
const json = (schema) => ({ 'application/json': { schema } })
const notFound = { description: 'No such VM', content: json({ type: 'object', properties: { error: { type: 'string' } } }) }

const spec = (host) => ({
  openapi: '3.0.3',
  info: { title: 'Acme Cloud', version: '1.4.0', description: 'Virtual machines in Acme Cloud.' },
  servers: ENVIRONMENTS.map((e) => ({ url: `http://${host}/${e}`, description: e })),
  components: { securitySchemes: { bearer: { type: 'http', scheme: 'bearer' } } },
  security: [{ bearer: [] }],
  paths: {
    '/vms': {
      get: { operationId: 'listVms', summary: 'List VMs', responses: { 200: { description: 'The VMs', content: json({ type: 'array', items: vm }) } } },
      post: {
        operationId: 'createVm',
        summary: 'Create a VM',
        requestBody: {
          required: true,
          content: json({
            type: 'object',
            required: ['name', 'image'],
            properties: {
              name: { type: 'string', pattern: '^[a-z][a-z0-9-]{2,30}$' },
              image: { type: 'string' },
              cpu: { type: 'integer', default: 2 },
              memoryGb: { type: 'integer', default: 8 },
            },
          }),
        },
        responses: { 201: { description: 'Created', content: json(vm) }, 409: { description: 'The name is taken' } },
      },
    },
    '/vms/{id}': {
      get: { operationId: 'getVm', summary: 'Get a VM', parameters: [id], responses: { 200: { description: 'The VM', content: json(vm) }, 404: notFound } },
      delete: { operationId: 'deleteVm', summary: 'Delete a VM', parameters: [id], responses: { 204: { description: 'Deleted' }, 404: notFound } },
    },
  },
})

const vms = Object.fromEntries(ENVIRONMENTS.map((e) => [e, new Map()]))
let next = 1041

function send(res, status, body) {
  res.writeHead(status, body === undefined ? {} : { 'Content-Type': 'application/json' })
  res.end(body === undefined ? undefined : JSON.stringify(body))
}

function read(req) {
  return new Promise((resolve) => {
    let data = ''
    req.on('data', (c) => (data += c))
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : {})
      } catch {
        resolve(null)
      }
    })
  })
}

http
  .createServer(async (req, res) => {
    const url = new URL(req.url, `http://${req.headers.host}`)
    console.log(req.method, url.pathname)
    if (url.pathname === '/openapi.json') return send(res, 200, spec(req.headers.host))
    const [, env, collection, vmId, ...rest] = url.pathname.split('/')
    if (!ENVIRONMENTS.includes(env) || rest.length) return send(res, 404, { error: 'not_found' })
    if (req.headers.authorization !== `Bearer ${TOKEN}`) return send(res, 401, { error: 'unauthorized' })
    const store = vms[env]
    if (!collection) return req.method === 'HEAD' ? send(res, 200) : send(res, 200, { service: 'acme-cloud', environment: env })
    if (collection !== 'vms') return send(res, 404, { error: 'not_found' })
    if (!vmId && req.method === 'GET') return send(res, 200, [...store.values()])
    if (!vmId && req.method === 'POST') {
      const body = await read(req)
      if (!body || typeof body.name !== 'string' || !/^[a-z][a-z0-9-]{2,30}$/.test(body.name) || typeof body.image !== 'string') {
        return send(res, 400, { error: 'invalid', message: 'name (lowercase, 3 to 31 characters) and image are required' })
      }
      if ([...store.values()].some((v) => v.name === body.name)) return send(res, 409, { error: 'conflict', message: `${body.name} exists` })
      const n = next++
      const made = {
        id: `vm-${n}`,
        name: body.name,
        state: 'running',
        image: body.image,
        cpu: Number(body.cpu) || 2,
        memoryGb: Number(body.memoryGb) || 8,
        ip: `10.${env === 'prod' ? 20 : 10}.0.${n % 250}`,
        createdAt: new Date().toISOString(),
      }
      store.set(made.id, made)
      return send(res, 201, made)
    }
    if (vmId && req.method === 'GET') return store.has(vmId) ? send(res, 200, store.get(vmId)) : send(res, 404, { error: 'not_found' })
    if (vmId && req.method === 'DELETE') return store.delete(vmId) ? send(res, 204) : send(res, 404, { error: 'not_found' })
    return send(res, 405, { error: 'method_not_allowed' })
  })
  .listen(PORT, () => console.log(`fake VM API on http://localhost:${PORT} (spec at /openapi.json)`))
