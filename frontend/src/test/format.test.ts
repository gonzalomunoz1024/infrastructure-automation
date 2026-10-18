import { describe, expect, it } from 'vitest'
import { bytes, dayLabel, duration, formatNumber, isoDate, percent, plural, relativeTime, shortDate, truncateMiddle } from '@iaf/ui'

const NOW = Date.UTC(2026, 9, 17, 14, 5, 0) // 2026-10-17T14:05:00Z

describe('relativeTime', () => {
  it('says just now under 45 seconds', () => {
    expect(relativeTime(NOW - 10_000, NOW)).toBe('just now')
  })
  it('counts minutes, hours and days', () => {
    expect(relativeTime(NOW - 3 * 60_000, NOW)).toBe('3m ago')
    expect(relativeTime(NOW - 2 * 3_600_000, NOW)).toBe('2h ago')
    expect(relativeTime(NOW - 5 * 86_400_000, NOW)).toBe('5d ago')
    expect(relativeTime(NOW - 14 * 86_400_000, NOW)).toBe('2w ago')
  })
  it('handles the future', () => {
    expect(relativeTime(NOW + 10 * 60_000, NOW)).toBe('in 10m')
  })
  it('accepts ISO strings', () => {
    expect(relativeTime(new Date(NOW - 60_000).toISOString(), NOW)).toBe('1m ago')
  })
  it('falls back to a date after a month', () => {
    expect(relativeTime(NOW - 60 * 86_400_000, NOW)).toMatch(/Aug/)
  })
})

describe('duration', () => {
  it('formats milliseconds, seconds, minutes, hours and days', () => {
    expect(duration(340)).toBe('340ms')
    expect(duration(1_234)).toBe('1.2s')
    expect(duration(45_000)).toBe('45s')
    expect(duration(72_000)).toBe('1m 12s')
    expect(duration(119_900)).toBe('2m')
    expect(duration(3_600_000 * 2 + 5 * 60_000)).toBe('2h 5m')
    expect(duration(86_400_000 * 3 + 3_600_000 * 4)).toBe('3d 4h')
  })
  it('is defensive', () => {
    expect(duration(undefined)).toBe('—')
    expect(duration(-5)).toBe('0ms')
  })
})

describe('formatNumber / percent / bytes', () => {
  it('groups digits and limits fractions', () => {
    expect(formatNumber(12345)).toBe('12,345')
    expect(formatNumber(0.4567)).toBe('0.46')
    expect(formatNumber(1234, { compact: true })).toBe('1.2K')
    expect(formatNumber(null)).toBe('—')
  })
  it('formats percentages', () => {
    expect(percent(99.5)).toBe('99.5%')
    expect(percent(100)).toBe('100%')
    expect(percent(0.995, { ratio: true })).toBe('99.5%')
    expect(percent(1 / 3, { ratio: true, digits: 2 })).toBe('33.33%')
  })
  it('formats bytes in binary units', () => {
    expect(bytes(512)).toBe('512 B')
    expect(bytes(1536)).toBe('1.5 KB')
    expect(bytes(5 * 1024 * 1024)).toBe('5 MB')
  })
})

describe('shortDate', () => {
  it('shows the time today, the day this year and the year otherwise', () => {
    expect(shortDate(NOW - 3_600_000, { now: NOW, timeZone: 'UTC' })).toBe('13:05')
    expect(shortDate(Date.UTC(2026, 2, 3, 9, 30), { now: NOW, timeZone: 'UTC' })).toBe('3 Mar, 09:30')
    expect(shortDate(Date.UTC(2025, 11, 24, 9, 30), { now: NOW, timeZone: 'UTC' })).toBe('24 Dec 2025')
  })
  it('gives a dash for garbage', () => {
    expect(shortDate('not a date')).toBe('—')
  })
})

describe('small helpers', () => {
  it('dayLabel names the day, with the year only when it is not this one', () => {
    expect(dayLabel('2026-10-16T10:00:00Z', { now: NOW, timeZone: 'UTC' })).toBe('16 Oct')
    expect(dayLabel('2025-10-16T10:00:00Z', { now: NOW, timeZone: 'UTC' })).toBe('16 Oct 2025')
    expect(dayLabel('nope')).toBe('—')
  })
  it('isoDate', () => {
    expect(isoDate(NOW)).toBe('2026-10-17')
  })
  it('truncateMiddle keeps both ends', () => {
    expect(truncateMiddle('job_01HXK2M9Q7ZC9F2C', 12)).toBe('job_01…C9F2C')
    expect(truncateMiddle('short', 12)).toBe('short')
  })
  it('plural', () => {
    expect(plural(1, 'job')).toBe('1 job')
    expect(plural(3, 'job')).toBe('3 jobs')
    expect(plural(2, 'entry', 'entries')).toBe('2 entries')
  })
})
