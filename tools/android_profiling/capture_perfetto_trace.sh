#!/usr/bin/env bash
set -euo pipefail

PACKAGE="org.ncssar.rid2caltopo"
ACTIVITY=".app.R2CActivity"
DURATION_SEC=20
READY_DELAY_SEC=5
OUT_DIR="/Users/kjt/Projects/RID2Caltopo/tools/android_profiling/out"
TRACE_NAME=""
SERIAL=""
LAUNCH_APP=0
INTERACTIVE=0

usage() {
  cat <<'EOF'
Usage: capture_perfetto_trace.sh [options]

Captures a Perfetto trace plus focused RID2Caltopo logcat and device snapshots.

Options:
  --serial <serial>         adb device serial. Defaults to the only physical device.
  --duration <seconds>      Trace duration in seconds. Default: 20
  --ready-delay <seconds>   Delay before capture starts. Default: 5
  --interactive             Press Enter to start trace, then Enter again to stop.
  --trace-name <name>       Output basename. Default: rid2c_runtime_YYYYmmdd_HHMMSS
  --out-dir <path>          Host output directory.
  --launch-app              Launch org.ncssar.rid2caltopo before capture.
  -h, --help                Show this help.
EOF
}

pick_default_serial() {
  local devices
  mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/{print $1}')
  if [[ "${#devices[@]}" -eq 1 ]]; then
    printf '%s\n' "${devices[0]}"
    return 0
  fi
  echo "Unable to auto-select a physical device. Pass --serial." >&2
  adb devices -l >&2 || true
  return 1
}

run_adb() {
  if [[ -n "$SERIAL" ]]; then
    adb -s "$SERIAL" "$@"
  else
    adb "$@"
  fi
}

write_perfetto_config() {
  local dest="$1"
  local duration_ms="$2"
  local package="$3"
  cat >"$dest" <<EOF
buffers: {
  size_kb: 65536
  fill_policy: RING_BUFFER
}
buffers: {
  size_kb: 16384
  fill_policy: RING_BUFFER
}

data_sources: {
  config {
    name: "linux.process_stats"
    target_buffer: 1
    process_stats_config {
      scan_all_processes_on_start: true
      proc_stats_poll_ms: 1000
    }
  }
}

data_sources: {
  config {
    name: "linux.sys_stats"
    target_buffer: 1
    sys_stats_config {
      stat_period_ms: 1000
      meminfo_period_ms: 1000
      vmstat_period_ms: 1000
      stat_counters: STAT_CPU_TIMES
      stat_counters: STAT_FORK_COUNT
      meminfo_counters: MEMINFO_MEM_TOTAL
      meminfo_counters: MEMINFO_MEM_FREE
      meminfo_counters: MEMINFO_MEM_AVAILABLE
      meminfo_counters: MEMINFO_BUFFERS
      meminfo_counters: MEMINFO_CACHED
      meminfo_counters: MEMINFO_SWAP_FREE
      vmstat_counters: VMSTAT_PGFAULT
      vmstat_counters: VMSTAT_PGMAJFAULT
    }
  }
}

data_sources: {
  config {
    name: "linux.ftrace"
    ftrace_config {
      atrace_apps: "${package}"
      atrace_categories: "am"
      atrace_categories: "binder_driver"
      atrace_categories: "dalvik"
      atrace_categories: "freq"
      atrace_categories: "gfx"
      atrace_categories: "hal"
      atrace_categories: "idle"
      atrace_categories: "input"
      atrace_categories: "res"
      atrace_categories: "sched"
      atrace_categories: "view"
      atrace_categories: "wm"
      ftrace_events: "binder/binder_transaction"
      ftrace_events: "binder/binder_transaction_received"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_wakeup"
      ftrace_events: "sched/sched_wakeup_new"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "task/task_newtask"
      ftrace_events: "task/task_rename"
      symbolize_ksyms: false
      compact_sched {
        enabled: true
      }
    }
  }
}
EOF
  if (( duration_ms > 0 )); then
    printf '\nduration_ms: %s\n' "$duration_ms" >>"$dest"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      SERIAL="${2:?missing serial}"
      shift 2
      ;;
    --duration)
      DURATION_SEC="${2:?missing duration}"
      shift 2
      ;;
    --ready-delay)
      READY_DELAY_SEC="${2:?missing ready delay}"
      shift 2
      ;;
    --interactive)
      INTERACTIVE=1
      shift
      ;;
    --trace-name)
      TRACE_NAME="${2:?missing trace name}"
      shift 2
      ;;
    --out-dir)
      OUT_DIR="${2:?missing output dir}"
      shift 2
      ;;
    --launch-app)
      LAUNCH_APP=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found in PATH" >&2
  exit 1
