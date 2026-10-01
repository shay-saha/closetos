import fcntl
import hashlib
import os
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from uuid import UUID

from closetos_media.models import WorkflowJob
from closetos_media.storage import StoragePort


class ProcessingCancelled(RuntimeError):
    pass


class ProcessingBusy(RuntimeError):
    pass


@dataclass(frozen=True)
class Cancellation:
    event: threading.Event

    def checkpoint(self) -> None:
        if self.event.is_set():
            raise ProcessingCancelled("Processing was cancelled")


class ExecutionRegistry:
    def __init__(self, directory: Path):
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.directory = directory
        self._lock = threading.RLock()
        self._blocked: set[UUID] = set()
        self._active: dict[UUID, tuple[UUID, Cancellation]] = {}
        self._lease = (directory / "worker.lock").open("a+b")
        try:
            # All workers sharing these cancellation markers must use this single registry.
            fcntl.flock(self._lease.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self._lease.close()
            raise RuntimeError("Another media worker is using this state directory") from None

    def _marker(self, owner: UUID) -> Path:
        return self.directory / hashlib.sha256(str(owner).encode("ascii")).hexdigest()

    def _revoked(self, owner: UUID) -> bool:
        if owner in self._blocked:
            return True
        try:
            self._marker(owner).stat()
            return True
        except FileNotFoundError:
            return False

    def _persist_cancellation(self, owner: UUID) -> None:
        try:
            descriptor = os.open(self._marker(owner), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            descriptor = os.open(self._marker(owner), os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        descriptor = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    @contextmanager
    def track(self, job: WorkflowJob) -> Iterator[Cancellation]:
        owner = job.owner_id
        with self._lock:
            if self._revoked(owner):
                raise ProcessingCancelled("Processing was cancelled")
            if job.job_id in self._active:
                raise ProcessingBusy("This job is already running")
            cancellation = Cancellation(threading.Event())
            self._active[job.job_id] = (owner, cancellation)
        try:
            yield cancellation
        finally:
            with self._lock:
                self._active.pop(job.job_id)

    def cancel_owner(self, owner: UUID) -> bool:
        with self._lock:
            self._blocked.add(owner)
            active = [control for user, control in self._active.values() if user == owner]
            for control in active:
                control.event.set()
            self._persist_cancellation(owner)
            return not active

    def close(self) -> None:
        with self._lock:
            if self._active:
                raise RuntimeError("Media jobs must finish before releasing their registry")
            self._lease.close()


class CancellableStorage:
    def __init__(self, storage: StoragePort, cancellation: Cancellation):
        self.storage = storage
        self.cancellation = cancellation

    def read(self, key: str, limit: int) -> bytes:
        self.cancellation.checkpoint()
        result = self.storage.read(key, limit)
        self.cancellation.checkpoint()
        return result

    def write(self, key: str, data: bytes, mime_type: str) -> None:
        self.cancellation.checkpoint()
        self.storage.write(key, data, mime_type)
        self.cancellation.checkpoint()

    def optional_json(self, key: str) -> dict | None:
        self.cancellation.checkpoint()
        result = self.storage.optional_json(key)
        self.cancellation.checkpoint()
        return result
