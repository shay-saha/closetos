import os
from functools import cached_property
from typing import Protocol

from PIL import Image


class GarmentSegmentationPort(Protocol):
    model_name: str

    def mask(self, image: Image.Image) -> Image.Image: ...


class BiRefNetSegmentation:
    model_name = "birefnet-general-lite"

    @cached_property
    def session(self):
        import onnxruntime as ort
        from rembg import new_session

        options = ort.SessionOptions()
        # Release intermediate allocations instead of retaining a growing CPU arena.
        options.enable_cpu_mem_arena = False
        options.enable_mem_pattern = False
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        return new_session(
            self.model_name,
            providers=["CPUExecutionProvider"],
            sess_opts=options,
        )

    def mask(self, image: Image.Image) -> Image.Image:
        masks = self.session.predict(image.convert("RGB"))
        if len(masks) != 1:
            raise ValueError("Segmentation did not return a single garment mask")
        return masks[0].convert("L").resize(image.size, Image.Resampling.LANCZOS)


def download_model():
    os.environ.setdefault("OMP_NUM_THREADS", "2")
    return BiRefNetSegmentation().session
