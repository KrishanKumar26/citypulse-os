"""Create or reconcile the Kafka topics declared in topics.yml.

  python -m kafka_admin.create_topics --bootstrap-servers localhost:9092
  python -m kafka_admin.create_topics --dry-run          # print the plan, touch nothing

Idempotent: existing topics are left alone rather than recreated, so running
this at deploy time is safe. Partition counts can be increased but never
decreased, so a shrink is reported as an error the operator has to resolve
rather than silently ignored — a topic quietly running with the wrong partition
count is worse than a failed deploy.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

TOPICS_FILE = Path(__file__).resolve().parent / "topics.yml"


@dataclass(slots=True, frozen=True)
class TopicSpec:
    name: str
    partitions: int
    replication_factor: int
    configs: dict[str, str]
    description: str = ""


def _load_yaml(path: Path) -> dict[str, Any]:
    try:
        import yaml
    except ImportError as exc:  # pragma: no cover - depends on the environment
        raise SystemExit(
            "PyYAML is required to read topics.yml. "
            "Install data-engineering/requirements.txt."
        ) from exc
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def load_specs(path: Path = TOPICS_FILE) -> list[TopicSpec]:
    document = _load_yaml(path)
    specs = []
    for entry in document.get("topics", []):
        specs.append(
            TopicSpec(
                name=entry["name"],
                partitions=int(entry["partitions"]),
                replication_factor=int(entry["replication_factor"]),
                configs={k: str(v) for k, v in (entry.get("configs") or {}).items()},
                description=(entry.get("description") or "").strip(),
            )
        )
    if not specs:
        raise SystemExit(f"no topics declared in {path}")
    return specs


def reconcile(specs: list[TopicSpec], bootstrap_servers: str, *, dry_run: bool) -> int:
    if dry_run:
        for spec in specs:
            print(
                f"{spec.name}: partitions={spec.partitions} "
                f"rf={spec.replication_factor} configs={len(spec.configs)}"
            )
        return 0

    try:
        from kafka import KafkaAdminClient
        from kafka.admin import NewPartitions, NewTopic
        from kafka.errors import TopicAlreadyExistsError
    except ImportError as exc:  # pragma: no cover
        raise SystemExit(
            "kafka-python is required. Install data-engineering/requirements.txt."
        ) from exc

    admin = KafkaAdminClient(
        bootstrap_servers=bootstrap_servers.split(","),
        client_id="citypulse-topic-manager",
    )
    try:
        existing = set(admin.list_topics())

        to_create = [s for s in specs if s.name not in existing]
        if to_create:
            admin.create_topics(
                [
                    NewTopic(
                        name=s.name,
                        num_partitions=s.partitions,
                        replication_factor=s.replication_factor,
                        topic_configs=s.configs,
                    )
                    for s in to_create
                ],
                validate_only=False,
            )
            for spec in to_create:
                print(f"created {spec.name} ({spec.partitions} partitions)")

        # Reconcile partition counts on topics that already existed.
        described = admin.describe_topics([s.name for s in specs if s.name in existing])
        current: dict[str, int] = {}
        for entry in described:
            # kafka-python renamed this key from `topic` to `name` in 3.x.
            # Accepting both keeps the tool working against either client, which
            # matters because this runs at deploy time — a KeyError here would
            # fail a deployment over a library upgrade, not a real problem.
            name = entry.get("name") or entry.get("topic")
            if name is None:
                continue
            if entry.get("error_code"):
                raise SystemExit(
                    f"broker returned error {entry['error_code']} describing {name}; "
                    f"refusing to reconcile partitions against an unreliable answer"
                )
            current[name] = len(entry["partitions"])

        grow: dict[str, Any] = {}
        for spec in specs:
            have = current.get(spec.name)
            if have is None:
                continue
            if have > spec.partitions:
                raise SystemExit(
                    f"{spec.name} has {have} partitions but topics.yml declares "
                    f"{spec.partitions}. Kafka cannot reduce partitions; either "
                    f"raise the declared count or recreate the topic deliberately."
                )
            if have < spec.partitions:
                grow[spec.name] = NewPartitions(total_count=spec.partitions)

        if grow:
            admin.create_partitions(grow)
            for name, _ in grow.items():
                print(f"expanded {name} to {dict((s.name, s.partitions) for s in specs)[name]} partitions")

        unchanged = len(specs) - len(to_create) - len(grow)
        print(f"done: {len(to_create)} created, {len(grow)} expanded, {unchanged} unchanged")
    finally:
        admin.close()
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="create_topics")
    parser.add_argument("--bootstrap-servers", default="localhost:9092")
    parser.add_argument("--topics-file", type=Path, default=TOPICS_FILE)
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Print what would be created without contacting a broker.",
    )
    args = parser.parse_args(argv)
    return reconcile(load_specs(args.topics_file), args.bootstrap_servers, dry_run=args.dry_run)


if __name__ == "__main__":
    raise SystemExit(main())
