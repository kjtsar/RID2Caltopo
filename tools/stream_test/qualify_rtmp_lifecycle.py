#!/usr/bin/env python3
"""Qualify repeated RTMP publisher stop/restart rendering on a connected device."""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from collections.abc import Sequence
from pathlib import Path


APP_PACKAGE = "org.ncssar.rid2caltopo"
APP_ACTIVITY = f"{APP_PACKAGE}/.app.R2CActivity"
QUALIFICATION_EXTRA = f"{APP_PACKAGE}.extra.OPEN_STREAMS_QUALIFICATION"
DEVICE_RTMP_PORT = 1935
DEFAULT_DESIGNATOR = "RTMPQUAL1"

RENDER_START_RE = re.compile(r"Starting FFmpeg render for (?P<designator>\S+)\b")
PUBLISHING_RE = re.compile(r"is publishing to path '(?P<designator>[^']+)'")
DECODER_OPENED_RE = re.compile(
    r"Session lifecycle designator=(?P<designator>\S+).*\bevent=decoder_opened\b"
)
SURFACE_UPDATE_RE = re.compile(
    r"SurfaceTexture updated for (?P<designator>\S+) "
    r"textureId=(?P<texture_id>\d+) count=(?P<count>\d+)"
)


class QualificationFailure(RuntimeError):
    """A failure with a message intended to be acted on by an operator."""


@dataclasses.dataclass(frozen=True)
class SurfaceUpdate:
    texture_id: int
    count: int
    line_number: int


@dataclasses.dataclass(frozen=True)
class CycleEvidence:
    designator: str
    publishing_lines: tuple[int, ...]
    render_start_lines: tuple[int, ...]
    decoder_opened_lines: tuple[int, ...]
    surface_updates: tuple[SurfaceUpdate, ...]

    @property
    def advancing_texture(self) -> tuple[int, int, int] | None:
        """Return texture id, minimum count, and maximum count when frames advance."""
        return _find_advancing_texture(self.surface_updates)

    @property
    def staged_chain(self) -> tuple[int, int, int, int, int, int] | None:
        """Return ordered stage lines plus advancing texture/count evidence."""
        for publishing_line in self.publishing_lines:
            for render_line in self.render_start_lines:
                if render_line <= publishing_line:
                    continue
                for decoder_line in self.decoder_opened_lines:
                    if decoder_line <= render_line:
                        continue
                    updates = tuple(
                        update
                        for update in self.surface_updates
                        if update.line_number > decoder_line
                    )
                    advancing = _find_advancing_texture(updates)
                    if advancing is not None:
                        texture_id, first_count, last_count = advancing
                        return (
                            publishing_line,
                            render_line,
                            decoder_line,
                            texture_id,
                            first_count,
                            last_count,
                        )
        return None

    @property
    def passed(self) -> bool:
        return self.staged_chain is not None

    def failure_reasons(self) -> tuple[str, ...]:
        reasons: list[str] = []
        if not self.publishing_lines:
            reasons.append(
                f"no MediaMTX 'is publishing to path {self.designator!r}' event"
            )
        if not self.render_start_lines:
            reasons.append(
                f"no app-side 'Starting FFmpeg render for {self.designator}' event"
            )
        if not self.decoder_opened_lines:
            reasons.append(
                f"no 'designator={self.designator} ... event=decoder_opened' event"
            )
        if not self.surface_updates:
            reasons.append(
                f"no SurfaceTexture updates for {self.designator}; keep its Streams tile visible"
            )
        elif self.advancing_texture is None:
            samples = ", ".join(
                f"texture {item.texture_id} count {item.count}"
                for item in self.surface_updates
            )
            reasons.append(
                "SurfaceTexture evidence did not advance twice on one texture "
                f"(observed: {samples})"
            )
        if not reasons and self.staged_chain is None:
            reasons.append(
                "all stages appeared, but not in required publish -> render start -> "
                "decoder opened -> advancing frames order"
            )
        return tuple(reasons)


def _find_advancing_texture(
    updates: Sequence[SurfaceUpdate],
) -> tuple[int, int, int] | None:
    counts_by_texture: dict[int, list[int]] = {}
    for update in updates:
        counts_by_texture.setdefault(update.texture_id, []).append(update.count)
    for texture_id, counts in counts_by_texture.items():
        if len(counts) >= 2 and max(counts) > min(counts):
            return texture_id, min(counts), max(counts)
    return None


