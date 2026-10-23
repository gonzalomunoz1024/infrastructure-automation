// @iaf/console-cli-core: the console CLI's command model, without Node: the CLI runs it in a
// terminal, and the console shell runs it in the browser with the page's own session.

export { execute, errorLines, findCommand, GLOBAL_FLAGS, GROUPS, type ExecuteOptions, type ExecuteResult } from './execute.js';
export { isFlag, parseArgs, suggest, UsageError, type FlagDef, type FlagType, type FlagValue, type ParsedArgs } from './args.js';
export { formatData, OUTPUT_FORMATS, toJson, toTable, toYaml, yamlScalar, type OutputFormat } from './format.js';
export { commandHelp, GLOBAL_FLAG_HELP, groupHelp, topHelp, usage, wrap, type HelpContext, type HelpEntry } from './help.js';
export type { CommandSpec, FlagSpec, GroupSpec } from './spec.js';
