import threading
from concurrent.futures import ThreadPoolExecutor

import pytest
from test_pipeline import MemoryStorage, NoAnalysis, TestSegmentation, job_for, photograph

from closetos_media.execution import (
    CancellableStorage,
    Cancellation,
    ExecutionRegistry,
    ProcessingBusy,
    ProcessingCancelled,
)
from closetos_media.pipeline import Pipeline


def test_cancellation_tracks_running_jobs_until_they_finish_and_rejects_late_requests(tmp_path):
    registry = ExecutionRegistry(tmp_path)
    job = job_for(photograph())
    with registry.track(job) as control:
        assert registry.cancel_owner(job.owner_id) is False
        with pytest.raises(ProcessingCancelled):
            control.checkpoint()
        with pytest.raises(ProcessingCancelled), registry.track(job):
            pytest.fail("A cancelled owner cannot start another job")
    assert registry.cancel_owner(job.owner_id) is True
    registry.close()
    restarted = ExecutionRegistry(tmp_path)
    with pytest.raises(ProcessingCancelled), restarted.track(job):
        pytest.fail("Cancellation must survive worker restarts")
    restarted.close()
    assert str(job.owner_id) not in " ".join(path.name for path in tmp_path.iterdir())


def test_another_owner_remains_available_and_duplicate_jobs_cannot_overlap(tmp_path):
    registry = ExecutionRegistry(tmp_path)
    first, second = job_for(photograph()), job_for(photograph())
    with registry.track(first) as control:
        with pytest.raises(ProcessingBusy), registry.track(first):
            pytest.fail("A duplicate job cannot overlap")
        assert registry.cancel_owner(second.owner_id) is True
        control.checkpoint()
    with registry.track(first):
        pass
    registry.close()


def test_registry_lock_prevents_independent_workers_from_misreporting_a_drained_owner(tmp_path):
    registry = ExecutionRegistry(tmp_path)
    with pytest.raises(RuntimeError, match="Another media worker"):
        ExecutionRegistry(tmp_path)
    job = job_for(photograph())
    with registry.track(job), pytest.raises(RuntimeError, match="must finish"):
        registry.close()
    registry.close()
    replacement = ExecutionRegistry(tmp_path)
    replacement.close()


def test_failed_persistence_cancels_running_work_but_cannot_confirm_durable_cancellation(
    tmp_path, monkeypatch
):
    registry = ExecutionRegistry(tmp_path)
    job = job_for(photograph())

    def unavailable(owner):
        raise OSError("Private disk diagnostic")

    persist = registry._persist_cancellation
    monkeypatch.setattr(registry, "_persist_cancellation", unavailable)
    with registry.track(job) as control:
        with pytest.raises(OSError):
            registry.cancel_owner(job.owner_id)
        with pytest.raises(ProcessingCancelled):
            control.checkpoint()
        with pytest.raises(ProcessingCancelled), registry.track(job):
            pytest.fail("The current process must still reject revoked work")
    monkeypatch.setattr(registry, "_persist_cancellation", persist)
    assert registry.cancel_owner(job.owner_id) is True
    registry.close()
    replacement = ExecutionRegistry(tmp_path)
    with pytest.raises(ProcessingCancelled), replacement.track(job):
        pytest.fail("The retried cancellation must persist")
    replacement.close()


def test_cancellation_during_segmentation_prevents_all_derivative_writes(tmp_path):
    entered, resume = threading.Event(), threading.Event()

    class SlowSegmentation(TestSegmentation):
        def mask(self, image):
            entered.set()
            assert resume.wait(5)
            return super().mask(image)

    data = photograph()
    job = job_for(data)
    storage = MemoryStorage()
    storage.objects[job.source_key] = data
    registry = ExecutionRegistry(tmp_path)
    pipeline = Pipeline(storage, SlowSegmentation(), NoAnalysis())

    def process():
        with registry.track(job) as control:
            return pipeline.process(job, control)

    try:
        with ThreadPoolExecutor(max_workers=1) as executor:
            task = executor.submit(process)
            assert entered.wait(5)
            assert registry.cancel_owner(job.owner_id) is False
            resume.set()
            with pytest.raises(ProcessingCancelled):
                task.result(timeout=5)
        assert registry.cancel_owner(job.owner_id) is True
        assert storage.writes == []
        assert storage.objects[job.source_key] == data
    finally:
        resume.set()
        registry.close()


def test_cancellation_during_analysis_cannot_be_downgraded_to_a_successful_manifest():
    control = Cancellation(threading.Event())

    class CancelledAnalysis:
        def analyse(self, job, result, storage):
            control.event.set()
            raise ProcessingCancelled("Cancelled while analysing")

    data = photograph()
    job = job_for(data)
    storage = MemoryStorage()
    storage.objects[job.source_key] = data
    with pytest.raises(ProcessingCancelled):
        Pipeline(storage, TestSegmentation(), CancelledAnalysis()).process(job, control)
    assert job.output_prefix + "manifest.json" not in storage.objects
    assert job.output_prefix + "analysis.json" not in storage.objects


def test_a_put_already_in_progress_keeps_cancellation_pending_until_the_call_returns(tmp_path):
    entered, resume = threading.Event(), threading.Event()

    class SlowStorage(MemoryStorage):
        def write(self, key, data, mime_type):
            entered.set()
            assert resume.wait(5)
            super().write(key, data, mime_type)

    job = job_for(photograph())
    storage = SlowStorage()
    registry = ExecutionRegistry(tmp_path)

    def write():
        with registry.track(job) as control:
            CancellableStorage(storage, control).write(
                job.output_prefix + "display.webp", b"image", "image/webp"
            )

    try:
        with ThreadPoolExecutor(max_workers=1) as executor:
            task = executor.submit(write)
            assert entered.wait(5)
            assert registry.cancel_owner(job.owner_id) is False
            resume.set()
            with pytest.raises(ProcessingCancelled):
                task.result(timeout=5)
        assert registry.cancel_owner(job.owner_id) is True
        assert len(storage.writes) == 1
    finally:
        resume.set()
        registry.close()
