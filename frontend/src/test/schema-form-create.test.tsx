import { describe, expect, it } from 'vitest'
import { renderToString } from 'react-dom/server'
import type { Problem, Schema } from '../lib/types'
import { fieldRoot, problemsFor, unfieldedProblems } from '../components/ProblemList'
import { SchemaForm, createFormOf, isCreateRequest, isComplete, splitProblems, validateAgainst } from '../components/SchemaForm'

const text = (html: string) => html.replace(/<!--.*?-->/g, '')

/* The input of a workflow whose `image` takes an id or `{ create }`, as the catalog describes it. */
const BUILD_IMAGE_INPUT: Schema = {
  type: 'object',
  required: ['os'],
  properties: {
    os: { type: 'string', title: 'Operating system', enum: ['ubuntu-2404', 'rhel-9'] },
    hardening: { type: 'string', title: 'Hardening', default: 'cis1' },
  },
}
const CREATE_VM_INPUT: Schema = {
  type: 'object',
  required: ['name', 'image'],
  properties: {
    name: { type: 'string', title: 'Name' },
    image: {
      title: 'Image',
      description: 'The image the VM boots from',
      'x-resource': { type: 'image', orCreateWith: 'build-vm-image' },
      anyOf: [
        { type: 'string', pattern: '^img-', description: 'An existing image id from the registry' },
        { type: 'object', description: 'Build a new image with build-vm-image first; the job continues with its id', required: ['create'], additionalProperties: false, properties: { create: BUILD_IMAGE_INPUT } },
      ],
    },
    cpu: { type: 'integer', default: 2 },
  },
}

describe('an input with two forms', () => {
  it('is recognised from anyOf, with the producer and both branches', () => {
    const form = createFormOf(CREATE_VM_INPUT.properties!.image)
    expect(form).not.toBeNull()
    expect(form!.type).toBe('image')
    expect(form!.producer).toBe('build-vm-image')
    expect(form!.idSchema.pattern).toBe('^img-')
    expect(form!.createSchema).toBe(BUILD_IMAGE_INPUT)
    expect(form!.description).toContain('Build a new image with build-vm-image first')
    expect(createFormOf(CREATE_VM_INPUT.properties!.name)).toBeNull()
    expect(createFormOf({ 'x-resource': { type: 'vm', orCreateWith: 'create-vm' } })).toBeNull()
    expect(isCreateRequest({ create: { os: 'rhel-9' } })).toBe(true)
    expect(isCreateRequest('img-77')).toBe(false)
  })

  it('offers "Use an existing image" and "Build one first", as the catalog describes', () => {
    const html = text(renderToString(<SchemaForm schema={CREATE_VM_INPUT} value={{ name: 'web-01' }} onChange={() => {}} idPrefix="run" />))
    expect(html).toContain('Use an existing image')
    expect(html).toContain('Build one first')
    // the id form is the registry picker; the build form is not open
    expect(html).toContain('aria-label="Image id"')
    expect(html).not.toContain('id="run-image-create-os"')
  })

  it('expands the producer\'s input inline for a build request', () => {
    const html = text(renderToString(<SchemaForm schema={CREATE_VM_INPUT} value={{ name: 'web-01', image: { create: { hardening: 'cis1' } } }} onChange={() => {}} idPrefix="run" />))
    expect(html).toContain('Build a new image with build-vm-image first; the job continues with its id')
    expect(html).toContain('aria-label="Operating system"')
    expect(html).toContain('id="run-image-create-hardening"')
    expect(html).toContain('value="cis1"')
    expect(html).not.toContain('aria-label="Image id"')
  })

  it('shows a 400 problem at image.create.os under that nested field', () => {
    const problems: Problem[] = [
      { level: 'error', code: 'enum', field: 'image.create.os', message: 'os must be one of ubuntu-2404, rhel-9' },
      { level: 'error', code: 'required', field: 'input.name', message: 'name is required' },
      { level: 'error', code: 'policy', message: 'prod needs a ticket' },
    ]
    const { fielded, general } = splitProblems(problems, CREATE_VM_INPUT)
    expect(fielded.map((p) => p.field)).toEqual(['image.create.os', 'input.name'])
    expect(general.map((p) => p.code)).toEqual(['policy'])
    expect(fieldRoot('image.create.os')).toBe('image')
    expect(problemsFor(problems, 'image').map((p) => p.code)).toEqual(['enum'])
    expect(unfieldedProblems(problems, ['name', 'image']).map((p) => p.code)).toEqual(['policy'])

    const html = text(renderToString(<SchemaForm schema={CREATE_VM_INPUT} value={{ image: { create: { os: 'windows' } } }} onChange={() => {}} problems={fielded} idPrefix="run" />))
    expect(html).toContain('os must be one of ubuntu-2404, rhel-9')
    expect(html).toContain('name is required')
    // the nested field is the one marked, not the outer one
    const outer = html.slice(html.indexOf('Use an existing image'), html.indexOf('aria-label="Operating system"'))
    expect(outer).not.toContain('os must be one of')
    const inner = html.slice(html.indexOf('aria-label="Operating system"'))
    expect(inner).toContain('os must be one of ubuntu-2404, rhel-9')
    // the only text input with a problem is the name
    expect(html.match(/aria-invalid="true"/g)).toHaveLength(1)
  })

  it('shows a problem on the request itself under the image field', () => {
    const problems: Problem[] = [{ level: 'error', code: 'type', field: 'image.create', message: 'create is the input of build-vm-image, an object' }]
    const html = text(renderToString(<SchemaForm schema={CREATE_VM_INPUT} value={{ image: { create: {} } }} onChange={() => {}} problems={problems} idPrefix="run" />))
    expect(html).toContain('create is the input of build-vm-image, an object')
  })

  it('validates a build request against the producer\'s input and an id against the id branch', () => {
    expect(validateAgainst(CREATE_VM_INPUT, { name: 'web-01', image: { create: { hardening: 'cis1' } } }).map((p) => `${p.code}:${p.field}`)).toEqual(['required:image.create.os'])
    expect(validateAgainst(CREATE_VM_INPUT, { name: 'web-01', image: { create: { os: 'rhel-9' } } })).toEqual([])
    expect(validateAgainst(CREATE_VM_INPUT, { name: 'web-01', image: 'vm-1' }).map((p) => p.code)).toEqual(['pattern'])
    expect(validateAgainst(CREATE_VM_INPUT, { name: 'web-01', image: 'img-77' })).toEqual([])
    expect(isComplete(CREATE_VM_INPUT, { name: 'web-01', image: { create: {} } })).toBe(true)
  })
})
