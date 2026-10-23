import { useRef, useState, type FormEvent } from 'react'
import { Button, Field, Input, Modal, OverlayBody, OverlayFooter, OverlayHeader, Segmented, Select } from '@iaf/ui'
import { ConfirmDialog, type Problem } from '@iaf/console-kit'
import { FailureNote, useCommand } from '../../app/commands'
import { alarmCondition, alarmName, formatThreshold, monitoringApi, newAlarmId, seriesTitle, unitOfMetric, unitSuffix, unitWord, type Alarm, type AlarmComparison, type AlarmInput } from '../../lib/monitoring'

/*
 * Creating, changing and deleting an alarm, from the Monitoring tab and the Alarms page alike.
 * The web app picks the alarm's id when the form is first sent, so a retried create is the same
 * alarm (PUT /console/v1/alarms/{alarmId}); the BFF evaluates it at once, so the list shows its
 * state as soon as it is saved.
 */

/** The queries an alarm change touches: a resource's alarms and the account's. */
export const ALARM_QUERIES = ['console-alarms', 'alarms']

export interface SeriesChoice {
  name: string
  unit?: string
}

export interface AlarmDialogProps {
  /** the resource the alarm watches, which an edit keeps */
  wfrn: string
  /** the resource's name, for the dialog's words */
  resourceName: string
  /** the series the resource's metrics read answers; an edit keeps its own when the read is not at hand */
  series: SeriesChoice[]
  alarm?: Alarm
  /** the series a new alarm starts on */
  metric?: string
  onClose: () => void
}

function problemFor(problems: Problem[], field: string): string | undefined {
  return problems.find((p) => p.field === field)?.message
}