def qualify_log_lines(lines: Sequence[str], designator: str) -> CycleEvidence:
    """Extract render-start and advancing-frame evidence for one cycle."""
    publishing: list[int] = []
    render_starts: list[int] = []
    decoder_opened: list[int] = []
    updates: list[SurfaceUpdate] = []
    for line_number, line in enumerate(lines, start=1):
        publishing_match = PUBLISHING_RE.search(line)
        if publishing_match and publishing_match.group("designator") == designator:
            publishing.append(line_number)

        render_match = RENDER_START_RE.search(line)
        if render_match and render_match.group("designator") == designator:
            render_starts.append(line_number)

        decoder_match = DECODER_OPENED_RE.search(line)
        if decoder_match and decoder_match.group("designator") == designator:
            decoder_opened.append(line_number)

        update_match = SURFACE_UPDATE_RE.search(line)
        if update_match and update_match.group("designator") == designator:
            updates.append(
                SurfaceUpdate(
                    texture_id=int(update_match.group("texture_id")),
                    count=int(update_match.group("count")),
                    line_number=line_number,
                )
            )
    return CycleEvidence(
        designator,
        tuple(publishing),
        tuple(render_starts),
        tuple(decoder_opened),
        tuple(updates),
    )


def _run(
    command: Sequence[str],
    purpose: str,
    *,
    timeout: float = 15,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise QualificationFailure(
            f"Timed out while {purpose}: {' '.join(command)}"
        ) from exc
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout).strip() or "no diagnostic output"
        raise QualificationFailure(f"Failed while {purpose}: {detail}")
    return result


def _require_tool(name: str) -> str:
    path = shutil.which(name)
    if path is None:
        raise QualificationFailure(
            f"Required tool '{name}' was not found on PATH. Install it and rerun."
        )
    return path


def _select_device(adb: str, requested_serial: str | None) -> str:
    result = _run([adb, "devices"], "listing adb devices")
    states: dict[str, str] = {}
    for line in result.stdout.splitlines()[1:]:
        fields = line.split()
        if len(fields) >= 2:
            states[fields[0]] = fields[1]

    if requested_serial:
        state = states.get(requested_serial)
        if state != "device":
            observed = state or "not listed"
            raise QualificationFailure(
                f"adb device {requested_serial!r} is {observed}. Unlock/authorize it, "
                "check 'adb devices', then rerun with the same --serial."
            )
        return requested_serial

    ready = [serial for serial, state in states.items() if state == "device"]
    if not ready:
        raise QualificationFailure(
            "No authorized adb device is connected. Connect and unlock the tablet, "
            "accept its USB debugging prompt, and verify it appears in 'adb devices'."
        )
    if len(ready) > 1:
        raise QualificationFailure(
            "Multiple adb devices are connected. Choose one with --serial: "
            + ", ".join(ready)
        )
    return ready[0]


def _verify_ffmpeg(ffmpeg: str, encoder: str) -> None:
    _run([ffmpeg, "-version"], "checking ffmpeg")
    encoders = _run([ffmpeg, "-hide_banner", "-encoders"], "listing ffmpeg encoders")
    if not re.search(rf"^\s*V\S*\s+{re.escape(encoder)}\s", encoders.stdout, re.MULTILINE):
        raise QualificationFailure(
            f"ffmpeg does not provide the requested H.264 encoder {encoder!r}. "
            "Install an ffmpeg build with libx264 or pass --encoder with an available "
            "H.264 encoder from 'ffmpeg -encoders'."
        )


def _adb(adb: str, serial: str, *args: str) -> list[str]:
    return [adb, "-s", serial, *args]


def _verify_app_running(adb: str, serial: str) -> None:
    result = _run(
        _adb(adb, serial, "shell", "pidof", APP_PACKAGE),
        "checking whether RID2Caltopo is running",
        check=False,
    )
    if result.returncode != 0 or not result.stdout.strip():
        raise QualificationFailure(
            "RID2Caltopo is not running on the selected device. Start the app, open "
            "Live View with the Streams pane visible, and rerun the harness."
        )


def _launch_qualification_activity(adb: str, serial: str) -> None:
    result = _run(
        _adb(
            adb,
            serial,
            "shell",
            "am",
            "start",
            "-n",
            APP_ACTIVITY,
            "--ez",
            QUALIFICATION_EXTRA,
            "true",
        ),
        "launching the debug Streams qualification activity",
    )
    output = f"{result.stdout}\n{result.stderr}"
    if "Error:" in output:
        raise QualificationFailure(
            "Android rejected the Streams qualification launch hook: "
            f"{output.strip()}. Install a debug build that includes the hook."
        )
    _verify_app_running(adb, serial)


def _background_app(adb: str, serial: str) -> None:
    _run(
        _adb(adb, serial, "shell", "input", "keyevent", "HOME"),
        "sending the app to the background between qualification cycles",
    )


