"""Database write helpers that survive a connection worth less than a local socket.

Every bulk write here used to be a single `executemany` over the whole payload.
Against a local PostgreSQL that is the fastest thing to do. Against a hosted one
across the public internet it is not: seeding Neon died repeatedly with

    psycopg.OperationalError: sending prepared query failed: SSL error: bad length
    SSL SYSCALL error: EOF detected

partway through a 13,440-row baseline write and a 5,000-row weather batch. The
row count is not itself the problem — the size of a single uninterrupted send is.
A batch that takes long enough to cross a NAT idle timeout or a pooler's limit
gets its connection cut, and because the caller commits at the end, everything
already sent is rolled back too.

Splitting the send makes each round trip short enough to complete, and turns a
lost seed into a lost chunk.
"""

from __future__ import annotations

from typing import Iterable, Sequence

import psycopg

# Chosen from what failed rather than from a benchmark: 5,000-row batches broke
# on this link and 1,000-row ones did not. Locally the difference is not
# measurable — psycopg already round-trips per batch, so this only changes how
# many round trips there are, not what each one costs per row.
DEFAULT_CHUNK_SIZE = 1000


def execute_batched(
    connection: psycopg.Connection,
    sql: str,
    rows: Sequence[tuple],
    *,
    chunk_size: int = DEFAULT_CHUNK_SIZE,
) -> int:
    """Run `sql` over `rows` in chunks, in one transaction.

    Does not commit. The caller decides the transaction boundary, because for
    most of these jobs a partial write is worse than no write — a half-learned
    baseline set would be used as if it were complete.

    Returns rows *affected*, summed across chunks — not rows sent. The
    difference matters: most of these statements carry `ON CONFLICT DO NOTHING`,
    so a re-run of the same batch legitimately affects nothing, and reporting
    the payload length instead would claim work that did not happen.
    """
    if not rows:
        return 0

    affected = 0
    with connection.cursor() as cursor:
        for start in range(0, len(rows), chunk_size):
            cursor.executemany(sql, rows[start:start + chunk_size])
            affected += cursor.rowcount
    return affected


def chunked(items: Sequence, size: int = DEFAULT_CHUNK_SIZE) -> Iterable[Sequence]:
    """Yield consecutive slices of `items`. Kept next to the writer it exists for."""
    for start in range(0, len(items), size):
        yield items[start:start + size]
