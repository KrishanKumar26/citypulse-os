# Generator, topic manager and local pipeline runner.
#
# Python 3.12, not 3.13+: PySpark and Airflow do not support newer runtimes, and
# using one image family across the data platform keeps the shared `common/`
# package running on the same interpreter everywhere.

FROM python:3.12-slim AS base

# Fail fast and log immediately — a buffered stdout in a container means the
# last thing before a crash is the thing you never see.
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /opt/citypulse/data-engineering

# Dependencies before source, so a code change does not invalidate the pip layer.
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY common/ ./common/
COPY generator/ ./generator/
COPY pipeline/ ./pipeline/
COPY kafka_admin/ ./kafka_admin/

# Unprivileged: nothing here needs root, and a container that cannot write to
# its own image is one fewer way a compromise spreads (PRD §30).
RUN useradd --create-home --uid 10001 citypulse \
    && chown -R citypulse:citypulse /opt/citypulse
USER citypulse

ENV PYTHONPATH=/opt/citypulse/data-engineering

# No default command: this image backs several services, and each declares its
# own in docker-compose.yml. A default here would only be a wrong guess.
