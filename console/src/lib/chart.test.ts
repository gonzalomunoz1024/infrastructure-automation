import { describe, expect, it } from 'vitest'
import { durationMs, isoDuration, linearScale, nearestIndex, nextIndex, niceStep, pathOf, pointLabel, runsOf, summarise, timeLabel, timeTicks, valueDomain, valueTicks } from './chart'
import { DEFAULT_RANGE, alarmCondition, alarmName, formatMetric, formatMoney, rangeOf, seriesTitle, sizeWords, speakMetric, unitOfMetric, windowOf } from './monitoring'

/* The Monitoring tab's pure parts: scales, ticks, the summary in words, the keyboard's steps, and the words for series, alarms and sizes. */

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const pts = (values: (number | null)[], step = MINUTE, t0 = 0) => values.map((v, i) => ({ t: t0 + i * step, v: v === null ? Number.NaN : v }))

describe('scales and ticks', () => {
  it('maps a domain onto a range and back', () => {
    const y = linearScale(0, 100, 200, 0)
    expect(y(0)).toBe(200)
    expect(y(50)).toBe(100)
    expect(y(100)).toBe(0)
    expect(y.invert(100)).toBe(50)
    // a flat domain does not divide by zero
    expect(Number.isFinite(linearScale(5, 5, 0, 10)(5))).toBe(true)
  })

  it('picks round steps and ticks that cover the values', () => {
    expect(niceStep(100, 4)).toBe(25)
    expect(niceStep(80, 4)).toBe(20)
    expect(niceStep(7, 4)).toBe(2)
    expect(niceStep(0, 4)).toBe(1)
    expect(valueTicks(0, 100)).toEqual([0, 25, 50, 75, 100])
    expect(valueTicks(0, 83)).toEqual([0, 25, 50, 75, 100])
    expect(valueTicks(0, 0.6)).toEqual([0, 0.2, 0.4, 0.6])
    expect(valueTicks(Number.NaN, 1)).toEqual([])
  })

  it('starts the value axis at zero, keeps a threshold on the plot and a percent axis at 100', () => {
    expect(valueDomain([10, 42], { unit: 'percent' })).toEqual({ min: 0, max: 60, ticks: [0, 20, 40, 60] })
    expect(valueDomain([10, 42], { unit: 'percent', include: [80] }).max).toBe(100)
    expect(valueDomain([96, 99], { unit: 'percent' })).toEqual({ min: 0, max: 100, ticks: [0, 25, 50, 75, 100] })
    expect(valueDomain([], { unit: 'percent' }).max).toBe(100)
    expect(valueDomain([120, 340]).max).toBe(400)
    expect(valueDomain([0, 0]).max).toBeGreaterThan(0)
  })

  it('ticks a time axis on round local times, at most as many as asked', () => {
    const from = Date.parse('2026-10-22T10:00:00Z')
    const day = timeTicks(from, from + 24 * HOUR, 5)
    expect(day.length).toBeLessThanOrEqual(5)
    expect(day.every((t) => t % (6 * HOUR) === 0)).toBe(true)
    const hour = timeTicks(from + 7 * MINUTE, from + HOUR + 7 * MINUTE, 5)
    expect(hour.map((t) => new Date(t).toISOString().slice(11, 16))).toEqual(['10:15', '10:30', '10:45', '11:00'])
    // two hours east of UTC: a day's ticks fall on local midnight, 22:00 UTC
    const week = timeTicks(from, from + 7 * 24 * HOUR, 8, 120)
    expect(week.every((t) => new Date(t).getUTCHours() === 22)).toBe(true)
    expect(timeTicks(from, from)).toEqual([])
  })

  it('labels a tick by the time of day for a day, by the date beyond', () => {
    const t = Date.parse('2026-10-23T14:05:00Z')
    expect(timeLabel(t, 24 * HOUR, 'UTC')).toBe('14:05')
    expect(timeLabel(t, 7 * 24 * HOUR, 'UTC')).toBe('Fri 23')
    expect(pointLabel(t, 'UTC')).toBe('Fri 23 Oct, 14:05')
  })
})

