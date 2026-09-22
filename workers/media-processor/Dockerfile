FROM python:3.12-slim-bookworm AS build
COPY --from=ghcr.io/astral-sh/uv:0.10.8 /uv /usr/local/bin/uv
WORKDIR /worker
COPY workers/media-processor/pyproject.toml workers/media-processor/uv.lock ./
COPY workers/media-processor/src ./src
RUN uv sync --locked --no-dev
ENV U2NET_HOME=/models OMP_NUM_THREADS=2
RUN uv run --no-sync python -m closetos_media download-model
RUN uv run --no-sync python -m closetos_media download-embeddings

FROM python:3.12-slim-bookworm
RUN useradd --system --uid 10001 --create-home worker
WORKDIR /worker
COPY --from=build --chown=worker:worker /worker /worker
COPY --from=build --chown=worker:worker /models /models
ENV PATH=/worker/.venv/bin:$PATH U2NET_HOME=/models OMP_NUM_THREADS=2 PYTHONUNBUFFERED=1
USER worker
EXPOSE 8800
ENTRYPOINT ["python", "-m", "closetos_media"]
CMD ["process"]
