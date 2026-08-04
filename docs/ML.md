# CityPulse OS — Forecasting

> Every number in this document was measured on data the model had not seen.
> Nothing here is an estimate of how well the model *should* do.

Related: `docs/ARCHITECTURE.md` for where this sits, `docs/DEVELOPMENT_PLAN.md`
for phase status.

---

## 1. What is forecast, and what is not

PRD §11 asks for five predictions. Four are produced:

| Target | Forecast | Why |
|---|---|---|
| Traffic congestion (`occupancy_ratio`) | yes | Densely measured every five minutes |
| Average speed (`average_speed_kph`) | yes | Same |
| Vehicle volume (`vehicle_count`) | yes | Same |
| Risk level (`risk_score`) | yes | Derived per window from the above |
| **Crowd intensity** | **no** | The platform has no crowd sensor |
| *(also)* Air quality (`aqi`) | **no** | Measured, but too sparsely at this grain |

**Crowd intensity** is not forecast because nothing measures it. A prediction of
a quantity with no actual cannot be scored, so it could be arbitrarily wrong
forever without anything detecting that — which makes it a fabrication rather
than a forecast.

**Air quality** is measured, but roughly once every six curated windows. At a
five-minute grain its lag features are null more often than not. Forward-filling
would manufacture the density: the same reading repeated six times would look
like six confirmations of a stable value, and the measured error would be
flattered by a target that barely moves. AQI forecasting belongs on its own
grain and is left out rather than faked at this one.

---

## 2. The model

**Ridge regression**, one model per (target, horizon) — twenty in total.

Chosen as a *baseline*, which the execution prompt (§15) requires before
complexity. That is not only process: traffic at a five-minute grain is
dominated by its own recent history and by time of day, both of which a linear
model with lag and calendar features captures directly. A gradient-boosted
ensemble would likely fit better; it would also be far harder to explain, and
PRD §11 requires every forecast to name its contributing factors. A linear
model's coefficients *are* that explanation, so the simpler model buys
something rather than merely costing less.

**Why ridge and not ordinary least squares.** The lag features are collinear by
construction — occupancy five minutes ago and fifteen minutes ago move together
— and OLS on collinear inputs produces large cancelling coefficients that swing
between refits. Users would see a forecast that changes its story every time the
model retrains.

**Why one model per horizon.** A single model predicting all five horizons would
have to express that its own error grows with distance, which it cannot. Separate
fits let each horizon carry its own measured error — which is exactly what the
confidence calculation consumes.

### Features

Fifteen, in three groups:

- **History of the target** — lags at 5/15/30/60 minutes, rolling means over
  15/60/180 minutes, rolling standard deviation over 60 minutes, and the
  15-minute delta. The delta matters because rising and falling congestion at
  the same level call for different predictions and a level-only model cannot
  tell them apart.
- **Calendar** — hour encoded cyclically as sine and cosine so 23:55 and 00:05
  are adjacent rather than maximally distant, plus day of week, weekend flag and
  peak-window flags. Computed in the city's own timezone: 09:00 local is the
  commute everywhere, 09:00 UTC is nothing in particular.
- **Conditions and character** — rainfall, open incidents, scheduled events, and
  a per-zone-type demand weight.

### Two rules that govern every feature

1. **Only the past.** A feature for time *t* reads observations at or before *t*
   and nothing after.
2. **Only what is knowable at issue time.** Weather at *t + 60min* would predict
   congestion at *t + 60min* superbly, and using it would be cheating — the
   platform holds no weather forecast, so at issue time that value does not
   exist.

---

## 3. Evaluation

**Temporal holdout, never a random split.** A random split would put 10:05 in
training and 10:10 in test for the same zone; the lag features would then carry
the answer straight across, producing an excellent MAE and a model that
forecasts nothing.

Rows straddling the boundary are dropped rather than assigned. A row *issued*
before the split whose *label* falls after it would otherwise let the model
predict into the test period having already been trained on it.

| | |
|---|---|
| Data | 162,980 curated windows, 20 zones, 4 weeks |
| Trained on | first 75% of the timeline |
| Evaluated on | last 25%, strictly after everything trained on |
| Rows per (metric, horizon) | ~39,000–40,700 |

**Baseline: persistence** — "the next value equals the last observed one". Every
model is reported against it, because a model that cannot beat persistence has
not earned its existence, and an MAE without that comparison lets a useless
model look precise.

---

## 4. Measured results

Run `traffic-baseline v1`, ridge (α = 1.0). MAE is in each target's own units.

### Traffic congestion — `occupancy_ratio` (1.0 = rated capacity)

| Horizon | MAE | MAPE | Persistence MAE | Improvement |
|---|---|---|---|---|
| 15 min | 0.0583 | 14.8% | 0.0784 | 25.7% |
| 30 min | 0.0856 | 24.4% | 0.1190 | 28.0% |
| 60 min | 0.1268 | 40.9% | 0.1945 | 34.8% |
| 3 h | 0.1891 | 71.1% | 0.4375 | 56.8% |
| 6 h | 0.2293 | 84.1% | 0.5451 | 57.9% |

### Average speed — `average_speed_kph`

| Horizon | MAE | MAPE | Persistence MAE | Improvement |
|---|---|---|---|---|
| 15 min | 2.19 | 8.6% | 2.68 | 18.0% |
| 30 min | 2.98 | 12.2% | 3.56 | 16.4% |
| 60 min | 4.42 | 18.9% | 5.32 | 16.8% |
| 3 h | 6.72 | 31.1% | 11.22 | 40.2% |
| 6 h | 7.37 | 35.4% | 12.13 | 39.2% |

