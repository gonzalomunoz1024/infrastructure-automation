/**
 * What the checks need from `kind: Provider` and `kind: ResourceType` documents: the action
 * list with the effect and approval defaults ProviderYaml.java applies, and the structural
 * validation of those files for the CLI.
 */
import { asText, asTextOr, elements, get, has, isBlank, isObject, text } from './json.ts';
import type { ProviderActionDocument, ProviderDocument, ResourceTypeDocument } from './types.ts';

export const PROVIDER_KIND = 'Provider';
export const RESOURCE_TYPE_KIND = 'ResourceType';
export const PROVIDER_TYPES: readonly string[] = ['openapi', 'http', 'ansible', 'terraform', 'idp', 'sdk'];

/** What an action does to the world. Drives approval defaults, previews and retry safety. */
export type Effect = 'read' | 'create' | 'change' | 'delete' | 'none' | 'unknown';

export function effectOf(s: string | null | undefined): Effect {
  if (s === null || s === undefined) {
    return 'unknown';
  }
  switch (s.toLowerCase()) {
    case 'read':
    case 'get':
    case 'list':
      return 'read';
    case 'create':
    case 'post':
      return 'create';
    case 'change':
    case 'update':
    case 'put':
    case 'patch':
      return 'change';
    case 'delete':
    case 'destroy':
      return 'delete';
    case 'none':
      return 'none';
    default:
      return 'unknown';
  }
}

/** The default effect of an HTTP method, used when a spec or a user says nothing. */
export function effectOfHttpMethod(method: string | null | undefined): Effect {
  switch ((method ?? '').toUpperCase()) {
    case 'GET':
    case 'HEAD':
    case 'OPTIONS':
      return 'read';
    case 'POST':
      return 'create';
    case 'PUT':
    case 'PATCH':
      return 'change';
    case 'DELETE':
      return 'delete';
    default:
      return 'unknown';
  }
}

export function isDestructive(effect: Effect): boolean {
  return effect === 'delete';
}

/** The HTTP method of an action: from `operation: { method }` or a plain `method`. */
export function actionMethod(a: ProviderActionDocument): string | null {
  const op = get(a, 'operation');
  return isObject(op) ? text(op, 'method') : text(a, 'method');
}

/** The action's effect: as declared, else from its HTTP method, else unknown. */
export function actionEffect(a: ProviderActionDocument): Effect {
  if (has(a, 'effect')) {
    return effectOf(asText(get(a, 'effect')));
  }
  const method = actionMethod(a);
  return method !== null ? effectOfHttpMethod(method) : 'unknown';
}

/** Whether the action needs an approval: as declared (`required`, not `none`/`false`), else when it is destructive. */
export function actionApprovalRequired(a: ProviderActionDocument): boolean {
  if (has(a, 'approval')) {
    const v = asText(get(a, 'approval'));
    return v !== 'none' && v !== 'false';
  }
  return isDestructive(actionEffect(a));
}

/** Whether the action may be retried: as declared (`safe`), else only reads. */
export function actionRetrySafe(a: ProviderActionDocument): boolean {
  if (has(a, 'retry')) {
    return asText(get(a, 'retry')) === 'safe';
  }
  return actionEffect(a) === 'read';
}

export function providerActions(p: ProviderDocument): ProviderActionDocument[] {
  const actions = get(get(p, 'spec'), 'actions');
  return elements(actions)
    .filter((a) => isObject(a))
    .map((a) => a as unknown as ProviderActionDocument);
}

export function actionIds(p: ProviderDocument): string[] {
  return providerActions(p).map((a) => asText(get(a, 'id')));
}

export function findAction(p: ProviderDocument, id: string): ProviderActionDocument | undefined {
  return providerActions(p).find((a) => asText(get(a, 'id')) === id);
}

export function providerName(p: ProviderDocument): string {
  return asText(get(get(p, 'metadata'), 'name'));
}

/** A content document that is not a provider or a resource type: the structural errors of the Java readers. */
export class ContentParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ContentParseError';
  }
}

/** Validate a provider document the way ProviderYaml.java does; the document itself is the result. */
export function parseProvider(doc: unknown): ProviderDocument {
  const root = doc;
  if (!isObject(root)) {
    throw new ContentParseError('A provider file is a YAML mapping');
  }
  const kind = asTextOr(get(root, 'kind'), null);
  if (kind !== null && kind !== PROVIDER_KIND) {
    throw new ContentParseError(`kind must be Provider, was ${kind}`);
  }
  const name = asTextOr(get(get(root, 'metadata'), 'name'), null);
  if (name === null || isBlank(name)) {
    throw new ContentParseError('metadata.name is required');
  }
  const spec = get(root, 'spec');
  if (!isObject(spec)) {
    throw new ContentParseError('spec is required');
  }
  const type = asTextOr(get(spec, 'type'), 'openapi');
  if (!PROVIDER_TYPES.includes(type.toLowerCase())) {
    throw new ContentParseError(`Unknown provider type '${type}'`);
  }
  for (const a of elements(get(spec, 'actions'))) {
    if (asTextOr(get(a, 'id'), null) === null) {
      throw new ContentParseError('Every action needs an id');
    }
  }
  return root as unknown as ProviderDocument;
}

/** Validate a resource type document the way ResourceTypeDefinition.java does; the document itself is the result. */
export function parseResourceType(doc: unknown): ResourceTypeDocument {
  const root = doc;
  if (!isObject(root)) {
    throw new ContentParseError('A resource type file is a YAML mapping');
  }
  const kind = asTextOr(get(root, 'kind'), null);
  if (kind !== null && kind !== RESOURCE_TYPE_KIND) {
    throw new ContentParseError(`kind must be ResourceType, was ${kind}`);
  }
  const name = asTextOr(get(get(root, 'metadata'), 'name'), null);
  if (name === null || isBlank(name)) {
    throw new ContentParseError('metadata.name is required');
  }
  return root as unknown as ResourceTypeDocument;
}

export function resourceTypeName(r: ResourceTypeDocument): string {
  return asText(get(get(r, 'metadata'), 'name'));
}
