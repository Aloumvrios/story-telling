"""
Image generation service — Stable Diffusion XL on Apple Silicon (MPS) via diffusers.

POST /generate  (json: { "prompt": "...", "negative_prompt": "..." })
  -> image/png bytes

Alternative backend: point this at a running ComfyUI instance instead of loading
the pipeline in-process. See README for the ComfyUI option.
"""
import io
import os

from fastapi import FastAPI
from fastapi.responses import Response
from pydantic import BaseModel

app = FastAPI(title="imagegen-service")

_pipe = None

MODEL_ID = os.environ.get("SDXL_MODEL", "stabilityai/stable-diffusion-xl-base-1.0")
STEPS = int(os.environ.get("SDXL_STEPS", "30"))
WIDTH = int(os.environ.get("SDXL_WIDTH", "1024"))
HEIGHT = int(os.environ.get("SDXL_HEIGHT", "1024"))
# Precision. fp16 is fast but on Apple Silicon (MPS) it is numerically unstable
# for SDXL and frequently produces all-black (NaN) images. Default to fp32 there
# for correctness; override with SDXL_DTYPE=float16 if your setup is stable.
DTYPE = os.environ.get("SDXL_DTYPE", "").strip().lower()


class GenRequest(BaseModel):
    prompt: str
    negative_prompt: str = ""


def _get_pipe():
    global _pipe
    if _pipe is None:
        import torch
        from diffusers import StableDiffusionXLPipeline

        device = "mps" if torch.backends.mps.is_available() else "cpu"
        # Default: fp32 on MPS (avoids black/NaN images), fp16 elsewhere.
        dtype_name = DTYPE or ("float32" if device == "mps" else "float16")
        torch_dtype = torch.float32 if dtype_name == "float32" else torch.float16

        # variant="fp16" selects the fp16 weight files (the only ones we have
        # locally); diffusers upcasts them to torch_dtype in memory.
        _pipe = StableDiffusionXLPipeline.from_pretrained(
            MODEL_ID, torch_dtype=torch_dtype, variant="fp16", use_safetensors=True
        ).to(device)
        _pipe.enable_attention_slicing()
        _pipe.enable_vae_tiling()  # keep VAE memory bounded at 1024x1024
        if torch_dtype == torch.float16:
            # SDXL's VAE overflows to NaN in fp16 during decode -> run it in fp32.
            _pipe.upcast_vae()
    return _pipe


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/generate")
def generate(req: GenRequest):
    pipe = _get_pipe()
    image = pipe(
        prompt=req.prompt,
        negative_prompt=req.negative_prompt,
        num_inference_steps=STEPS,
        width=WIDTH,
        height=HEIGHT,
    ).images[0]

    buf = io.BytesIO()
    image.save(buf, format="PNG")
    return Response(content=buf.getvalue(), media_type="image/png")