fi

if [[ -z "$SERIAL" ]]; then
  SERIAL="$(pick_default_serial)"
fi

if ! [[ "$DURATION_SEC" =~ ^[0-9]+$ ]] || (( DURATION_SEC <= 0 )); then
  echo "--duration must be a positive integer number of seconds" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
timestamp="$(date +%Y%m%d_%H%M%S)"
trace_base="${TRACE_NAME:-rid2c_runtime_${timestamp}}"
host_trace="${OUT_DIR}/${trace_base}.perfetto-trace"
host_logcat="${OUT_DIR}/${trace_base}.logcat.txt"
host_pre_status="${OUT_DIR}/${trace_base}.process_status.before.txt"
host_post_status="${OUT_DIR}/${trace_base}.process_status.after.txt"
host_pre_meminfo="${OUT_DIR}/${trace_base}.meminfo.before.txt"
host_post_meminfo="${OUT_DIR}/${trace_base}.meminfo.after.txt"
host_pre_gfx="${OUT_DIR}/${trace_base}.gfxinfo.before.txt"
host_post_gfx="${OUT_DIR}/${trace_base}.gfxinfo.after.txt"
host_thermal="${OUT_DIR}/${trace_base}.thermal.txt"
host_perfetto_stdout="${OUT_DIR}/${trace_base}.perfetto_stdout.txt"
device_trace="/data/misc/perfetto-traces/${trace_base}.perfetto-trace"
config_file="$(mktemp "/tmp/${trace_base}.XXXXXX.pbtx")"
logcat_pid=""
device_perfetto_pid=""

cleanup() {
  if [[ -n "$device_perfetto_pid" ]]; then
    run_adb shell kill -TERM "$device_perfetto_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$logcat_pid" ]]; then
    kill "$logcat_pid" >/dev/null 2>&1 || true
    wait "$logcat_pid" 2>/dev/null || true
  fi
  rm -f "$config_file"
}
trap cleanup EXIT

prompt_for_enter() {
  local message="$1"
  echo "$message"
  read -r </dev/tty
}

drain_pending_tty_input() {
  local ignored
  while read -r -t 1 ignored </dev/tty; do
    :
  done
}

wait_for_device_perfetto_exit() {
  local pid="$1"
  local attempt
  for attempt in {1..30}; do
    if ! run_adb shell kill -0 "$pid" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for device Perfetto PID ${pid} to exit" >&2
  return 1
}

wait_for_device_trace_ready() {
  local trace_path="$1"
  local attempt
  local size
  local previous_size=""
  for attempt in {1..30}; do
    size="$(run_adb shell stat -c %s "$trace_path" 2>/dev/null | tr -d '\r' || true)"
    if [[ "$size" =~ ^[0-9]+$ ]] && (( size > 0 )); then
      if [[ "$size" == "$previous_size" ]]; then
        echo "Device trace is ready: ${size} bytes."
        return 0
      fi
      previous_size="$size"
    fi
    sleep 1
  done
  echo "Timed out waiting for non-empty device trace at ${trace_path}" >&2
  run_adb shell ls -l "$trace_path" >&2 || true
  return 1
}

if (( INTERACTIVE )); then
  if [[ ! -r /dev/tty ]]; then
    echo "--interactive requires a terminal for Enter prompts" >&2
    exit 1
  fi
  write_perfetto_config "$config_file" 0 "$PACKAGE"
else
  write_perfetto_config "$config_file" "$((DURATION_SEC * 1000))" "$PACKAGE"
fi

echo "== Device =="
run_adb devices -l
echo

echo "== Perfetto =="
run_adb shell perfetto --version
echo

echo "== Package =="
if ! run_adb shell pm list packages "$PACKAGE" | grep -q "package:${PACKAGE}"; then
  echo "Package ${PACKAGE} is not installed on ${SERIAL}" >&2
  exit 1
