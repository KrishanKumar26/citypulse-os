"""Compatibility for the one Python version the platform does not control.

Everything here targets Python 3.12 — requirements.txt says so, CI runs it, the
tests run on it. One component does not get a choice: the Spark job runs inside
`apache/spark:4.0.0-python3`, whose interpreter is 3.10. The image is chosen
deliberately (see Dockerfile.spark) because it already carries a matching JVM
and Scala build; installing a newer Python beside it would mean owning that
compatibility matrix by hand, which is the thing the base image is for.

So the modules copied into that image — common/, pipeline/, generator/ — must
import and run on 3.10, while the rest of the platform is free to use 3.12.

This was found the hard way. `common/events.py` imported `StrEnum`, added in
3.11, and every Spark container died on startup:

    ImportError: cannot import name 'StrEnum' from 'enum'
    (/usr/lib/python3.10/enum.py)

It restarted, died, and restarted for fifteen minutes while the rest of the
stack sat healthy around it, so the compose stack looked fine and no curated
window ever appeared. The streaming path had been verified before only by
running it natively on 3.12 — which proves the code, not the container.
"""

from __future__ import annotations

import sys
from enum import Enum

class StrEnumFallback(str, Enum):
    """`enum.StrEnum` for Python 3.10.

    Not simply `class X(str, Enum)`. That mixin's formatting behaviour moved
    twice: on 3.10 an f-string yields the *value*, on 3.12 it yields ``X.A``.
    Code written against 3.11's StrEnum expects the value from both `str()` and
    f-strings, so both are pinned here rather than inherited. Leaving it to the
    mixin would give an enum that serialises correctly on one interpreter and
    writes ``EventType.TRAFFIC`` into Kafka on another — a data defect rather
    than a crash, and far harder to notice than an ImportError.

    The two assignments are exactly what CPython 3.11's `enum.StrEnum` does, for
    the same reason: the members are strings, so string behaviour should be the
    string's.

    Defined unconditionally, not inside the version branch below, so a test can
    exercise it on any interpreter. A fallback that only exists on the one
    machine nobody develops on is a fallback nobody has checked.
    """

    __str__ = str.__str__
    __format__ = str.__format__


if sys.version_info >= (3, 11):
    from enum import StrEnum
else:
    StrEnum = StrEnumFallback


__all__ = ["StrEnum", "StrEnumFallback"]