def _forward_rtmp_port(adb: str, serial: str, requested_port: int) -> int:
    local_spec = f"tcp:{requested_port}" if requested_port else "tcp:0"
    result = _run(
        _adb(adb, serial, "forward", local_spec, f"tcp:{DEVICE_RTMP_PORT}"),
        "forwarding a host port to device MediaMTX",
    )
    if requested_port:
        return requested_port
    output = result.stdout.strip()
    if not output.isdigit():
        raise QualificationFailure(
            "adb did not report the ephemeral forwarded port. Update platform-tools "
            "or rerun with an explicit unused --local-port."
        )
    return int(output)


class LogcatCollector:
    def __init__(self, command: Sequence[str], output_path: Path) -> None:
        self._command = list(command)
        self._output_path = output_path
        self._lines: list[str] = []
        self._lock = threading.Lock()
        self._process: subprocess.Popen[str] | None = None
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        self._output_path.parent.mkdir(parents=True, exist_ok=True)
        self._process = subprocess.Popen(
            self._command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            errors="replace",
        )
        self._thread = threading.Thread(target=self._read, daemon=True)
        self._thread.start()

    def _read(self) -> None:
        assert self._process is not None and self._process.stdout is not None
        with self._output_path.open("w", encoding="utf-8") as output:
            for line in self._process.stdout:
                output.write(line)
                output.flush()
                with self._lock:
                    self._lines.append(line.rstrip("\n"))

    def mark(self) -> int:
        with self._lock:
            return len(self._lines)

    def lines_since(self, mark: int) -> list[str]:
        with self._lock:
            return self._lines[mark:].copy()

    def assert_alive(self) -> None:
        if self._process is not None and self._process.poll() is not None:
            raise QualificationFailure(
                "adb logcat stopped unexpectedly. Check the USB connection and rerun; "
                f"partial log: {self._output_path}"
            )

    def stop(self) -> None:
        if self._process is not None and self._process.poll() is None:
            self._process.terminate()
            try:
                self._process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self._process.kill()
                self._process.wait(timeout=3)
        if self._thread is not None:
            self._thread.join(timeout=3)


def _publisher_command(
    ffmpeg: str,
    encoder: str,
    local_port: int,
    designator: str,
) -> list[str]:
    return [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "warning",
        "-re",
        "-f",
        "lavfi",
        "-i",
        "testsrc2=size=640x360:rate=15",
        "-an",
        "-c:v",
        encoder,
        "-pix_fmt",
        "yuv420p",
        "-g",
        "30",
        "-keyint_min",
        "30",
        "-sc_threshold",
        "0",
        "-f",
        "flv",
        f"rtmp://127.0.0.1:{local_port}/{designator}",
    ]


def _stop_publisher(process: subprocess.Popen[str]) -> str:
    if process.poll() is None:
        process.send_signal(signal.SIGINT)
        try:
            _, stderr = process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            _, stderr = process.communicate(timeout=3)
    else:
        _, stderr = process.communicate(timeout=3)
    return stderr.strip()


def _run_cycle(
    cycle: int,
    total_cycles: int,
    collector: LogcatCollector,
    publisher_command: Sequence[str],
    designator: str,
    timeout: float,
) -> CycleEvidence:
    mark = collector.mark()
    print(f"[{cycle}/{total_cycles}] Starting synthetic RTMP publisher for {designator}...")
    publisher = subprocess.Popen(
        publisher_command,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
        errors="replace",
    )
    deadline = time.monotonic() + timeout
    evidence = qualify_log_lines(collector.lines_since(mark), designator)
    publisher_error = ""
    try:
        while time.monotonic() < deadline:
            collector.assert_alive()
            evidence = qualify_log_lines(collector.lines_since(mark), designator)
            if evidence.passed:
                break
            if publisher.poll() is not None:
                publisher_error = _stop_publisher(publisher)
                raise QualificationFailure(
                    f"Cycle {cycle}: ffmpeg exited before render qualification "
                    f"(exit {publisher.returncode}). {publisher_error or 'No ffmpeg diagnostic.'} "
                    "Confirm MediaMTX is running in the app and the forwarded RTMP port is reachable."
                )
            time.sleep(0.25)
        else:
            reasons = "; ".join(evidence.failure_reasons())
            raise QualificationFailure(
                f"Cycle {cycle}: timed out after {timeout:.1f}s: {reasons}. "
                "Keep Live View on screen with the Streams tile visible and inspect the captured log."
            )
    finally:
        if publisher.poll() is None:
            publisher_error = _stop_publisher(publisher)

    chain = evidence.staged_chain
    assert chain is not None
    _, _, _, texture_id, first_count, last_count = chain
    print(
        f"[{cycle}/{total_cycles}] PASS publish -> render -> decoder; texture "
        f"{texture_id} advanced {first_count}->{last_count}. Publisher stopped."
    )
    return evidence


