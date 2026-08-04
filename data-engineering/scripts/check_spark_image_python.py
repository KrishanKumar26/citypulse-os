#!/usr/bin/env python
"""Every module the Spark image carries must import on the Spark image's Python.

    docker run --rm -v "$PWD/data-engineering:/src" -w /src python:3.10-slim \
        python scripts/check_spark_image_python.py

The platform targets Python 3.12. One component does not get a choice: the Spark
job runs inside `apache/spark:4.0.0-python3`, whose interpreter is 3.10. Nothing
enforced that split until `common/events.py` started importing `StrEnum`, added
in 3.11. Every Spark container then died at startup with

    ImportError: cannot import name 'StrEnum' from 'enum'

restarted, and died again for fifteen minutes while the other eight services sat
healthy around it. The stack looked fine; it simply produced no curated windows.
That was caught by the full-stack job, twenty-five minutes in and last of six.
This takes seconds.

Only the three packages Dockerfile.spark copies are checked, because only they
have to meet the older floor. `intelligence/` and `ml/` are free to use 3.12.
"""

from __future__ import annotations

import importlib
import pathlib
import sys

# Kept in step with the COPY lines in data-engineering/Dockerfile.spark.
PACKAGES_IN_THE_IMAGE = ("common", "pipeline", "generator")

FIRST_PARTY = PACKAGES_IN_THE_IMAGE


def missing_third_party(error: ImportError) -> bool:
    """True when the import failed only because a dependency is not installed.

    This runs in a bare `python:3.10-slim`, with none of requirements.txt
    present. A missing psycopg is this container's business; a missing
    `common.something`, or any other kind of ImportError, is the codebase's.
    """
    message = str(error)
    if "No module named" not in message:
        return False
    quoted = message.split("'")
    if len(quoted) < 2:
        return False
    return not quoted[1].startswith(FIRST_PARTY)


def main() -> int:
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

    failures: list[tuple[str, Exception]] = []
    checked = 0

    for package in PACKAGES_IN_THE_IMAGE:
        for path in sorted(pathlib.Path(package).rglob("*.py")):
            if path.name == "__init__.py":
                continue
            module = str(path.with_suffix("")).replace("/", ".")
            checked += 1
            try:
                importlib.import_module(module)
            except ImportError as error:
                if missing_third_party(error):
                    continue
                failures.append((module, error))
            except SyntaxError as error:
                failures.append((module, error))

    print(f"{checked} modules checked on Python {sys.version.split()[0]}")
    for module, error in failures:
        print(f"  {module}: {type(error).__name__}: {error}")

    if failures:
        print(f"\n{len(failures)} module(s) will not load inside the Spark image.")
        return 1

    print("all load")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
