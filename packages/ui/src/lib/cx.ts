/** Tiny class joiner — keeps runtime deps at zero (no clsx). */
export function cx(...parts: Array<string | false | null | undefined | 0>): string {
  return parts.filter(Boolean).join(' ')
}