fi
run_adb shell pidof "$PACKAGE" || true
echo

ANDROID_SERIAL="$SERIAL" /Users/kjt/Projects/RID2Caltopo/tools/android_process_status.sh "$PACKAGE" >"$host_pre_status" 2>&1 || true
run_adb shell dumpsys meminfo "$PACKAGE" >"$host_pre_meminfo" 2>&1 || true
run_adb shell dumpsys gfxinfo "$PACKAGE" framestats >"$host_pre_gfx" 2>&1 || true
run_adb shell dumpsys thermalservice >"$host_thermal" 2>&1 || true
run_adb logcat -c || true
run_adb shell rm -f "$device_trace" >/dev/null 2>&1 || true

run_adb logcat -v threadtime \
  FfmpegProbeService:D \
  FfmpegBridge:D \
  ffmpeg_bridge:D \
  '*:S' >"$host_logcat" 2>&1 &
logcat_pid="$!"

if (( LAUNCH_APP )); then
  echo "== Launching RID2Caltopo =="
  run_adb shell am start -W -n "${PACKAGE}/${ACTIVITY}" || true
  echo
fi

echo "Prepare the tablet now:"
echo "1. Open PowerHouseTeam.mp4 in RID2Caltopo captured-video playback."
echo "2. Set the anomaly settings you want to profile."
echo "3. Let playback run through the unstable window during the trace."
echo
if (( INTERACTIVE )); then
  prompt_for_enter "Press Enter to start the Perfetto trace."
  set +e
  perfetto_start_output="$(run_adb shell perfetto --background-wait --txt -c - -o "$device_trace" <"$config_file" 2>&1)"
  perfetto_start_status="$?"
  set -e
  printf '%s\n' "$perfetto_start_output" >"$host_perfetto_stdout"
  if (( perfetto_start_status != 0 )); then
    echo "Unable to start background Perfetto trace:" >&2
    printf '%s\n' "$perfetto_start_output" >&2
    exit "$perfetto_start_status"
  fi
  device_perfetto_pid="$(printf '%s\n' "$perfetto_start_output" | awk '/^[0-9]+$/ {print $1; exit}')"
  if [[ -z "$device_perfetto_pid" ]]; then
    echo "Unable to determine background Perfetto PID from:" >&2
    printf '%s\n' "$perfetto_start_output" >&2
    exit 1
  fi
  trace_started_at="$(date +%s)"
  drain_pending_tty_input
  prompt_for_enter "Trace is running. Press Enter to stop the Perfetto trace."
  trace_stopped_at="$(date +%s)"
  run_adb shell kill -TERM "$device_perfetto_pid" || true
  wait_for_device_perfetto_exit "$device_perfetto_pid"
  device_perfetto_pid=""
  echo "Trace stop requested after $((trace_stopped_at - trace_started_at))s."
else
  echo "Trace starts in ${READY_DELAY_SEC}s and runs for ${DURATION_SEC}s..."
  sleep "$READY_DELAY_SEC"
  run_adb shell perfetto --txt -c - -o "$device_trace" <"$config_file" >"$host_perfetto_stdout" 2>&1
fi
wait_for_device_trace_ready "$device_trace"
run_adb pull "$device_trace" "$host_trace" >/dev/null
run_adb shell rm -f "$device_trace" >/dev/null 2>&1 || true

sleep 1
ANDROID_SERIAL="$SERIAL" /Users/kjt/Projects/RID2Caltopo/tools/android_process_status.sh "$PACKAGE" >"$host_post_status" 2>&1 || true
run_adb shell dumpsys meminfo "$PACKAGE" >"$host_post_meminfo" 2>&1 || true
run_adb shell dumpsys gfxinfo "$PACKAGE" framestats >"$host_post_gfx" 2>&1 || true

echo
echo "Trace capture complete."
echo "Trace:   $host_trace"
echo "Logcat:  $host_logcat"
echo "Status:  $host_pre_status"
echo "         $host_post_status"
echo "Meminfo: $host_pre_meminfo"
echo "         $host_post_meminfo"
echo "Gfxinfo: $host_pre_gfx"
echo "         $host_post_gfx"
echo "Thermal: $host_thermal"
echo
echo "Open the trace in https://ui.perfetto.dev/ and search for 'RID2C'."
