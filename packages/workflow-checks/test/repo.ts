/** The repository's own content as the catalog, the way SampleContentTest.java builds it. */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { scan, type ContentFile } from '../cli.ts';
import {
  buildContext,
  contextSchemas,
  parseDocument,
  parseProvider,
  parseResourceType,
  parseYamlText,
  resourceTypeName,
  type CheckContext,
  type ParsedWorkflow,
  type ProviderDocument,
  type ResourceTypeDocument,
} from '../src/index.ts';

export const REPO_ROOT = fileURLToPath(new URL('../../../', import.meta.url));
export const FIXTURES = fileURLToPath(new URL('../fixtures/', import.meta.url));

export function readYaml(path: string): unknown {
  return parseYamlText(readFileSync(path, 'utf8'));
}

export function contentFiles(): ContentFile[] {
  return scan(REPO_ROOT);
}

export function ofKind(kind: string): ContentFile[] {
  return contentFiles().filter((f) => f.kind === kind);
}

/** Providers, every workflow as published and resource types with their schemas, from workflows/, providers/ and resource-types/. */
export function repoContext(): CheckContext {
  const providers: ProviderDocument[] = [];
  const workflows: ParsedWorkflow[] = [];
  const types: ResourceTypeDocument[] = [];
  for (const f of contentFiles()) {
    switch (f.kind) {
      case 'Provider':
        providers.push(parseProvider(f.doc));
        break;
      case 'Workflow':
        workflows.push(parseDocument(f.doc));
        break;
      case 'ResourceType':
        types.push(parseResourceType(f.doc));
        break;
      default:
        break;
    }
  }
  return buildContext(
    providers,
    workflows.map((w) => w.metadata.name),
    types.map((t) => resourceTypeName(t)),
    contextSchemas(workflows, types),
  );
}

export function fixture(name: string): string {
  return readFileSync(join(FIXTURES, name), 'utf8');
}