export function AlarmDialog({ wfrn, resourceName, series, alarm, metric, onClose }: AlarmDialogProps) {
  const cmd = useCommand()
  const choices = series.length ? series : alarm ? [{ name: alarm.metric, unit: unitOfMetric(alarm.metric) }] : []
  const [name, setName] = useState(alarm?.name ?? '')
  const [picked, setPicked] = useState(alarm?.metric ?? metric ?? choices[0]?.name ?? '')
  const [comparison, setComparison] = useState<AlarmComparison>(alarm?.comparison ?? 'above')
  const [threshold, setThreshold] = useState(alarm ? String(alarm.threshold) : '')
  const [windowText, setWindowText] = useState(String(alarm?.windowMinutes ?? 10))
  const [problems, setProblems] = useState<Problem[]>([])
  // taken when the form is first sent and kept for its retries: the same id is the same alarm
  const idRef = useRef<string | null>(alarm?.alarmId ?? null)

  const unit = choices.find((c) => c.name === picked)?.unit ?? unitOfMetric(picked)
  const value = Number(threshold)
  const minutes = Number(windowText)
  const thresholdError = threshold.trim() === '' ? undefined : Number.isFinite(value) ? undefined : 'Type a number'
  const windowError = windowText.trim() === '' || (Number.isInteger(minutes) && minutes >= 1 && minutes <= 1440) ? undefined : 'A whole number of minutes, from 1 to 1440'
  const ready = !!picked && threshold.trim() !== '' && !thresholdError && !windowError && name.length <= 128
  const input: AlarmInput = { wfrn, metric: picked, comparison, threshold: value, windowMinutes: windowText.trim() ? minutes : 10, ...(name.trim() ? { name: name.trim() } : {}) }
  const title = alarm ? `Edit ${alarmName(alarm)}` : 'Create an alarm'

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!ready) return
    setProblems([])
    idRef.current ??= newAlarmId()
    const alarmId = idRef.current
    const saved = await cmd.run(alarm ? 'Save the alarm' : 'Create the alarm', () => monitoringApi.putAlarm(alarmId, input), {
      invalidates: ALARM_QUERIES,
      quiet: true,
      onProblems: setProblems,
      done: (a) => ({ title: alarm ? 'Alarm saved' : 'Alarm created', message: `${alarmName(a)}: ${a.state === 'alarm' ? 'in alarm now' : a.state === 'ok' ? 'OK now' : 'not enough data yet'}.` }),
    })
    if (saved) onClose()
  }

  return (
    <Modal onClose={onClose} label={title} width="max-w-lg">
      <form onSubmit={(e) => void submit(e)} noValidate>
        <OverlayHeader title={title} subtitle={`Watches ${resourceName}: the average of a series over a window, compared with a threshold every minute.`} icon="bell" />
        <OverlayBody>
          <div className="space-y-5">
            <Field label="Series" error={problemFor(problems, '/metric')}>
              <Select ariaLabel={`Series: ${seriesTitle(picked)}`} value={picked} onChange={setPicked} options={choices.map((c) => ({ value: c.name, label: seriesTitle(c.name), hint: unitWord(c.unit) || undefined }))} />
            </Field>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Field label="Fires when the average is">
                <Segmented<AlarmComparison>
                  ariaLabel="Fires when the average is above or below the threshold"
                  value={comparison}
                  onChange={setComparison}
                  full
                  options={[
                    { value: 'above', label: 'Above' },
                    { value: 'below', label: 'Below' },
                  ]}
                />
              </Field>
              <Field label={`Threshold${unitWord(unit) ? `, ${unitWord(unit)}` : ''}`} htmlFor="alarm-threshold" required error={thresholdError ?? problemFor(problems, '/threshold')}>
                <Input id="alarm-threshold" data-autofocus inputMode="decimal" value={threshold} onChange={(e) => setThreshold(e.target.value)} invalid={!!thresholdError} placeholder={unit === 'percent' ? '80' : '400'} trailing={unitSuffix(unit).trim() ? <span className="text-caption text-tertiary">{unitSuffix(unit).trim()}</span> : undefined} />
              </Field>
            </div>
            <Field label="Window, minutes" htmlFor="alarm-window" hint="The average over this many minutes is compared, from 1 to 1440." error={windowError ?? problemFor(problems, '/windowMinutes')}>
              <Input id="alarm-window" inputMode="numeric" value={windowText} onChange={(e) => setWindowText(e.target.value)} invalid={!!windowError} />
            </Field>
            <Field label="Name" htmlFor="alarm-name" hint="Optional, up to 128 characters. Without one, the alarm is named by what it watches." error={name.length > 128 ? 'Up to 128 characters' : problemFor(problems, '/name')}>
              <Input id="alarm-name" value={name} onChange={(e) => setName(e.target.value)} placeholder={ready ? alarmName(input) : `${resourceName} CPU above 80%`} maxLength={160} invalid={name.length > 128} />
            </Field>
            {ready && (
              <p className="text-caption text-secondary" aria-live="polite">
                Fires when the average of {seriesTitle(picked)} over {input.windowMinutes === 1 ? '1 minute' : `${input.windowMinutes} minutes`} is {comparison} {formatThreshold(value, unit)}.
              </p>
            )}
            <FailureNote failure={cmd.failure} shownFields={['metric', 'threshold', 'windowMinutes', 'name']} compact />
          </div>
        </OverlayBody>
        <OverlayFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={cmd.busy} disabled={!ready}>
            {alarm ? 'Save alarm' : 'Create alarm'}
          </Button>
        </OverlayFooter>
      </form>
    </Modal>
  )
}

/** Asks once, then deletes the alarm. */
export function DeleteAlarmDialog({ alarm, resourceName, onClose }: { alarm: Alarm; resourceName: string; onClose: () => void }) {
  const cmd = useCommand()
  return (
    <ConfirmDialog
      title={`Delete ${alarmName(alarm)}?`}
      tone="danger"
      icon="trash"
      confirmLabel="Delete alarm"
      message={`It stops watching ${resourceName} (${alarmCondition(alarm)}). This cannot be undone.`}
      onClose={onClose}
      onConfirm={async () => {
        await cmd.run('Delete the alarm', () => monitoringApi.deleteAlarm(alarm.alarmId), { invalidates: ALARM_QUERIES, done: () => ({ title: 'Alarm deleted', message: `${alarmName(alarm)} no longer watches ${resourceName}.` }) })
      }}
    />
  )
}
