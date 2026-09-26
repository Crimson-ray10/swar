"""Fetch pinned open-source runtime/VAD build inputs. Never called by the Android app."""
import hashlib
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ASSETS = [
    ("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar",
     "mobile/offline/libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar",
     "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"),
    ("https://raw.githubusercontent.com/snakers4/silero-vad/v5.1.2/src/silero_vad/data/silero_vad.onnx",
     "mobile/offline/src/main/assets/silero_vad.onnx",
     "2623a2953f6ff3d2c1e61740c6cdb7168133479b267dfef114a4a3cc5bdd788f"),
]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    for url, relative, expected in ASSETS:
        destination = ROOT / relative
        if destination.is_file() and digest(destination) == expected:
            print(f"Verified {relative}")
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(destination.suffix + ".part")
        try:
            with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as stream:
                while chunk := response.read(1024 * 1024):
                    stream.write(chunk)
            if digest(temporary) != expected:
                raise ValueError(f"Checksum mismatch: {relative}")
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)
        print(f"Prepared {relative}")


if __name__ == "__main__":
    main()
