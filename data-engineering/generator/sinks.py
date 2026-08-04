"""Where generated events go.

The generator does not know whether it is feeding Kafka, a file or a terminal.
That indirection is not speculative: Kafka is not available in every
environment this runs in, and without a sink boundary the alternative would be
either a generator that cannot run locally or one that grows a second code path
per destination.

`KafkaSink` imports its driver lazily, so the module stays importable — and the
JSONL and stdout paths stay usable — on a machine with no Kafka client
installed.
"""

from __future__ import annotations

import sys
from abc import ABC, abstractmethod
from pathlib import Path
from typing import TextIO

from common.events import AnyEvent, partition_key, serialise


class Sink(ABC):
    """One destination for generated events."""

    @abstractmethod
    def emit(self, event: AnyEvent) -> None: ...

    def flush(self) -> None:
        """Push anything buffered. Called on a timer and at shutdown."""

    def close(self) -> None:
        self.flush()

    def __enter__(self) -> "Sink":
        return self

    def __exit__(self, *exc_info: object) -> None:
        self.close()


class StdoutSink(Sink):
    """Newline-delimited JSON on stdout, for piping and eyeballing."""

    def __init__(self, stream: TextIO | None = None) -> None:
        self._stream = stream or sys.stdout

    def emit(self, event: AnyEvent) -> None:
        self._stream.write(serialise(event) + "\n")

    def flush(self) -> None:
        self._stream.flush()


class JsonlSink(Sink):
    """Append-only JSONL file.

    This is the local stand-in for a Kafka topic: the local pipeline runner
    reads the same format Kafka would deliver, so the processing path is
    exercised for real even with no broker present.
    """

    def __init__(self, path: str | Path, *, buffer_size: int = 200) -> None:
        self._path = Path(path)
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._handle = self._path.open("a", encoding="utf-8")
        self._buffer: list[str] = []
        self._buffer_size = buffer_size

    def emit(self, event: AnyEvent) -> None:
        self._buffer.append(serialise(event))
        if len(self._buffer) >= self._buffer_size:
            self.flush()

    def flush(self) -> None:
        if self._buffer:
            self._handle.write("\n".join(self._buffer) + "\n")
            self._buffer.clear()
        self._handle.flush()

    def close(self) -> None:
        self.flush()
        self._handle.close()


class KafkaSink(Sink):
    """Publishes to Kafka, one topic per event type.

    Keyed by zone (or city, for weather) so a place's events keep their order
    within a partition — windowed aggregation downstream depends on it.
    """

    def __init__(
        self,
        bootstrap_servers: str,
        topic_for: dict[str, str],
        *,
        client_id: str = "citypulse-generator",
    ) -> None:
        try:
            from kafka import KafkaProducer  # imported here so the module loads without the driver
        except ImportError as exc:  # pragma: no cover - depends on the environment
            raise RuntimeError(
                "kafka-python is not installed. Install data-engineering/requirements.txt, "
                "or run the generator with --sink jsonl to work without a broker."
            ) from exc

        self._topic_for = topic_for
        self._producer = KafkaProducer(
            bootstrap_servers=bootstrap_servers.split(","),
            client_id=client_id,
            value_serializer=lambda value: value.encode("utf-8"),
            key_serializer=lambda key: key.encode("utf-8"),
            # Wait for all in-sync replicas. A synthetic feed that silently
            # loses records would make every downstream count wrong and give no
            # sign of it.
            acks="all",
            retries=5,
            linger_ms=50,
            compression_type="gzip",
        )

    def emit(self, event: AnyEvent) -> None:
        topic = self._topic_for[str(event.envelope.event_type)]
        self._producer.send(topic, key=partition_key(event), value=serialise(event))

    def flush(self) -> None:
        self._producer.flush()

    def close(self) -> None:
        self.flush()
        self._producer.close()


class MultiSink(Sink):
    """Fans one event out to several sinks — used to tee Kafka to a local file."""

    def __init__(self, *sinks: Sink) -> None:
        self._sinks = sinks

    def emit(self, event: AnyEvent) -> None:
        for sink in self._sinks:
            sink.emit(event)

    def flush(self) -> None:
        for sink in self._sinks:
            sink.flush()

    def close(self) -> None:
        for sink in self._sinks:
            sink.close()


class CountingSink(Sink):
    """Wraps a sink and counts what passed through, for tests and reporting."""

    def __init__(self, inner: Sink) -> None:
        self._inner = inner
        self.counts: dict[str, int] = {}

    def emit(self, event: AnyEvent) -> None:
        key = str(event.envelope.event_type)
        self.counts[key] = self.counts.get(key, 0) + 1
        self._inner.emit(event)

    @property
    def total(self) -> int:
        return sum(self.counts.values())

    def flush(self) -> None:
        self._inner.flush()

    def close(self) -> None:
        self._inner.close()
