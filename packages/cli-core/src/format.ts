export type OutputFormat = 'json' | 'table' | 'yaml';
export const OUTPUT_FORMATS: readonly OutputFormat[] = ['json', 'table', 'yaml'];

/** An answer as the format prints it, ending with a newline (empty for no answer). */
export function formatData(data: unknown, format: OutputFormat): string {
  if (data === undefined) return '';
  switch (format) {
    case 'json':
      return toJson(data);
    case 'yaml':
      return toYaml(data);
    case 'table':
      return toTable(data);
  }
}

export function toJson(data: unknown): string {
  return data === undefined ? '' : `${JSON.stringify(data, null, 2)}\n`;
}

// ---------------------------------------------------------------------------------------------
// YAML: block style, strings quoted (JSON style) whenever a plain scalar could read otherwise

export function toYaml(data: unknown): string {
  if (data === undefined) return '';
  const lines: string[] = [];
  writeYaml(data, 0, lines);
  return `${lines.join('\n')}\n`;
}

function writeYaml(value: unknown, indent: number, lines: string[]): void {
  const pad = ' '.repeat(indent);
  if (Array.isArray(value)) {
    if (value.length === 0) {
      lines.push(`${pad}[]`);
      return;
    }
    for (const item of value) {
      if (isNode(item)) {
        const inner: string[] = [];
        writeYaml(item, indent + 2, inner);
        lines.push(`${pad}- ${inner[0]!.slice(indent + 2)}`, ...inner.slice(1));
      } else {
        lines.push(`${pad}- ${yamlScalar(item)}`);
      }
    }
    return;
  }
  if (isRecord(value)) {
    const entries = Object.entries(value).filter(([, v]) => v !== undefined);
    if (entries.length === 0) {
      lines.push(`${pad}{}`);
      return;
    }
    for (const [k, v] of entries) {
      if (isNode(v)) {
        lines.push(`${pad}${yamlKey(k)}:`);
        writeYaml(v, indent + 2, lines);
      } else {
        lines.push(`${pad}${yamlKey(k)}: ${yamlScalar(v)}`);
      }
    }
    return;
  }
  lines.push(`${pad}${yamlScalar(value)}`);
}

/** A non-empty list or map: written on lines of its own. */
function isNode(value: unknown): boolean {
  return (Array.isArray(value) && value.length > 0) || (isRecord(value) && Object.values(value).some((v) => v !== undefined));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

const PLAIN = /^[A-Za-z0-9_./][A-Za-z0-9_./ @+=-]*$/;
const RESERVED = /^(?:true|false|yes|no|on|off|y|n|null|~|[-+]?(?:\.inf|\.nan)|[-+]?(?:\d[\d_]*)?(?:\.\d*)?(?:e[-+]?\d+)?|0x[0-9a-f]+|0o[0-7]+|\d{4}-\d\d-\d\d.*)$/i;

export function yamlScalar(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'boolean') return String(value);
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : value > 0 ? '.inf' : value < 0 ? '-.inf' : '.nan';
  if (Array.isArray(value)) return '[]';
  if (typeof value === 'object') return '{}';
  const s = String(value);
  if (s !== '' && PLAIN.test(s) && !RESERVED.test(s) && !s.endsWith(' ')) return s;
  return JSON.stringify(s);
}

function yamlKey(key: string): string {
  return key !== '' && PLAIN.test(key) && !RESERVED.test(key) && !key.endsWith(' ') ? key : JSON.stringify(key);
}

// ---------------------------------------------------------------------------------------------
// Tables: a list as rows and columns, anything else as a key and value per line

export function toTable(data: unknown): string {
  if (data === undefined || data === null) return '';
  if (typeof data !== 'object') return `${String(data)}\n`;
  if (Array.isArray(data)) return rowsTable(data);
  const record = data as Record<string, unknown>;
  if (Array.isArray(record.items)) {
    const footer: string[] = [];
    for (const [k, v] of Object.entries(record)) {
      if (k === 'items' || k === 'next' || v === undefined) continue;
      footer.push(`${k}: ${cell(v, 60)}`);
    }
    if (typeof record.next === 'string' && record.next !== '') {
      footer.push(`more: --after ${record.next} for the next page, or --all for every page`);
    }
    return rowsTable(record.items) + footer.map((l) => `${l}\n`).join('');
  }
  return keyValueTable(record);
}

function rowsTable(rows: readonly unknown[]): string {
  if (rows.length === 0) return '(none)\n';
  if (!rows.every(isRecord)) return rows.map((r) => `${cell(r, 120)}\n`).join('');
  const columns: string[] = [];
  for (const row of rows as Record<string, unknown>[]) {
    for (const k of Object.keys(row)) if (!columns.includes(k)) columns.push(k);
  }
  const header = columns.map(columnTitle);
  const body = (rows as Record<string, unknown>[]).map((row) => columns.map((c) => cell(row[c], 48)));
  return align([header, ...body]);
}

function keyValueTable(record: Record<string, unknown>): string {
  const rows: string[][] = [];
  const walk = (value: unknown, prefix: string) => {
    if (isRecord(value) && Object.keys(value).length > 0) {
      for (const [k, v] of Object.entries(value)) walk(v, prefix ? `${prefix}.${k}` : k);
    } else if (value !== undefined) {
      rows.push([prefix, cell(value, 100)]);
    }
  };
  walk(record, '');
  if (rows.length === 0) return '(empty)\n';
  return align(rows);
}

function columnTitle(key: string): string {
  return key.replace(/([a-z0-9])([A-Z])/g, '$1_$2').replace(/[^A-Za-z0-9]+/g, '_').toUpperCase();
}

function cell(value: unknown, width: number): string {
  let s: string;
  if (value === undefined || value === null) s = '';
  else if (typeof value === 'string') s = value;
  else if (Array.isArray(value) && value.every((v) => v === null || typeof v !== 'object')) s = value.map((v) => String(v)).join(', ');
  else if (typeof value === 'object') s = JSON.stringify(value);
  else s = String(value);
  s = s.replace(/[\r\n\t]+/g, ' ');
  return s.length > width ? `${s.slice(0, width - 1)}…` : s;
}

function align(rows: readonly string[][]): string {
  const widths: number[] = [];
  for (const row of rows) row.forEach((c, i) => (widths[i] = Math.max(widths[i] ?? 0, c.length)));
  return rows
    .map((row) =>
      row
        .map((c, i) => (i === row.length - 1 ? c : c.padEnd(widths[i]!)))
        .join('  ')
        .trimEnd(),
    )
    .map((line) => `${line}\n`)
    .join('');
}