def _prepare_cycle_activity(
    adb: str,
    serial: str,
    cycle: int,
    restart_pause: float,
    activity_settle: float,
) -> None:
    if cycle > 1:
        print(f"[{cycle}] Sending HOME before the next app/publisher lifecycle cycle...")
        _background_app(adb, serial)
        if restart_pause:
            time.sleep(restart_pause)
    print(f"[{cycle}] Relaunching Streams through the debug qualification hook...")
    _launch_qualification_activity(adb, serial)
    if activity_settle:
        time.sleep(activity_settle)


def _default_log_path() -> Path:
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    return Path(tempfile.gettempdir()) / f"rid2c-rtmp-lifecycle-{stamp}.log"


def _parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Publish repeated synthetic H.264/FLV RTMP sessions to device MediaMTX "
            "and require app render/frame-advance evidence for every cycle."
        )
    )
    parser.add_argument("--serial", help="adb device serial (required when multiple devices are connected)")
    parser.add_argument("--cycles", type=int, default=3, help="publisher stop/restart cycles (default: 3)")
    parser.add_argument("--timeout", type=float, default=25.0, help="seconds allowed for each cycle (default: 25)")
    parser.add_argument("--restart-pause", type=float, default=3.0, help="seconds between cycles (default: 3)")
    parser.add_argument("--activity-settle", type=float, default=1.5, help="seconds to let the relaunched Streams UI settle before publishing (default: 1.5)")
    parser.add_argument("--designator", default=DEFAULT_DESIGNATOR, help=f"RTMP path/designator (default: {DEFAULT_DESIGNATOR})")
    parser.add_argument("--local-port", type=int, default=0, help="host forward port; 0 asks adb for a free port (default: 0)")
    parser.add_argument("--encoder", default="libx264", help="ffmpeg H.264 encoder (default: libx264)")
    parser.add_argument("--log-file", type=Path, default=None, help="captured logcat path (default: timestamped file in the system temp directory)")
    args = parser.parse_args(argv)
    if args.cycles < 1:
        parser.error("--cycles must be at least 1")
    if args.timeout <= 0:
        parser.error("--timeout must be greater than 0")
    if args.restart_pause < 0:
        parser.error("--restart-pause cannot be negative")
    if args.activity_settle < 0:
        parser.error("--activity-settle cannot be negative")
    if not 0 <= args.local_port <= 65535:
        parser.error("--local-port must be between 0 and 65535")
    if not args.designator or any(char.isspace() for char in args.designator):
        parser.error("--designator must be non-empty and contain no whitespace")
    return args


def main(argv: Sequence[str] | None = None) -> int:
    args = _parse_args(argv)
    collector: LogcatCollector | None = None
    adb = ""
    serial = ""
    local_port: int | None = None
    log_path = (args.log_file or _default_log_path()).expanduser().resolve()
    try:
        adb = _require_tool("adb")
        ffmpeg = _require_tool("ffmpeg")
        serial = _select_device(adb, args.serial)
        _verify_ffmpeg(ffmpeg, args.encoder)
        local_port = _forward_rtmp_port(adb, serial, args.local_port)
        print(f"Device: {serial}; RTMP forward: 127.0.0.1:{local_port} -> device:{DEVICE_RTMP_PORT}")
        print(f"Capturing logcat to {log_path}")

        collector = LogcatCollector(
            _adb(adb, serial, "logcat", "-v", "threadtime", "-T", "1"),
            log_path,
        )
        collector.start()
        time.sleep(0.5)
        collector.assert_alive()
        publisher_command = _publisher_command(
            ffmpeg, args.encoder, local_port, args.designator
        )
        for cycle in range(1, args.cycles + 1):
            _prepare_cycle_activity(
                adb,
                serial,
                cycle,
                args.restart_pause,
                args.activity_settle,
            )
            _run_cycle(
                cycle,
                args.cycles,
                collector,
                publisher_command,
                args.designator,
                args.timeout,
            )

        print(f"PASS: all {args.cycles} RTMP lifecycle cycles rendered advancing frames.")
        print(f"Logcat: {log_path}")
        return 0
    except QualificationFailure as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        print(f"Captured logcat (if started): {log_path}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Interrupted; stopping publisher/logcat and removing adb forwarding.", file=sys.stderr)
        return 130
    finally:
        if collector is not None:
            collector.stop()
        if adb and serial and local_port is not None:
            _run(
                _adb(adb, serial, "forward", "--remove", f"tcp:{local_port}"),
                "removing adb port forwarding",
                check=False,
            )


if __name__ == "__main__":
    raise SystemExit(main())