### Vehicle volume — `vehicle_count`

| Horizon | MAE | MAPE | Persistence MAE | Improvement |
|---|---|---|---|---|
| 15 min | 45.0 | 9.0% | 81.7 | 44.9% |
| 30 min | 57.6 | 13.3% | 128.7 | 55.3% |
| 60 min | 93.2 | 25.5% | 225.6 | 58.7% |
| 3 h | 270.8 | 88.4% | 538.2 | 49.7% |
| 6 h | 326.7 | 124.3% | 679.4 | 51.9% |

### Composite risk — `risk_score` (0–100)

| Horizon | MAE | MAPE | Persistence MAE | Improvement |
|---|---|---|---|---|
| 15 min | 3.12 | 17.6% | 4.22 | 26.2% |
| 30 min | 3.75 | 22.0% | 5.87 | 36.1% |
| 60 min | 5.27 | 33.5% | 8.98 | 41.3% |
| 3 h | 8.09 | 57.5% | 19.32 | 58.1% |
| 6 h | 9.86 | 69.6% | 24.27 | 59.4% |

**All 20 models beat persistence**, by 16% to 59%.

### Reading these numbers honestly

- **Error grows with horizon, steeply.** Six-hour congestion MAE (0.229) is four
  times the fifteen-minute figure (0.058). A six-hour forecast is a rough
  direction, not a number to plan against, and the confidence the API reports
  says so.
- **MAPE is worse than MAE suggests, and that is expected.** Percentage error
  explodes when the actual is small — being wrong by 0.1 occupancy is minor at
  0.9 and enormous at 0.05. Quiet overnight windows dominate the high MAPE
  figures. Rows with a near-zero actual are excluded from MAPE entirely rather
  than clamped, because a fabricated percentage would corrupt the average.
- **The improvement over persistence widens with horizon.** Persistence degrades
  fast — "in six hours it will be exactly as it is now" is nearly useless — so
  beating it at 6 h is easier than at 15 min. The 16–18% gains at short horizons
  are the harder and more meaningful ones.
- **This is synthetic data.** The generator models Indian metro commute patterns
  with coupled weather, incidents and events, which makes the problem realistic
  but not real. These figures establish that the pipeline, features and
  evaluation are sound. They are **not** a claim about accuracy on a real city.

---

## 5. Confidence

PRD §11's exit criterion: confidence is *derived from measured error, not
asserted*. Here that is literal.

```
relative_error = MAE / typical_scale        # MAE read from model_metrics
confidence     = min(0.95, 1 - relative_error)
```

The MAE comes from the database row for that exact metric and horizon, written
by the evaluation above. A six-hour forecast is less confident than a
fifteen-minute one because it was *measured* to be worse — not because someone
decided long forecasts should look uncertain.

Measured confidence for congestion:

| Horizon | MAE | Confidence |
|---|---|---|
| 15 min | 0.0583 | 0.942 |
| 30 min | 0.0856 | 0.914 |
| 60 min | 0.1268 | 0.873 |
| 3 h | 0.1891 | 0.811 |
| 6 h | 0.2293 | 0.771 |

Capped at 0.95: a model is never certain, and 100% would be a claim no
measurement can support.

**Prediction intervals** come from the observed spread of holdout residuals
(±1.96σ), not from the model's internal variance estimate — that assumes normal,
homoscedastic errors, and traffic residuals are neither.

---

## 6. Explanations

Every forecast stores its contributing factors: the four features whose
standardised value × coefficient moved the prediction furthest from the average,
with direction and magnitude. For a linear model that is the actual arithmetic,
not a narrative written around the answer.

Stored on the forecast rather than recomputed on read, so a prediction can still
be explained after the feature code has moved on.

---

## 7. Accuracy over time

Measured error at training time is a claim about the past. `forecast_accuracy`
checks whether it held: once a forecast's target time passes and the real window
exists, the two are compared and the absolute error, percentage error and
whether the actual fell inside the advertised interval are recorded.

Without this the platform could report a confidence forever without ever
checking whether it was earned — indistinguishable from making it up.

---

## 8. Reproducing this

```bash
cd data-engineering
set -a && . ../.env && set +a

python -m ml.train --dry-run        # evaluate and print, write nothing
python -m ml.train --model-version v1
python -m ml.predict                # forecasts from the ACTIVE run
python -m ml.score                  # score past forecasts against actuals
```

Training takes roughly three minutes over four weeks of history on a laptop.
Fitting is closed-form via the normal equations, so the same data always
produces the same model — which is what makes yesterday's measured error
applicable to today's predictions.

---

## 9. Known limitations

1. **Synthetic data.** See above. The evaluation machinery is real; the city is not.
2. **No crowd intensity, no AQI.** Both stated with reasons in §1.
3. **One model family.** Ridge only. Comparing against gradient boosting is
   deferred until there is a reason to believe the extra complexity pays, which
   the measured baseline is the way to establish.
4. **Weather is a current observation, not a forecast.** The model knows it is
   raining now, not that rain is coming. Real weather forecasts would likely be
   the single largest accuracy gain available, particularly at the 3 h and 6 h
   horizons where the model is weakest.
5. **No per-zone models.** All zones share one fit per (metric, horizon), with
   zone character entering only through a demand weight. Zones with unusual
   patterns are served worse than the aggregate figures suggest.
