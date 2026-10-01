import logging
import threading

import pytest
from fastapi.testclient import TestClient
from test_pipeline import job_for, photograph

from closetos_media.execution import ExecutionRegistry
from closetos_media.server import app


def test_invalid_photograph_logs_only_job_identity_and_error_class(monkeypatch, caplog, tmp_path):
    class InvalidPipeline:
        def process(self, job, cancellation):
            raise ValueError("Private photograph metadata\nFORGED_LOG_EVENT")

    monkeypatch.setattr(app.state, "token", "test-token", raising=False)
    monkeypatch.setattr(app.state, "pipeline", InvalidPipeline(), raising=False)
    capacity = threading.BoundedSemaphore(1)
    monkeypatch.setattr(app.state, "capacity", capacity, raising=False)
    registry = ExecutionRegistry(tmp_path)
    monkeypatch.setattr(app.state, "executions", registry, raising=False)
    job = job_for(photograph())
    caplog.set_level(logging.INFO, logger="closetos_media.server")
    response = TestClient(app).post(
        "/process",
        json=job.model_dump(mode="json", by_alias=True),
        headers={"Authorization": "Bearer test-token"},
    )
    assert response.status_code == 422
    assert response.json() == {"detail": "The photograph could not be processed"}
    assert str(job.job_id) in caplog.text
    assert "ValueError" in caplog.text
    assert "Private photograph metadata" not in caplog.text
    assert "FORGED_LOG_EVENT" not in caplog.text
    assert all(record.exc_info is None for record in caplog.records)
    assert capacity.acquire(blocking=False)

    registry.close()


def test_owner_cancellation_requires_authentication_and_is_idempotent(monkeypatch, tmp_path):
    registry = ExecutionRegistry(tmp_path)
    monkeypatch.setattr(app.state, "token", "test-token", raising=False)
    monkeypatch.setattr(app.state, "executions", registry, raising=False)
    job = job_for(photograph())
    client = TestClient(app)
    endpoint = f"/owners/{job.owner_id}/cancel"
    assert client.post(endpoint).status_code == 401
    assert client.post(endpoint, headers={"Authorization": "Bearer wrong"}).status_code == 401
    with registry.track(job):
        pass
    headers = {"Authorization": "Bearer test-token"}
    with registry.track(job):
        response = client.post(endpoint, headers=headers)
        assert response.status_code == 200
        assert response.json() == {"drained": False}
    assert client.post(endpoint, headers=headers).json() == {"drained": True}
    assert client.post(endpoint, headers=headers).json() == {"drained": True}
    registry.close()


def test_late_processing_for_a_cancelled_owner_is_rejected_before_pipeline_work(
    monkeypatch, tmp_path
):
    class UnreachablePipeline:
        def process(self, job, cancellation):
            raise AssertionError("A cancelled owner's pipeline must not run")

    registry = ExecutionRegistry(tmp_path)
    monkeypatch.setattr(app.state, "token", "test-token", raising=False)
    monkeypatch.setattr(app.state, "executions", registry, raising=False)
    monkeypatch.setattr(app.state, "pipeline", UnreachablePipeline(), raising=False)
    capacity = threading.BoundedSemaphore(1)
    monkeypatch.setattr(app.state, "capacity", capacity, raising=False)
    job = job_for(photograph())
    registry.cancel_owner(job.owner_id)
    response = TestClient(app).post(
        "/process",
        json=job.model_dump(mode="json", by_alias=True),
        headers={"Authorization": "Bearer test-token"},
    )
    assert response.status_code == 410
    assert response.json() == {"detail": "Processing was cancelled"}
    assert capacity.acquire(blocking=False)
    registry.close()


def test_cancellation_storage_failures_do_not_expose_private_diagnostics(monkeypatch, tmp_path):
    registry = ExecutionRegistry(tmp_path)
    monkeypatch.setattr(app.state, "token", "test-token", raising=False)
    monkeypatch.setattr(app.state, "executions", registry, raising=False)

    def unavailable(owner):
        raise OSError("Private state directory")

    monkeypatch.setattr(registry, "_persist_cancellation", unavailable)
    job = job_for(photograph())
    response = TestClient(app).post(
        f"/owners/{job.owner_id}/cancel", headers={"Authorization": "Bearer test-token"}
    )
    assert response.status_code == 503
    assert response.json() == {"detail": "Worker cancellation status is unavailable"}
    assert "Private state directory" not in response.text
    registry.close()


def test_cancellation_waits_for_a_running_http_job_to_finish(monkeypatch, tmp_path):
    from concurrent.futures import ThreadPoolExecutor

    from closetos_media.execution import ProcessingCancelled

    entered, resume = threading.Event(), threading.Event()

    class RunningPipeline:
        def process(self, job, cancellation):
            entered.set()
            assert resume.wait(5)
            cancellation.checkpoint()
            raise AssertionError("A cancelled job must not return a result")

    registry = ExecutionRegistry(tmp_path)
    monkeypatch.setattr(app.state, "token", "test-token", raising=False)
    monkeypatch.setattr(app.state, "executions", registry, raising=False)
    monkeypatch.setattr(app.state, "pipeline", RunningPipeline(), raising=False)
    capacity = threading.BoundedSemaphore(1)
    monkeypatch.setattr(app.state, "capacity", capacity, raising=False)
    job = job_for(photograph())
    headers = {"Authorization": "Bearer test-token"}
    client = TestClient(app)
    try:
        with ThreadPoolExecutor(max_workers=1) as executor:
            processing = executor.submit(
                lambda: client.post(
                    "/process", json=job.model_dump(mode="json", by_alias=True), headers=headers
                )
            )
            assert entered.wait(5)
            endpoint = f"/owners/{job.owner_id}/cancel"
            assert client.post(endpoint, headers=headers).json() == {"drained": False}
            resume.set()
            response = processing.result(timeout=5)
            assert response.status_code == 410
            assert client.post(endpoint, headers=headers).json() == {"drained": True}
        assert capacity.acquire(blocking=False)
        with pytest.raises(ProcessingCancelled), registry.track(job):
            pytest.fail("Late processing must remain blocked")
    finally:
        resume.set()
        registry.close()


def test_non_ascii_authorization_is_rejected_without_a_server_error(monkeypatch, tmp_path):
    registry = ExecutionRegistry(tmp_path)
    monkeypatch.setattr(app.state, "token", "test-token", raising=False)
    monkeypatch.setattr(app.state, "executions", registry, raising=False)
    job = job_for(photograph())
    response = TestClient(app).post(
        f"/owners/{job.owner_id}/cancel", headers={"Authorization": b"Bearer \xff"}
    )
    assert response.status_code == 401
    with registry.track(job):
        pass
    registry.close()
