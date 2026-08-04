"""The 3.10 StrEnum fallback behaves like the real one.

The Spark job runs on Python 3.10 while everything else here runs on 3.12, so
`common/compat.py` supplies StrEnum when the interpreter has none. That fallback
is dead code on the machine it is written on, which is exactly the code most
likely to be wrong — so it is exercised directly rather than only in the one
container that uses it.

What it has to get right is not just "exists". A `str, Enum` mixin serialises
differently across 3.10, 3.11 and 3.12, and the difference is silent: the enum
still works, it just writes `EventType.TRAFFIC` into Kafka instead of `TRAFFIC`.
An ImportError stops the pipeline; this would corrupt it.
"""

from __future__ import annotations

import json
import pathlib
import sys

import pytest

from common.compat import StrEnum, StrEnumFallback
from common.events import EventType


class Fallback(StrEnumFallback):
    TRAFFIC = "TRAFFIC"
    CITY_EVENT = "CITY_EVENT"


def test_str_gives_the_value_not_the_member_name():
    assert str(Fallback.TRAFFIC) == "TRAFFIC"


def test_f_string_gives_the_value():
    # The one that changed between 3.10 and 3.12, and the one that would have
    # written a member repr into every Kafka message rather than failing loudly.
    assert f"{Fallback.TRAFFIC}" == "TRAFFIC"
    assert "{}".format(Fallback.CITY_EVENT) == "CITY_EVENT"


def test_it_is_a_string_everywhere_a_string_is_expected():
    assert Fallback.TRAFFIC == "TRAFFIC"
    assert Fallback.TRAFFIC in {"TRAFFIC", "WEATHER"}
    assert json.dumps({"type": Fallback.TRAFFIC}) == '{"type": "TRAFFIC"}'
    assert "-".join([Fallback.TRAFFIC, Fallback.CITY_EVENT]) == "TRAFFIC-CITY_EVENT"


@pytest.mark.skipif(sys.version_info < (3, 11), reason="no stdlib StrEnum to compare against")
def test_the_fallback_matches_the_real_thing():
    # Same declarations, both implementations, every observable the pipeline
    # relies on. If the stdlib's behaviour ever moves again, this fails rather
    # than the Spark container quietly producing different bytes.
    for member, twin in ((Fallback.TRAFFIC, EventType.TRAFFIC),
                         (Fallback.CITY_EVENT, EventType.CITY_EVENT)):
        assert str(member) == str(twin)
        assert f"{member}" == f"{twin}"
        assert json.dumps(member) == json.dumps(twin)
        assert member == twin


def test_the_exported_name_is_the_right_one_for_this_interpreter():
    if sys.version_info >= (3, 11):
        import enum
        assert StrEnum is enum.StrEnum
    else:
        assert StrEnum is StrEnumFallback


# ---------------------------------------------------------------------------
# The guard that is meant to catch this next time
# ---------------------------------------------------------------------------

def test_the_guard_does_not_swallow_the_error_it_exists_for():
    """The 3.10 import check must not mistake a StrEnum failure for a missing dep.

    `check_spark_image_python.py` runs in a bare python:3.10-slim, so it has to
    ignore ImportErrors for packages that are simply not installed there. That
    filter is the one place the whole guard could silently stop working — if it
    were slightly too broad it would skip the very error that made it necessary
    and report success on a broken image.
    """
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "scripts"))
    from check_spark_image_python import missing_third_party

    # The actual error from the failed CI run. Must not be filtered out.
    real = ImportError("cannot import name 'StrEnum' from 'enum' (/usr/lib/python3.10/enum.py)")
    assert missing_third_party(real) is False

    # A first-party module that genuinely went missing. Must not be filtered.
    assert missing_third_party(ImportError("No module named 'common.events'")) is False
    assert missing_third_party(ImportError("No module named 'pipeline.loader'")) is False

    # Dependencies absent from the bare image. These are the only ones to skip.
    assert missing_third_party(ImportError("No module named 'psycopg'")) is True
    assert missing_third_party(ImportError("No module named 'pyspark'")) is True

    # Malformed messages must fail closed rather than skip.
    assert missing_third_party(ImportError("No module named")) is False
