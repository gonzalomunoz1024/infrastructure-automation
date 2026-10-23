import { describe, expect, it } from 'vitest'
import type { Schema } from '@iaf/console-kit'
import { slugOf, stepOfProblems, stepSchema, stepsFor, withTypeHints } from './steps'

/* The wizard's steps come from the create workflow's input schema: a type's author adds groups, the wizard grows. */

const SCHEMA: Schema = {
  type: 'object',
  required: ['name', 'size', 'subnet'],
  properties: {
    name: { type: 'string', title: 'VM name' },
    size: { type: 'string', title: 'Size', enum: ['small', 'large'] },
    subnet: { type: 'string', title: 'Subnet', 'x-console': { group: 'Networking' } },
    backups: { type: 'boolean', title: 'Backups', 'x-console': { group: 'Backup & recovery' } },
    publicIp: { type: 'boolean', title: 'Public IP', 'x-console': { group: 'Networking' } },
  },
}

describe('the wizard’s steps', () => {
  const steps = stepsFor(SCHEMA, 'virtual machine')

  it('opens with the ungrouped fields, gives each group a step in schema order, and ends with the review', () => {
    expect(steps.map((s) => s.id)).toEqual(['details', 'networking', 'backup-recovery', 'review'])
    expect(steps.map((s) => s.label)).toEqual(['Virtual machine details', 'Networking', 'Backup & recovery', 'Review and create'])
    expect(steps[1].fields).toEqual(['subnet', 'publicIp'])
    expect(steps.map((s) => s.kind)).toEqual(['fields', 'fields', 'fields', 'review'])
  })

  it('adds an optional Tags step before the review only when asked', () => {
    expect(steps.some((s) => s.kind === 'tags')).toBe(false)
    const tagged = stepsFor(SCHEMA, 'virtual machine', { tags: true })
    expect(tagged.slice(-2).map((s) => s.id)).toEqual(['tags', 'review'])
    expect(tagged[tagged.length - 2]).toMatchObject({ optional: true, description: 'Optional' })
    // the account's required tags make the step one the create cannot skip, and say how many
    const required = stepsFor(SCHEMA, 'virtual machine', { tags: true, requiredTags: 4 })
    expect(required[required.length - 2]).toMatchObject({ id: 'tags', description: '4 required' })
    expect(required[required.length - 2].optional).toBeUndefined()
  })

  it('sends a problem on the create’s tags to the Tags step, and one on an input field to that field’s step', () => {
    const tagged = stepsFor(SCHEMA, 'virtual machine', { tags: true, requiredTags: 4 })
    expect(stepOfProblems(tagged, [{ level: 'error', code: 'tags.required', field: '/tags/cost-centre', message: 'Cost centre is required' }])).toBe(tagged.findIndex((s) => s.id === 'tags'))
    expect(stepOfProblems(tagged, [{ level: 'error', code: 'pattern', field: '/input/subnet', message: 'bad' }])).toBe(1)
    expect(stepOfProblems(steps, [{ level: 'error', code: 'tags.required', field: '/tags/owner', message: 'Owner is required' }])).toBe(-1)
  })

  it('keeps one details step when the workflow asks for nothing', () => {
    expect(stepsFor({ type: 'object' }, 'image').map((s) => s.id)).toEqual(['details', 'review'])
    expect(stepsFor(undefined, 'image')[0]).toMatchObject({ label: 'Image details', fields: [] })
  })

  it('cuts the schema down to one step, with the step’s required fields', () => {
    expect(stepSchema(SCHEMA, steps[0])).toEqual({ type: 'object', properties: { name: SCHEMA.properties!.name, size: SCHEMA.properties!.size }, required: ['name', 'size'] })
    expect(stepSchema(SCHEMA, steps[1]).required).toEqual(['subnet'])
    expect(stepSchema(SCHEMA, steps[2]).required).toEqual([])
  })

  it('finds the step whose field a problem names, inside nested fields too', () => {
    expect(stepOfProblems(steps, [{ level: 'error', code: 'required', field: '/subnet', message: 'required' }])).toBe(1)
    expect(stepOfProblems(steps, [{ level: 'error', code: 'pattern', field: '/input/name', message: 'pattern' }])).toBe(0)
    expect(stepOfProblems(steps, [{ level: 'error', code: 'quota.exceeded', message: 'no field' }, { level: 'error', code: 'type', field: '/backups', message: 'type' }])).toBe(2)
    expect(stepOfProblems(steps, [{ level: 'error', code: 'quota.exceeded', message: 'no field' }])).toBe(-1)
  })

  it('slugs a label for the step’s id', () => {
    expect(slugOf('Backup & recovery!')).toBe('backup-recovery')
    expect(slugOf('***')).toBe('step')
  })
})

describe('the type’s hints on a create', () => {
  const input: Schema = { type: 'object', properties: { name: { type: 'string' }, image: { type: 'string' }, size: { type: 'string', 'x-console': { group: 'Sizing' } }, password: { type: 'string' } } }
  const typeSchema: Schema = {
    type: 'object',
    properties: {
      image: { type: 'string', 'x-console': { group: 'Boot', widget: 'picker', help: 'https://wiki.example.test/images' } },
      size: { type: 'string', 'x-console': { group: 'Hardware' } },
      password: { type: 'string', 'x-console': { sensitive: true } },
    },
  }

  it('groups a field the way the type groups it, unless the input says otherwise', () => {
    const hinted = withTypeHints(input, typeSchema)
    expect(hinted.properties!.image['x-console']).toEqual({ group: 'Boot', help: 'https://wiki.example.test/images' })
    expect(hinted.properties!.size['x-console']).toEqual({ group: 'Sizing' })
    expect(hinted.properties!.password['x-console']).toEqual({ sensitive: true })
    expect(stepsFor(hinted, 'virtual machine').map((s) => s.id)).toEqual(['details', 'boot', 'sizing', 'review'])
  })

  it('leaves an input the type has nothing to add to as it was', () => {
    expect(withTypeHints(input, undefined)).toBe(input)
    expect(withTypeHints(input, { type: 'object', properties: { other: { type: 'string', 'x-console': { group: 'X' } } } })).toBe(input)
  })
})
