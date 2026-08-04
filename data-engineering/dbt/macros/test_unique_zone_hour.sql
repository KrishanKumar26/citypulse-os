{#
  Asserts the (zone_id, event_hour) grain of the hourly fact.

  Written as a project macro rather than pulling in dbt_utils for one test: a
  package dependency means every contributor and every CI run has to run
  `dbt deps` before anything works, which is a poor trade for six lines of SQL.
#}
{% test unique_zone_hour(model) %}

select
    zone_id,
    event_hour,
    count(*) as duplicate_rows
from {{ model }}
group by 1, 2
having count(*) > 1

{% endtest %}
