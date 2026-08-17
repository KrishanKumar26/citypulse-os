# CityPulse OS — Working Memory

What the code cannot tell you: decisions and their reasons, bugs that were real,
and traps that cost hours. Read with `CONTEXT.md`.

Everything here was measured, not recalled. Where a number appears, it came from
a run against the deployment.

---

## 1. The rule that matters most

**A check that cannot show its evidence is not a check.**

This was learned four times in one session, each time expensively, each time
caught by the user rather than by me:

| The check | Why it lied |
|---|---|
| `grep` for jargon over `app/(app)` string literals | The worst offender was generated in a Python f-string and stored in the database. The grep could not see it, returned nothing, and was believed. |
| A contrast scan over nine screens | Every navigation had landed back on `/login`. It audited one page nine times and reported identical findings, which is not what nine screens look like. |
| A jargon sweep matching case-insensitively | `SYNTHETIC` matched the sidebar's "partly **synthetic**". The scan was finding itself. |
| A deploy poll grepping for `0a1026` | It matched the string `(want #0a1026)` in its own output and reported LIVE while the old build was still serving. |

Every one of these was fixed the same way: **print the line, then read it.** Make
the check name the page it measured, the parameter it sent, the status code it
got. Then believe it.

Corollary: `curl -w '%{time_total}'` without `%{http_code}` produced a "fast
login" that was actually a 429 from the rate limiter.

## 2. Bugs that were real

Found while measuring something else. All fixed.

- **Unit mismatch on one card.** The situation card scaled occupancy by 100 to
  show "162 % of capacity" while the stored sentence carried the raw `1.62` —
  the same fact as two numbers, three lines apart. Fixed by making the sentence
  speak in multiples, which has no units to disagree about.
- **A forecast wearing another metric's unit.** The city outlook forecasts one
  metric (occupancy). It was paired with every anomaly regardless, formatted
  with the *anomaly's* scale: an occupancy ratio of 0.61 rendered "0.6 / 100"
  under a risk figure. Every row appeared to predict the city becoming
  completely safe within fifteen minutes. The backend had been sending
  `targetMetric` all along; the frontend never looked.
- **Provenance mark collision.** The zone tables marked provenance with
  `PROVENANCE_LABEL[source].charAt(0)`. "measured" and "modelled" share a first
  letter, so sixty-two rows rendered an instrument and a model identically — in
  the one column built to tell them apart.
- **Percent rounding disagreement.** The anomaly sentence was formatted from the
  raw percent change while the row stored it rounded. A change of 122.5 was
  written "+123%" in prose and 122.5 on the record.
- **Contrast.** Sidebar section headings, card labels and the demo-data
  disclosure used `content-disabled` — 2.6:1 dark, 2.8:1 light. Correct for the
  *absence* of a value, wrong for a heading. The labels moved up a step; the
  token's value was not changed, because `content-tertiary` is already 5.1:1 and
  raising it would collapse two steps into one.
- **Login enumeration timing.** A registered address answered ~1 s slower than
  an unregistered one, four times out of four. The dummy hash was doing its job;
  the difference was the extra read, write and committed transaction needed to
  count the failure towards the lockout. Both branches are now held to a floor.

## 3. The outage, and it was mine

The deployment stopped accepting logins and signups. Reads kept working, so the
dashboard looked healthy.

```
psycopg.errors.DiskFull: could not extend file because
project size limit (512 MB) has been exceeded
```

Neon's free tier is 512 MB and the database had **no retention of any kind**.
Earlier the same afternoon I had changed the generator tick from 300 s to 60 s —
five readings per window instead of one — which multiplied the write volume by
five, hourly, without measuring the disk cost. I had measured CPU and load time
and concluded it was free.

Three further mistakes while fixing it:

1. The prune step was placed **after** the load. The load is the thing hitting
   DiskFull, so it failed the job and skipped the step that would have freed the
   space. Freeing space must come before needing it.
2. Plain `VACUUM` was used. It marks dead tuples reusable by the same table and
   returns nothing to the filesystem — 31,546 rows deleted, size unchanged at
   489 MB. `VACUUM FULL` was needed.
3. Retention was first set to 30 days, which freed almost nothing: the refresh
   writes three hours of events every hour, so the raw tables grow at three
   times real time and everything in them is days old.

Lesson: **when you make something write more, measure the disk, not just the
clock.**

## 4. The alert engine was never broken

Two hours were spent fixing a bug that did not exist. The Command Centre showed
"Nothing suggested yet" on every row, so I queried
`GET /api/v1/alerts?citySlug=X&status=OPEN`. That endpoint takes `cityId`, and
its status enum has no `OPEN`. Both parameters were ignored, the filter matched
nothing, and I read an empty list as an empty database.

