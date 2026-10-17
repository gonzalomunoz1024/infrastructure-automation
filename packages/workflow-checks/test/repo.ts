/** The repository's own content as the catalog, the way SampleContentTest.java builds it. */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parse as parseYaml } from 'yaml';
import { scan, type ContentFile } from '../cli.ts';
import { buildContext, parseDocument, parseProvider, parseResourceType, resourceTypeName, type CheckContext, type ProviderDocument } from '../src/index.ts';

export const REPO_ROOT = fileURLToPath(new URL('../../../', import.meta.url));
export const FIXTURES = fileURLToPath(new URL('../fixtures/', import.meta.url));

export function readYaml(path: string): unknown {
  return parseYaml(readFileSync(path, 'utf8'), { uniqueKeys: false }) ?? {};
}

export function contentFiles(): ContentFile[] {
  return scan(REPO_ROOT);
}

export function ofKind(kind: string): ContentFile[] {
  return contentFiles().filter((f) => f.kind === kind);
}

/** Providers, published workflow names and resource types from workflows/, providers/ and resource-types/. */
export function repoContext(): CheckContext {
  const providers: ProviderDocument[] = [];
  const workflows: string[] = [];
  const resourceTypes: string[] = [];
  for (const f of contentFiles()) {
    switch (f.kind) {
      case 'Provider':
        providers.push(parseProvider(f.doc));
        break;
      case 'Workflow':
        workflows.push(parseDocument(f.doc).metadata.name);
        break;
      case 'ResourceType':
        resourceTypes.push(resourceTypeName(parseResourceType(f.doc)));
        break;
      default:
        break;
    }
  }
  return buildContext(providers, workflows, resourceTypes);
}

export function fixture(name: string): string {
  return readFileSync(join(FIXTURES, name), 'utf8');
}
