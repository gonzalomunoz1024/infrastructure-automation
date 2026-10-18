/**
 * The `api.deprecated` block and `/api/v<n>/` paths, judged the way the Java checks judge them
 * (backend WorkflowDeprecationChecksTest holds the same cases).
 */
import { describe, expect, it } from 'vitest';
import { checkYaml } from '../src/index.ts';
import { repoContext } from './repo.ts';

const ctx = repoContext();

function codes(path: string, deprecated: string | null): string[] {
  const yaml = `apiVersion: iaf.platform/v1
kind: Workflow
metadata: { name: vm-info, title: VM info, noun: vm, verb: read, owner: team-compute, version: 1.0.0 }
policy: { rbac: [ops] }
api:
  path: ${path}
  input: { type: object, required: [vmId], properties: { vmId: { type: string } } }
${deprecated === null ? '' : `  deprecated: ${deprecated}`}
workflow:
  do:
    - getVm:
        call: provider
        with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.vmId }}" } }
`;
  return checkYaml(yaml, ctx)
    .map((p) => p.code)
    .sort();
}

describe('api.deprecated', () => {
  it('a complete block passes', () => {
    expect(codes('/api/v1/vms/{vmId}:info', '{ since: 2026-11-01, sunset: 2027-05-01, replacement: "/api/v2/vms/{vmId}:info" }')).toEqual([]);
    expect(codes('/api/v1/vms/{vmId}:info', '{ since: "2026-11-01T09:00:00Z", sunset: "2027-05-01T00:00:00+02:00", message: Use vm-info-v2 }')).toEqual([]);
    expect(codes('/api/v1/vms/{vmId}:info', '{ since: 2026-11-01, sunset: 2027-05-01, replacement: "https://docs.example.com/vm-info" }')).toEqual([]);
    expect(codes('/api/v1/vms/{vmId}:info', null)).toEqual([]);
  });

  it('both dates are required and the sunset comes after', () => {
    expect(codes('/api/v1/vms/{vmId}:info', '{ sunset: 2027-05-01 }')).toEqual(['deprecated.since']);
    expect(codes('/api/v1/vms/{vmId}:info', '{ since: 2026-11-01 }')).toEqual(['deprecated.sunset']);
    expect(codes('/api/v1/vms/{vmId}:info', '{ since: soon, sunset: 2027-02-30 }')).toEqual(['deprecated.since', 'deprecated.sunset']);
    expect(codes('/api/v1/vms/{vmId}:info', '{ since: 2027-05-01, sunset: 2027-05-01 }')).toEqual(['deprecated.sunset']);
    expect(codes('/api/v1/vms/{vmId}:info', 'true')).toEqual(['deprecated.since', 'deprecated.sunset']);
  });

  it('the replacement is another path or a URL', () => {
    expect(codes('/api/v1/vms/{vmId}:info', '{ since: 2026-11-01, sunset: 2027-05-01, replacement: vm-info-v2 }')).toEqual(['deprecated.replacement']);
    expect(codes('/api/v1/vms/{vmId}:info', '{ since: 2026-11-01, sunset: 2027-05-01, replacement: "/api/v1/vms/{vmId}:info" }')).toEqual([
      'deprecated.replacement',
    ]);
  });

  it('generated paths live under any API version and platform nouns stay reserved', () => {
    expect(codes('/api/v2/vms/{vmId}:info', null)).toEqual([]);
    expect(codes('/api/v12/vms/{vmId}:info', null)).toEqual([]);
    expect(codes('/api/vms/{vmId}:info', null)).toEqual(['api.path']);
    expect(codes('/api/v0/vms/{vmId}:info', null)).toEqual(['api.path']);
    expect(codes('/api/v2/jobs/{vmId}', null)).toEqual(['api.reservedPath']);
    expect(codes('/api/v1/notifications/{vmId}', null)).toEqual(['api.reservedPath']);
  });
});
