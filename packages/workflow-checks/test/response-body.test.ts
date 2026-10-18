/**
 * `api.response.body`, read as written and judged against the outputs the workflow sets, the way
 * the Java checks judge it (backend WorkflowResponseBodyTest holds the same cases).
 */
import { describe, expect, it } from 'vitest';
import { checkYaml, parseDocument } from '../src/index.ts';
import { repoContext } from './repo.ts';

const ctx = repoContext();
const OUTPUTS = `  outputs: { powerState: "{{ $('Get VM').json.powerState }}" }`;

function yaml(response: string | null, outputs: string | null): string {
  return `apiVersion: iaf.platform/v1
kind: Workflow
metadata: { name: vm-info, title: VM info, noun: vm, verb: read, owner: team-compute, version: 1.0.0 }
policy: { rbac: [ops] }
api:
  path: /api/v1/vms/{vmId}:info
  input: { type: object, required: [vmId], properties: { vmId: { type: string } } }
${response === null ? '' : `  response: ${response}`}
workflow:
  do:
    - getVm:
        name: Get VM
        call: provider
        with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.vmId }}" } }
${outputs ?? ''}
`;
}

function codes(response: string | null, outputs: string | null): string[] {
  return checkYaml(yaml(response, outputs), ctx)
    .map((p) => `${p.level} ${p.code}`)
    .sort();
}

describe('api.response.body', () => {
  it('is read as written', () => {
    const body = { type: 'object', required: ['powerState'], properties: { powerState: { type: 'string' } } };
    expect(parseDocument({ kind: 'Workflow', metadata: { name: 'x' }, api: { response: { status: 202, body } } }).api?.responseBody).toEqual(body);
    expect(parseDocument({ kind: 'Workflow', metadata: { name: 'x' }, api: { response: { status: 202 } } }).api?.responseBody).toBeUndefined();
    expect(parseDocument({ kind: 'Workflow', metadata: { name: 'x' }, api: {} }).api?.responseBody).toBeUndefined();
  });

  it('a body that describes the outputs passes', () => {
    expect(codes('{ status: 202, body: { type: object, properties: { powerState: { type: string } } } }', OUTPUTS)).toEqual([]);
    expect(codes('{ status: 202, body: { properties: { powerState: { type: string } } } }', OUTPUTS)).toEqual([]);
    expect(codes('{ status: 202, body: { type: object } }', null)).toEqual([]);
    expect(codes('{ status: 202 }', null)).toEqual([]);
  });

  it('the body is an object schema', () => {
    expect(codes('{ status: 202, body: a JSON object }', OUTPUTS)).toEqual(['error api.responseBody']);
    expect(codes('{ status: 202, body: { type: array, items: { type: string } } }', OUTPUTS)).toEqual(['error api.responseBody']);
    expect(codes('{ status: 202, body: [powerState] }', OUTPUTS)).toEqual(['error api.responseBody']);
    expect(codes('{ status: 202, body: null }', OUTPUTS)).toEqual(['error api.responseBody']);
  });

  it('a property the outputs never set is a warning', () => {
    const problems = checkYaml(yaml('{ status: 202, body: { type: object, properties: { powerState: { type: string }, host: { type: string } } } }', OUTPUTS), ctx);
    expect(problems.map((p) => p.code)).toEqual(['api.responseOutput']);
    expect(problems[0]?.field).toBe('api.response.body.host');
    expect(problems[0]?.message).toContain("'host'");
    expect(problems[0]?.message).toContain('powerState');
    expect(codes('{ status: 202, body: { type: object, properties: { powerState: { type: string } } } }', null)).toEqual(['warning api.responseOutput']);
  });
});
