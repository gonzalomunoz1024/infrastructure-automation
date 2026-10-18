/**
 * YAML text read the way WorkflowYaml.readTree reads it on the backend: a mapping that writes the
 * same key twice is a syntax error naming the key (keeping one of the two values silently would
 * judge a file other than the one written), and an empty text is an empty mapping. The checks,
 * the CLI CI runs and the tests all read YAML through here.
 */
import { isScalar, parseDocument, visit, type Document, type YAMLError } from 'yaml';

/** YAML text that does not read; the message is what both twins report as `yaml.invalid`. */
export class YamlSyntaxError extends Error {
  override name = 'YamlSyntaxError';
}

/** The plain values of a YAML text; throws {@link YamlSyntaxError} with `YAML syntax: ...`. */
export function parseYamlText(text: string): unknown {
  const doc = parseDocument(text);
  const error = doc.errors[0];
  if (error !== undefined) {
    throw new YamlSyntaxError(`YAML syntax: ${describe(doc, error)}`);
  }
  try {
    return doc.toJS() ?? {};
  } catch (e) {
    throw new YamlSyntaxError(`YAML syntax: ${firstLine(e)}`);
  }
}

/** A duplicate key in the words the Java side uses (`Duplicate field 'examples'`), with its place; any other error as the parser words it. */
function describe(doc: Document, error: YAMLError): string {
  if (error.code === 'DUPLICATE_KEY') {
    const key = keyAt(doc, error.pos[0]);
    const at = error.linePos?.[0];
    if (key !== null) {
      return `Duplicate field '${key}'${at === undefined ? '' : ` at line ${at.line}, column ${at.col}`}`;
    }
  }
  return firstLine(error);
}

/** The scalar key that starts at an offset of the text. */
function keyAt(doc: Document, offset: number): string | null {
  let key: string | null = null;
  visit(doc, {
    Pair(_, pair) {
      if (isScalar(pair.key) && pair.key.range?.[0] === offset) {
        key = String(pair.key.value);
        return visit.BREAK;
      }
      return undefined;
    },
  });
  return key;
}

/** The first line of an error's message (the parser adds the offending lines below it). */
export function firstLine(e: unknown): string {
  const message = e instanceof Error ? e.message : String(e);
  return message.split('\n')[0] ?? message;
}
