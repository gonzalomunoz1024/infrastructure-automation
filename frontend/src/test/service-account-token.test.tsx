import { renderToString } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { ActsFor } from '../pages/SettingsPage'

/*
 * POST /service-accounts answers with the teams the account acts for: the ones given, or its
 * creator's when none were, and a note that says so, or that the policy refuses every job an
 * account with no team starts (docs/api.md, Identity).
 */

const strip = (html: string) => html.replace(/<!--.*?-->/g, '')

describe('the teams a new service account acts for', () => {
  it('lists the teams, and the note that they are its creator’s', () => {
    const html = strip(renderToString(<ActsFor teams={['team-compute', 'team-platform']} note="No teams were given: the account acts for Gonzalo's teams (team-compute, team-platform)" />))
    expect(html).toContain('It acts for:')
    expect(html).toMatch(/aria-label="Teams"[^>]*>.*team-compute.*team-platform/)
    expect(html).toContain('data-tone="neutral"')
    expect(html).toContain('No teams were given: the account acts for Gonzalo&#x27;s teams (team-compute, team-platform)')
  })

  it('warns when it acts for no team: the policy refuses every job it starts', () => {
    const note = 'No teams were given and Ana belongs to none: the account acts for no team, so the policy refuses every job it starts; create it again with teams to start jobs'
    const html = strip(renderToString(<ActsFor teams={[]} note={note} />))
    expect(html).toContain('No team')
    expect(html).not.toContain('aria-label="Teams"')
    expect(html).toContain('data-tone="warning"')
    expect(html).toContain('the policy refuses every job it starts')
  })

  it('needs no note when the teams were given', () => {
    const html = strip(renderToString(<ActsFor teams={['team-data']} />))
    expect(html).toContain('team-data')
    expect(html).not.toContain('data-tone')
  })
})
