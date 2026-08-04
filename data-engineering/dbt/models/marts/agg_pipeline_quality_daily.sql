-- Daily pipeline health (PRD §24, §35).
--
-- Answers "is the data trustworthy today" as a query rather than a log search.
-- Rejections come from the DLQ and volumes from the raw tables, so the ratio is
-- computed from what actually landed rather than from what the pipeline
-- reported about itself.
with ingested as (
    select date_trunc('day', event_time) as day, count(*) as records_ingested
    from {{ ref('stg_traffic_events') }}
    group by 1
),
rejected as (
    select
        date_trunc('day', coalesce(event_time, rejected_at)) as day,
        count(*)                                             as records_rejected,
        count(distinct reason_code)                          as distinct_reasons
    from {{ source('citypulse', 'ingestion_dlq') }}
    group by 1
),
lag_stats as (
    select
        date_trunc('day', event_time) as day,
        max(ingestion_lag_seconds)    as max_lag_seconds,
        avg(ingestion_lag_seconds)    as avg_lag_seconds
    from {{ ref('stg_traffic_events') }}
    group by 1
)
select
    coalesce(i.day, r.day)                       as day,
    coalesce(i.records_ingested, 0)              as records_ingested,
    coalesce(r.records_rejected, 0)              as records_rejected,
    coalesce(r.distinct_reasons, 0)              as distinct_reject_reasons,
    round(
        100.0 * coalesce(i.records_ingested, 0)
        / nullif(coalesce(i.records_ingested, 0) + coalesce(r.records_rejected, 0), 0),
        3
    )                                            as validity_pct,
    round(l.avg_lag_seconds::numeric, 1)         as avg_ingestion_lag_seconds,
    l.max_lag_seconds
from ingested i
full outer join rejected r on r.day = i.day
left join lag_stats l on l.day = coalesce(i.day, r.day)
