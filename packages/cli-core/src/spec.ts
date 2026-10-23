/** A group of commands: one tag of the contract (see generated/commands.ts). */
export interface GroupSpec {
  /** The tag in kebab case: `access-requests`. */
  readonly name: string;
  /** The contract's tag: `Access requests`. */
  readonly tag: string;
  readonly description?: string;
  readonly commands: readonly CommandSpec[];
}

/** One command: one operation of the contract. */
export interface CommandSpec {
  /** The operation id less the tag's noun, in kebab case: `list` for listResources. */
  readonly name: string;
  /** The command is the group's own (`console search --q …`): its operation id is the tag. */
  readonly groupDefault?: boolean;
  readonly operationId: string;
  readonly method: string;
  readonly path: string;
  readonly summary?: string;
  readonly description?: string;
  /** The path parameters in path order: they may be given as positional arguments too. */
  readonly positionals: readonly string[];
  readonly flags: readonly FlagSpec[];
  readonly body?: { readonly required: boolean; readonly contentType: string; readonly schema?: string };
  readonly paged?: boolean;
  readonly response: 'json' | 'text' | 'events' | 'none';
}

/** A path, query or header parameter, as a flag. */
export interface FlagSpec {
  /** The flag without its dashes: `job-id`. */
  readonly flag: string;
  /** The parameter's name in the contract: `jobId`. */
  readonly name: string;
  readonly in: 'path' | 'query' | 'header';
  readonly required?: boolean;
  readonly type: string;
  readonly array?: boolean;
  readonly enum?: readonly string[];
  readonly default?: unknown;
  /** For `tag.<name>`: the pattern a flag such as `--tag.env` must match. */
  readonly namePattern?: string;
  readonly description?: string;
}