There were ten open alerts with real recommended actions the whole time. The
screen in the screenshot was Ahmedabad, whose busiest zone sat at 0.94 of
capacity against a threshold of 1.00 — "Nothing suggested yet" was the product
being accurate.

The tick change made in the name of that non-existent bug is still in place, on
its own merit, and is documented as such.

## 5. Deployment traps

- **Local green means nothing about the build that ships.** A test importing
  `globSync` from `node:fs` type-checked here — this machine has newer
  `@types/node` — and failed Vercel's build on a clean `npm ci`. The theme sat
  undeployed for hours while I polled for it. If a push does not appear within a
  few minutes, check the build log before assuming propagation.
- **Render's free tier sleeps** after fifteen minutes idle. Cold start measured
  at 63–104 seconds. The login form now explains this after four seconds; the
  API client has a 120-second ceiling, generous on purpose because a shorter one
  would abandon a request that was about to succeed.
- **Stored strings do not change when the code does.** Anomaly sentences are
  composed at detection time and written to the row, so a wording change leaves
  every existing row on the old phrasing for as long as the feed shows it.
  `intelligence/rephrase.py` rebuilds them from the evidence already on the row,
  runs on every refresh, and is idempotent.
- **BCrypt cost lives in the hash string.** Lowering the encoder's strength
  speeds up new accounts only; existing users keep verifying at the old cost
  forever. `AuthService` re-hashes on the next successful sign-in.

## 6. Decisions worth not re-litigating

- **Synthetic feeds stay.** PRD §43 requires the platform to run with no external
  API. Removing them empties fourteen modules. The honesty is in the labelling,
  not in the absence.
- **`zone_metrics` is pruned at thirty days, and the number is derived.** It was
  never pruned until the database filled a second time on 16 August 2026. The
  old rule was right that thinning it starves the detector and wrong never to
  ask by how much: the floor is twelve samples in an hour-of-week bucket, a week
  supplies exactly twelve, so a month is four times the floor. See
  `CONTEXT.md` §4. **Lesson: an exemption defended on a danger nobody quantified
  is a guess wearing a rule's clothes.**
- **Two registers of language.** Operator and citizen screens use plain words;
  Data Health, Data Sources, API Management, Architecture and Security keep
  their technical vocabulary. Simplifying those would remove what their reader
  came for.
- **The situation card leads with the situation.** "Roads are 3.1x as full as
  usual for this time of day", not the deviation score. The evidence is still on
  the row and in the Technical Details panel; it is not the message.
- **Recommended actions are never composed by the product.** A rule's
  recommendation is shown as itself; generic guidance is labelled "general
  guidance" so the two cannot be mistaken for each other.
- **Both themes were built by measurement.** Ratios and ΔE separations are
  recorded beside the tokens. An override written for the primary button was
  removed after measuring — it solved a problem the tokens had already solved.

## 7. Open items

| Item | Owner |
|---|---|
| WAQI token replaced 17 Aug 2026; 10 zones now report measured air. The stored value had been 79 chars — aqicn.org issues **40** hex chars, not the 32 this table used to claim, and that wrong number was never checked against a real token | done |
| `hosted-check@citypulse.local` still active in production (suspend via `PATCH /api/v1/users/{id}/status`; there is no delete endpoint) | user |
| Database filled 16 Aug 2026; `zone_metrics` now kept 30 days | done |
| Traffic is real for 60 of 62 zones via TomTom; 2 snap too far or report too little confidence | done |
| Whether the zones reporting exactly free-flow are genuinely uncovered — `scripts/probe_tomtom_variance.py` answers it, and the production feed will too after a week of `traffic_source` | open |
| Phase 9: performance and accessibility unmeasured | open |
| **Six places still read `occupancy_ratio` alone, which V22 made null on every zone a real feed covers.** `ZoneTable` was given `TrafficValue` and the rest were not, so a zone detail shows an empty traffic tile and a flat chart while the number sits in `speed_ratio` beside it. Not a crash and not wrong — the reading genuinely is absent from that column — but it reads as a dead feed. `live/page.tsx` 194 and 378 and `ZoneIntelligence.tsx` 134 need the existing component; `ZoneIntelligence.tsx` 84, `anomalies/page.tsx` 365 and `forecast/page.tsx` 52 are series and need `speedRatio` on `ZoneHistoryPoint` first, which is a backend change | open |
| Two dead tracked files (`SignalFlow.tsx`, `air_provenance.py`) | cleanup |
