import logging
import threading

from fastapi.testclient import TestClient
from test_pipeline import job_for, photograph

from closetos_media.server import app


def test_invalid_photograph_logs_only_job_identity_and_error_class(monkeypatch, caplog):
    class InvalidPipeline:
        def process(self, job):
            raise ValueError("Private photograph metadata\nFORGED_LOG_EVENT")

    monkeypatch.setattr(app.state, "token", "test-token", raising=False)
    monkeypatch.setattr(app.state, "pipeline", InvalidPipeline(), raising=False)
    capacity = threading.BoundedSemaphore(1)
    monkeypatch.setattr(app.state, "capacity", capacity, raising=False)
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
