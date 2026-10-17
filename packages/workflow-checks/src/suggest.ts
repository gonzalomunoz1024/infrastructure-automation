/** "Did you mean": the closest candidate by edit distance, when it is close enough to be a typo. */

/** Levenshtein distance between two strings. */
export function levenshtein(a: string, b: string): number {
  let prev: number[] = new Array<number>(b.length + 1);
  let cur: number[] = new Array<number>(b.length + 1);
  for (let j = 0; j <= b.length; j++) {
    prev[j] = j;
  }
  for (let i = 1; i <= a.length; i++) {
    cur[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const cost = a.charCodeAt(i - 1) === b.charCodeAt(j - 1) ? 0 : 1;
      cur[j] = Math.min(cur[j - 1]! + 1, prev[j]! + 1, prev[j - 1]! + cost);
    }
    const t = prev;
    prev = cur;
    cur = t;
  }
  return prev[b.length]!;
}

/**
 * The closest candidate, case-insensitively, when its distance is at most max(2, len/3); the first
 * candidate wins a tie. Undefined when nothing is close enough.
 */
export function closest(wrong: string | null | undefined, candidates: Iterable<string> | null | undefined): string | undefined {
  if (wrong == null || candidates == null) {
    return undefined;
  }
  let best: string | undefined;
  let bestDistance = Number.MAX_SAFE_INTEGER;
  const lower = wrong.toLowerCase();
  for (const c of candidates) {
    const d = levenshtein(lower, c.toLowerCase());
    if (d < bestDistance) {
      bestDistance = d;
      best = c;
    }
  }
  const allowed = Math.max(2, Math.floor(wrong.length / 3));
  return bestDistance <= allowed ? best : undefined;
}
