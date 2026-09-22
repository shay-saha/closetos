import hashlib
import os
import tempfile
import urllib.request

from closetos_media.embeddings import CLIP_FILES, CLIP_MODEL, CLIP_REVISION, clip_home


def download_embedding_models():
    directory = clip_home()
    directory.mkdir(parents=True, exist_ok=True)
    for name, expected in CLIP_FILES.items():
        destination = directory / name
        if destination.is_file():
            with destination.open("rb") as source:
                if hashlib.file_digest(source, "sha256").hexdigest() == expected:
                    continue
        remote = "onnx/" + name if name.endswith(".onnx") else name
        url = f"https://huggingface.co/{CLIP_MODEL}/resolve/{CLIP_REVISION}/{remote}"
        fd, temporary = tempfile.mkstemp(dir=directory)
        try:
            with os.fdopen(fd, "wb") as target, urllib.request.urlopen(url, timeout=60) as source:
                digest = hashlib.sha256()
                while block := source.read(1024 * 1024):
                    digest.update(block)
                    target.write(block)
            if digest.hexdigest() != expected:
                raise ValueError("Embedding model failed checksum verification")
            os.replace(temporary, destination)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
