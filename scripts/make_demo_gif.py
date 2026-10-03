#!/usr/bin/env python3
"""Record docs/images/showcase.gif by driving examples/showcase through the stdio protocol.

Requires a built jar (scripts/sbtw harnessApp/assembly) and Pillow.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT / "examples" / "showcase"
ARTIFACTS = PROJECT / ".ocelot-harness" / "artifacts"
OUTPUT = ROOT / "docs" / "images" / "showcase.gif"
FRAMES = "demo-frames"
SCREEN = "main-screen"
FRAME_MS = 80


class Harness:
    def __init__(self) -> None:
        launcher = ROOT / "scripts" / ("ocelot-harnessd.cmd" if os.name == "nt" else "ocelot-harnessd")
        self.log = tempfile.TemporaryFile()
        self.process = subprocess.Popen(
            [str(launcher), "serve", "--stdio", "--project", str(PROJECT)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=self.log,
            text=True,
            encoding="utf-8",
        )
        self.next_id = 0

    def call(self, method: str, **params):
        self.next_id += 1
        request = {"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params}
        self.process.stdin.write(json.dumps(request) + "\n")
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            self.log.seek(0)
            raise RuntimeError("daemon exited:\n" + self.log.read().decode(errors="replace"))
        response = json.loads(line)
        if "error" in response:
            raise RuntimeError(f"{method} failed: {response['error']}")
        return response["result"]

    def close(self) -> None:
        try:
            if self.process.poll() is None:
                self.call("service.shutdown")
            self.process.wait(timeout=30)
        except Exception:
            self.process.kill()
        finally:
            self.log.close()


def main() -> int:
    harness = Harness()
    frames: list[Path] = []
    try:
        harness.call("harness.version", protocolMajor=1)
        harness.call("simulation.pause")
        harness.call("machine.start", computerId="main")

        def step(ticks: int) -> None:
            harness.call("simulation.step", count=ticks)

        def capture() -> None:
            result = harness.call(
                "screen.capture", screenId=SCREEN, format="png", path=f"{FRAMES}/{len(frames):03d}.png"
            )
            frames.append(ARTIFACTS / result["relativePath"])

        def touch(x: int, y: int) -> None:
            harness.call("screen.input", screenId=SCREEN, input={"type": "touch", "x": x, "y": y, "button": 0})

        harness.call(
            "simulation.run",
            condition={"type": "screen_contains", "screenId": SCREEN, "text": "boot ok"},
            maxTicks=2000,
            timeoutMillis=60000,
        )
        for _ in range(25):
            step(1)
            capture()
        for char in "hello from ocelotctl!":
            harness.call("screen.input", screenId=SCREEN, input={"type": "type_text", "text": char})
            step(1)
            capture()
        for x, y in ((20, 6), (55, 9), (70, 4)):
            touch(x, y)
            for _ in range(4):
                step(1)
                capture()
        for _ in range(20):
            step(1)
            capture()

        images = [Image.open(frame).convert("RGB") for frame in frames]
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        images[0].save(
            OUTPUT,
            save_all=True,
            append_images=images[1:],
            duration=FRAME_MS,
            loop=0,
            optimize=True,
        )
        print(f"wrote {OUTPUT.relative_to(ROOT)} ({len(images)} frames, {OUTPUT.stat().st_size // 1024} KiB)")
        return 0
    finally:
        harness.close()
        shutil.rmtree(ARTIFACTS / FRAMES, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