describe('reading a series', () => {
  it('summarises the latest, the average, the highest and the lowest, skipping gaps', () => {
    const s = summarise(pts([10, 30, null, 20]))
    expect(s.count).toBe(3)
    expect(s.latest).toEqual({ t: 3 * MINUTE, v: 20 })
    expect(s.average).toBe(20)
    expect(s.highest).toEqual({ t: MINUTE, v: 30 })
    expect(s.lowest).toEqual({ t: 0, v: 10 })
    expect(summarise([])).toEqual({ count: 0 })
    // the first of equal highs
    expect(summarise(pts([5, 9, 9])).highest?.t).toBe(MINUTE)
  })

  it('finds the point nearest a time', () => {
    const p = pts([1, 2, 3, 4])
    expect(nearestIndex(p, -10)).toBe(0)
    expect(nearestIndex(p, 1.4 * MINUTE)).toBe(1)
    expect(nearestIndex(p, 1.6 * MINUTE)).toBe(2)
    expect(nearestIndex(p, 1.5 * MINUTE)).toBe(1)
    expect(nearestIndex(p, 99 * MINUTE)).toBe(3)
    expect(nearestIndex([], 0)).toBe(-1)
  })

  it('breaks the line where points are missing', () => {
    const gappy = [...pts([1, 2]), ...pts([3, 4], MINUTE, 5 * MINUTE)]
    expect(runsOf(gappy, MINUTE).map((r) => r.length)).toEqual([2, 2])
    expect(runsOf(pts([1, null, 3, 4]), MINUTE).map((r) => r.length)).toEqual([1, 2])
    expect(runsOf(pts([1, 2, 3]), MINUTE)).toHaveLength(1)
    expect(pathOf(pts([1, 2]), (t) => t / MINUTE, (v) => v * 10)).toBe('M0.0 10.0 L1.0 20.0')
  })

  it('steps through the points from the keyboard', () => {
    expect(nextIndex('ArrowRight', 3, 10)).toBe(4)
    expect(nextIndex('ArrowLeft', 0, 10)).toBe(0)
    expect(nextIndex('ArrowUp', 9, 10)).toBe(9)
    expect(nextIndex('PageUp', 0, 100)).toBe(10)
    expect(nextIndex('PageDown', 5, 100)).toBe(0)
    expect(nextIndex('Home', 7, 10)).toBe(0)
    expect(nextIndex('End', 0, 10)).toBe(9)
    expect(nextIndex('a', 0, 10)).toBeNull()
    expect(nextIndex('End', 0, 0)).toBeNull()
  })

  it('reads and writes ISO-8601 durations', () => {
    expect(durationMs('PT5M')).toBe(5 * MINUTE)
    expect(durationMs('PT1H')).toBe(HOUR)
    expect(durationMs('P1D')).toBe(24 * HOUR)
    expect(durationMs('P1DT2H30M')).toBe(26.5 * HOUR)
    expect(durationMs('PT30S')).toBe(30_000)
    expect(durationMs('P')).toBeNull()
    expect(durationMs('PT')).toBeNull()
    expect(durationMs('5m')).toBeNull()
    expect(durationMs(undefined)).toBeNull()
    expect(isoDuration(5 * MINUTE)).toBe('PT5M')
    expect(isoDuration(HOUR)).toBe('PT1H')
    expect(isoDuration(24 * HOUR)).toBe('P1D')
    expect(isoDuration(26.5 * HOUR)).toBe('P1DT2H30M')
    expect(isoDuration(0)).toBe('PT0S')
  })
})

describe('the words of monitoring', () => {
  it('names series and units, and formats values with them', () => {
    expect(seriesTitle('cpuPercent')).toBe('CPU used')
    expect(seriesTitle('diskReadBytes')).toBe('Disk read bytes')
    expect(formatMetric(42.06, 'percent')).toBe('42.1%')
    expect(formatMetric(1204.4, 'count')).toBe('1,204')
    expect(formatMetric(3.5, 'ms')).toBe('3.5 ms')
    expect(formatMetric(null, 'percent')).toBe('no value')
    expect(speakMetric(42.06, 'percent')).toBe('42.1 percent')
    expect(unitOfMetric('memoryPercent')).toBe('percent')
    expect(unitOfMetric('connections')).toBe('count')
  })

  it('says what an alarm watches', () => {
    expect(alarmCondition({ metric: 'cpuPercent', comparison: 'above', threshold: 80, windowMinutes: 10 })).toBe('CPU used above 80% on average over 10 minutes')
    expect(alarmCondition({ metric: 'connections', comparison: 'below', threshold: 1, windowMinutes: 1 })).toBe('Open connections below 1 on average over 1 minute')
    expect(alarmName({ name: '  ', metric: 'memoryPercent', comparison: 'above', threshold: 72.5 })).toBe('Memory used above 72.5%')
    expect(alarmName({ name: 'web-01 busy', metric: 'cpuPercent', comparison: 'above', threshold: 1 })).toBe('web-01 busy')
  })

  it('says sizes and money in words', () => {
    expect(sizeWords({ cpu: 4, memoryGb: 16 })).toBe('4 CPUs and 16 GB of memory')
    expect(sizeWords({ tier: 'M30' })).toBe('tier M30')
    expect(sizeWords(undefined)).toBe('not known')
    expect(formatMoney({ amount: 38.5, currency: 'USD' })).toBe('$38.50')
    expect(formatMoney(undefined)).toBe('not known')
  })

  it('asks for a window ending at the current minute, a day by default', () => {
    expect(DEFAULT_RANGE).toBe('1d')
    expect(rangeOf('nonsense').id).toBe('1d')
    expect(windowOf(rangeOf('1h'), Date.parse('2026-10-23T10:00:42Z'))).toEqual({ from: '2026-10-23T09:00:00.000Z', to: '2026-10-23T10:00:00.000Z', step: 'PT1M' })
    expect(windowOf(rangeOf('7d'), Date.parse('2026-10-23T10:00:00Z')).step).toBe('PT1H')
  })
})
