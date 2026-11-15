#!/usr/bin/env bash
# End-to-end trial of the « Opération » mode (P39.12, ADR-134, docs/specs/group-hierarchy.md § 23): two devices, the
# real coordination server built into the APK, no screen. Used by the workflow « Opération : bout en bout »
# (.github/workflows/operation-e2e.yml, two emulators on one machine); on a workstation, with two emulators started:
#
#   bash tools/emulator/operation-e2e.sh <APK file or folder> <serial of A> <serial of B> [output folder]
#
# Installs the debug APK on both (only a build type with the testing tools answers), then drives the app of each one by
# `dumpsys activity fr.toolmap/.MainActivity operation-e2e …` (OperationEndToEnd), step by step:
#   1. A creates an operation and gives an invitation; B asks to come in; A sees the request with B's name (R5), lets
#      B in and places it in a group (leader of the group; A leader of the operation);
#   2. B sends positions (the fictitious place of the tests), A receives them;
#   3. a message in each thread each one may write in (the other gets those it reads, and no other), an alert each
#      (where its rank sends it), lifted; a waypoint shared, then taken away; a minute without sharing;
#   4. A's app killed and started again, catches up (B's waypoint, put meanwhile); B off the network 40 s (airplane
#      mode), catches up (the group renamed, a waypoint) when it comes back;
#   5. B leaves: gone at A, invitation key renewed (R3); B asks again with the old invitation: dropped, never shown;
#   6. B comes in again with a new invitation; off the network, A dissolves; B back; A's app started again tells it
#      by the trace (R1, ADR-131), whose moment in the room is then waited out.
# Each step has its expected result and its time; the latencies between the devices are corrected by the gap between
# their clocks. The server's calls, the bytes and the frames of each device are counted by the app (TrafficMeter).
# Fails at the first step that does not give what is expected, or when the app crashes; whatever happened, both apps
# leave the server's room (reset) and airplane mode is turned off.
#
# The job's log and its artifact are public: nothing here writes an invitation, a key, the room, a token or the
# server's address. The invitation goes from A to B through a variable only, masked for GitHub; the logs kept are
# filtered and scrubbed.
set -euo pipefail

apk="${1:?APK file or folder}"
A="${2:?serial of device A}"
B="${3:?serial of device B}"
out="${4:-operation-e2e}"
pkg="fr.toolmap"
tag="ToolMapOpE2E"
mkdir -p "$out"
steps="$out/steps.txt"
counters="$out/counters.txt"
: > "$steps"
: > "$counters"

if [ -d "$apk" ]; then
  apk="$(ls "$apk"/*.apk | head -n 1)"
fi

# Durations on the monotonic clock of the machine (/proc/uptime: the wall clock of a CI machine can step); the wall
# clock only where the devices' clocks are compared (measure_offset).
now_ms() { date +%s%3N; }
mono_ms() {
  local up
  read -r up _ < /proc/uptime
  echo $((10#${up/./} * 10))
}
role() { if [ "$1" = "$A" ]; then echo A; else echo B; fi; }
mask() { if [ "${GITHUB_ACTIONS:-}" = "true" ] && [ -n "$1" ]; then echo "::add-mask::$1"; fi; }

# The lines the app writes in its logcat and nothing else of it, scrubbed of anything that could be an address, a
# key, a token, a room or a member: what is kept in the artifact.
scrub() {
  local db_host="" key="${TOOLMAP_FIREBASE_API_KEY:-}"
  if [ -n "${TOOLMAP_FIREBASE_DB_URL:-}" ]; then db_host="$(echo "$TOOLMAP_FIREBASE_DB_URL" | sed -E 's#^https?://##; s#/.*$##')"; fi
  awk -v h="$db_host" -v k="$key" '{
      if (h != "") gsub(h, "<server>")
      if (k != "") gsub(k, "<key>")
      print
    }' |
    sed -E 's#room=[^ ]+#room=<room>#g; s#[a-z]+://[^ "]+#<address>#g; s#[A-Za-z0-9.-]+\.(firebasedatabase\.app|firebaseio\.com|googleapis\.com)#<host>#g;
      s#[0-9a-fA-F]{16,}#<hex>#g; s#AIza[0-9A-Za-z_-]{20,}#<key>#g; s#[A-Za-z0-9_+/=-]{40,}#<long>#g'
}

save_logs() {
  local dev r
  for dev in "$A" "$B"; do
    r="$(role "$dev")"
    adb -s "$dev" logcat -d 2>/dev/null |
      grep -E " ($tag|ToolMap|TMEvent|AndroidRuntime|DEBUG|libc) *:| >>> $pkg|ANR in $pkg|FATAL EXCEPTION" |
      grep -vE 'TMEvent *: .*relay\.(sse|hb\.in|frame\.in|kept\.in)' | scrub > "$out/logcat-$r.txt" || true
  done
}

crashed() {
  local dev="$1" log
  log="$(adb -s "$dev" logcat -d 2>/dev/null || true)"
  if grep -A1 'FATAL EXCEPTION' <<< "$log" | grep "Process: $pkg" > /dev/null; then echo "Java crash"; return 0; fi
  if grep ">>> $pkg" <<< "$log" > /dev/null; then echo "native crash"; return 0; fi
  if grep "ANR in $pkg" <<< "$log" > /dev/null; then echo "ANR"; return 0; fi
  return 1
}

# The counters of each device between two labels, as a table (tools/emulator/operation-e2e-measures.py).
summary() {
  [ -s "$counters" ] || return 0
  if command -v python3 > /dev/null 2>&1; then
    python3 -I "$(dirname "$0")/operation-e2e-measures.py" "$counters" > "$out/measures.txt" 2>&1 || true
    cat "$out/measures.txt"
  else
    echo "No python3: the counters are in $counters."
  fi
}

failed=""
finish() {
  local code=$?
  set +e
  for dev in "$A" "$B"; do adb -s "$dev" shell cmd connectivity airplane-mode disable > /dev/null 2>&1; done
  # Leave the room: the relay then deletes this member's node on the server.
  for dev in "$A" "$B"; do
    seq=$((seq + 1))
    adb -s "$dev" shell dumpsys activity "$pkg/.MainActivity" operation-e2e reset "t$seq" > /dev/null 2>&1
  done
  sleep 6
  save_logs
  summary
  if [ -n "$failed" ] || [ "$code" != 0 ]; then
    echo "FAILED: ${failed:-exit $code}" | tee "$out/result.txt"
    [ -n "${GITHUB_ACTIONS:-}" ] && echo "::error::« Opération » end to end: ${failed:-exit $code}"
    exit 1
  fi
}
trap finish EXIT

fail() {
  failed="$1"
  exit 1
}

# The app's answer to one command: the line it wrote after « operation-e2e ».
seq=0
ask() {
  local dev="$1"
  shift
  adb -s "$dev" shell dumpsys activity "$pkg/.MainActivity" operation-e2e "$@" 2>/dev/null | tr -d '\r' |
    grep -oE 'operation-e2e .*' | head -n 1 | sed 's/^operation-e2e //' || true
}

# Queues a command (start <device> <command> <arguments…>); sets token.
token=""
start() {
  local dev="$1" command="$2" answer
  shift 2
  seq=$((seq + 1))
  token="t$seq"
  answer="$(ask "$dev" "$command" "$token" "$@")"
  [[ "$answer" == "queued $token"* ]] || fail "$(role "$dev") did not take « $command »: ${answer:-no answer}"
}

# Waits for the answer of a queued command (collect <device> <token> <seconds>); sets result.
result=""
collect() {
  local dev="$1" tok="$2" limit="$3" line deadline
  deadline=$(($(date +%s) + limit + 15))
  result=""
  while [ "$(date +%s)" -le "$deadline" ]; do
    line="$(ask "$dev" result "$tok")"
    line="${line#result "$tok" }"
    if [ -n "$line" ] && [ "$line" != "pending" ] && [[ "$line" != result* ]]; then
      result="$line"
      return 0
    fi
    if [ -z "$line" ] && why="$(crashed "$dev")"; then fail "$(role "$dev"): $why of $pkg"; fi
    sleep 1
  done
  result="fail no-answer-within-${limit}s"
}

# One step: its name, who, the time the script waited, what the app answered.
record() {
  printf '%-22s %s %7s ms  %s\n' "$1" "$2" "$3" "$4" | tee -a "$steps"
}

# step <name> <device> <seconds> <command> <arguments…>: runs one command and waits for it; a failure stops the trial.
step() {
  local name="$1" dev="$2" limit="$3" t0
  shift 3
  t0="$(mono_ms)"
  start "$dev" "$@"
  collect "$dev" "$token" "$limit"
  record "$name" "$(role "$dev")" "$(($(mono_ms) - t0))" "$result"
  [[ "$result" == ok* ]] || fail "step $name ($(role "$dev")): $result"
}

# Both sides of a step: the one that waits is started first (begin), then the other one acts (step), then the
# waiting one is collected (end).
begin_dev=""
begin_token=""
begin_t0=0
begin() {
  begin_dev="$2"
  begin_name="$1"
  local limit="$3"
  shift 3
  begin_limit="$limit"
  begin_t0="$(mono_ms)"
  start "$begin_dev" "$@"
  begin_token="$token"
}
end() {
  collect "$begin_dev" "$begin_token" "$begin_limit"
  record "$begin_name" "$(role "$begin_dev")" "$(($(mono_ms) - begin_t0))" "$result"
  [[ "$result" == ok* ]] || fail "step $begin_name ($(role "$begin_dev")): $result"
}

value() { # value <key> <line>
  sed -n "s/.* $1=\([^ ]*\).*/\1/p" <<< " $2" | head -n 1
}

expect() { # expect <step> <key> <expected> <line>
  local got
  got="$(value "$2" "$4")"
  [ "$got" = "$3" ] || fail "step $1: $2=$got, expected $3"
}

snap() { # the counters of both devices, under a label
  local dev
  for dev in "$A" "$B"; do
    echo "$1 $(role "$dev") $(ask "$dev" counters | sed 's/^counters //')" >> "$counters"
  done
}

# The gap between the clocks of A and B (A minus B), best of five by round trip; 0 when it cannot be read.
device_ms() {
  local v
  v="$(adb -s "$1" shell date +%s%N 2>/dev/null | tr -d '\r')"
  if [[ "$v" =~ ^[0-9]{16,}$ ]]; then echo $((v / 1000000)); return 0; fi
  v="$(ask "$1" clock)"
  value ms "$v"
}
offset=0
offset_rtt=""
measure_offset() {
  local best=999999 t0 a t1 b t2 rtt i
  for i in 1 2 3 4 5; do
    t0="$(now_ms)"
    a="$(device_ms "$A")"
    t1="$(now_ms)"
    b="$(device_ms "$B")"
    t2="$(now_ms)"
    [ -n "$a" ] && [ -n "$b" ] || continue
    rtt=$((t1 - t0 > t2 - t1 ? t1 - t0 : t2 - t1))
    if [ "$rtt" -lt "$best" ]; then
      best="$rtt"
      offset=$(((a - (t0 + t1) / 2) - (b - (t1 + t2) / 2)))
    fi
  done
  offset_rtt="$best"
  echo "Clocks: A minus B = $offset ms (read within $best ms)" | tee -a "$steps"
}

# The latency of something sent by one device and seen by the other, the gap between their clocks taken out:
# gap <receiver A|B> <received at, receiver's clock> <sent at, sender's clock>.
gap() {
  if ! [[ "${2:-}" =~ ^[0-9]+$ && "${3:-}" =~ ^[0-9]+$ ]]; then echo "?"; return 0; fi
  if [ "$1" = A ]; then echo $(($2 - $3 - offset)); else echo $(($2 - $3 + offset)); fi
}

# The same for a list: gaps <receiver> <« i:receivedAtMs,… »> <sentAtMs of i = 1, 2…, comma-separated>.
gaps() {
  awk -v r="$2" -v s="$3" -v o="$offset" -v who="$1" 'BEGIN {
      split(s, sent, ","); m = split(r, got, ",")
      for (j = 1; j <= m; j++) {
        split(got[j], p, ":"); d = p[2] - sent[p[1]] - (who == "A" ? o : -o)
        out = out (out ? "," : "") p[1] ":" d
      }
      print out
    }'
}

# Raw latencies of a list « lane:raw,… » (received minus sent, two clocks), corrected: rawgaps <receiver> <list>.
rawgaps() {
  awk -v l="$2" -v o="$offset" -v who="$1" 'BEGIN {
      m = split(l, got, ",")
      for (j = 1; j <= m; j++) {
        split(got[j], p, ":"); d = p[2] - (who == "A" ? o : -o)
        out = out (out ? "," : "") p[1] ":" d
      }
      print out
    }'
}

latency() { # latency <what> <from> <to> <ms>
  echo "  latency $2 to $3, $1: $4 ms" | tee -a "$steps"
}

airplane() { # airplane <device> on|off
  local mode=enable
  [ "$2" = off ] && mode=disable
  adb -s "$1" shell cmd connectivity airplane-mode "$mode" > /dev/null 2>&1 ||
    fail "$(role "$1"): airplane mode could not be set"
}

# ---- the two apps -------------------------------------------------------------------------------------------------

restart_app() { # restart_app <device>: the process killed, the app started again, the trial answering
  local dev="$1" i
  adb -s "$dev" shell am force-stop "$pkg"
  adb -s "$dev" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1 ||
    fail "$(role "$dev"): the app does not start"
  for i in $(seq 1 60); do
    if [[ "$(ask "$dev" status)" == status* ]]; then return 0; fi
    sleep 1
  done
  fail "$(role "$dev"): the trial does not answer after the app started"
}

echo "APK: $apk"
for dev in "$A" "$B"; do
  adb -s "$dev" wait-for-device
  for _ in $(seq 1 150); do
    [ "$(adb -s "$dev" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 2
  done
  [ "$(adb -s "$dev" shell getprop sys.boot_completed | tr -d '\r')" = "1" ] || fail "$(role "$dev") did not finish booting"
  echo "$(role "$dev"): Android $(adb -s "$dev" shell getprop ro.build.version.release | tr -d '\r') (API $(adb -s "$dev" shell getprop ro.build.version.sdk | tr -d '\r'))"
  adb -s "$dev" install -r -g "$apk" > /dev/null || fail "$(role "$dev"): the APK does not install"
  # Screen kept on, a larger log, the app allowed in the background: nothing of the trial waits on them.
  adb -s "$dev" shell svc power stayon true > /dev/null 2>&1 || true
  adb -s "$dev" shell settings put system screen_off_timeout 1800000 > /dev/null 2>&1 || true
  adb -s "$dev" logcat -G 8M > /dev/null 2>&1 || true
  adb -s "$dev" shell cmd connectivity airplane-mode disable > /dev/null 2>&1 || true
  adb -s "$dev" logcat -c || true
  restart_app "$dev"
done
step "0.reset" "$A" 30 reset
step "0.reset" "$B" 30 reset
measure_offset
snap start

# ---- 1. create, invite, ask, let in, place ---------------------------------------------------------------------

step "1.create" "$A" 60 create Exercice Alpha
invite="$(ask "$A" invite)"
invite="${invite#invite }"
[[ "$invite" == toolmap://operation* ]] || fail "A gives no invitation"
mask "$invite"
old_invite="$invite"
old_epoch="$(value epoch "$(ask "$A" status)")"
begin "1.request-seen" "$A" 90 await-request Bravo 90
step "1.join" "$B" 60 join Bravo Exercice "'$invite'"
expect 1.join previewName true "$result"
end
expect 1.request-seen nameShown true "$result"
begin "1.waiting" "$B" 60 await-phase WAITING 60
step "1.admit" "$A" 30 admit Bravo
written_at="$(value writtenAtMs "$result")"
end
latency "the admission" A B "$(gap B "$(value seenAtMs "$result")" "$written_at")"
begin "1.placed" "$B" 60 await-placed Nord Alpha 60
step "1.organize" "$A" 30 organize Nord
written_at="$(value writtenAtMs "$result")"
end
latency "the placement (group, posts, names)" A B "$(gap B "$(value seenAtMs "$result")" "$written_at")"
snap placed

# ---- 2. positions ----------------------------------------------------------------------------------------------

begin "2.positions-in" "$A" 60 await-positions 5 60
step "2.positions-out" "$B" 60 send-positions 5 2000
sent_at="$(value sentAtMs "$result")"
end
received_at="$(value receivedAtMs "$result")"
latency "each position (i:ms)" B A "$(gaps A "$received_at" "$sent_at")"
snap positions

# ---- 3. messages, alerts, waypoint -----------------------------------------------------------------------------

step "3.messages-out" "$A" 60 send-messages ma
count="$(value peerReads "$result")"
step "3.messages-in" "$B" 60 await-messages ma "$count" 60
latency "a message, by thread (lane:ms)" A B "$(rawgaps B "$(value lanes "$result")")"
step "3.messages-out" "$B" 60 send-messages mb
count="$(value peerReads "$result")"
step "3.messages-in" "$A" 60 await-messages mb "$count" 60
latency "a message, by thread (lane:ms)" B A "$(rawgaps A "$(value lanes "$result")")"
begin "3.alert-in" "$B" 60 await-alert alerte-a 60
step "3.alert-out" "$A" 30 send-alert alerte-a
expect 3.alert-out peerReads true "$result"
end
latency "the alert" A B "$(rawgaps B "x:$(value latencyRawMs "$result")" | cut -d: -f2)"
begin "3.alert-lifted" "$B" 60 await-alert-gone 60
step "3.alert-lift" "$A" 30 lift-alert
lifted_at="$(value sentAtMs "$result")"
end
latency "the alert lifted" A B "$(gap B "$(value seenAtMs "$result")" "$lifted_at")"
begin "3.alert-in" "$A" 60 await-alert alerte-b 60
step "3.alert-out" "$B" 30 send-alert alerte-b
expect 3.alert-out peerReads true "$result"
end
latency "the alert" B A "$(rawgaps A "x:$(value latencyRawMs "$result")" | cut -d: -f2)"
begin "3.alert-lifted" "$A" 60 await-alert-gone 60
step "3.alert-lift" "$B" 30 lift-alert
lifted_at="$(value sentAtMs "$result")"
end
latency "the alert lifted" B A "$(gap A "$(value seenAtMs "$result")" "$lifted_at")"
begin "3.waypoint-in" "$B" 60 await-object 60
step "3.waypoint-put" "$A" 30 put-waypoint
put_at="$(value sentAtMs "$result")"
end
latency "the waypoint" A B "$(gap B "$(value seenAtMs "$result")" "$put_at")"
begin "3.waypoint-gone" "$B" 60 await-object-gone 60
step "3.waypoint-remove" "$A" 30 remove-waypoint
put_at="$(value sentAtMs "$result")"
end
latency "the waypoint taken away" A B "$(gap B "$(value seenAtMs "$result")" "$put_at")"
snap shared
# A minute with nothing shared: what two placed phones cost the server on their own.
sleep 60
snap idle

# ---- 4. A restarts, B off the network ----------------------------------------------------------------------------

snap before-restart
adb -s "$A" shell am force-stop "$pkg"
step "4.waypoint-put" "$B" 30 put-waypoint
restart_at="$(mono_ms)"
restart_app "$A"
step "4.reopen" "$A" 90 open
step "4.caught-up" "$A" 90 await-object 90
echo "  A caught up B's waypoint $(($(mono_ms) - restart_at)) ms after its app was started again (script clock)" | tee -a "$steps"
snap restarted

airplane "$B" on
cut_at="$(mono_ms)"
step "4.cut" "$B" 90 await-server UNREACHABLE 90
step "4.rename" "$A" 30 rename-group Sud
step "4.waypoint-put" "$A" 30 put-waypoint
left=$((40 - ($(mono_ms) - cut_at) / 1000))
[ "$left" -gt 0 ] && sleep "$left"
airplane "$B" off
back_at="$(mono_ms)"
echo "  B off the network for $(((back_at - cut_at) / 1000)) s" | tee -a "$steps"
step "4.back-online" "$B" 120 await-server ONLINE 120
step "4.group-renamed" "$B" 120 await-group Sud 120
echo "  B caught up the renamed group $(($(mono_ms) - back_at)) ms after the network came back (script clock)" | tee -a "$steps"
step "4.waypoint-in" "$B" 120 await-object 120
snap outage

# ---- 5. B leaves, the invitation is renewed, the old one is refused ------------------------------------------------

begin "5.departure-seen" "$A" 90 await-departure 90
step "5.leave" "$B" 60 leave
written_at="$(value writtenAtMs "$result")"
end
latency "the departure" B A "$(gap A "$(value goneAtMs "$result")" "$written_at")"
begin "5.old-invitation" "$A" 50 await-no-request Charlie 45
step "5.join-old" "$B" 60 join Charlie Exercice "'$old_invite'"
end
step "5.abandon" "$B" 30 reset
snap left

# ---- 6. B comes in again, A dissolves while B is away, B learns it from the trace --------------------------------

invite="$(ask "$A" invite)"
invite="${invite#invite }"
[[ "$invite" == toolmap://operation* ]] || fail "A gives no new invitation"
mask "$invite"
new_epoch="$(value epoch "$(ask "$A" status)")"
[ "${new_epoch:-0}" -gt "${old_epoch:-0}" ] || fail "the invitation key was not renewed (epoch ${old_epoch:-?} then ${new_epoch:-?})"
echo "  invitation key: epoch $old_epoch when B came in, $new_epoch now" | tee -a "$steps"
begin "6.request-seen" "$A" 90 await-request Delta 90
step "6.join" "$B" 60 join Delta Exercice "'$invite'"
end
begin "6.waiting" "$B" 60 await-phase WAITING 60
step "6.admit" "$A" 30 admit Delta
end
airplane "$B" on
step "6.cut" "$B" 90 await-server UNREACHABLE 90
step "6.dissolve" "$A" 60 dissolve
expect 6.dissolve ending DISSOLVED "$result"
expect 6.dissolve traceKept true "$result"
# A leaves the room 5 s after the end (its frames go with its node): B can no longer read the dissolution there.
sleep 10
airplane "$B" off
step "6.back-online" "$B" 120 await-server ONLINE 120
snap before-relaunch
restart_app "$A"
snap before-trace
begin "6.ended-by-trace" "$B" 180 await-ended 180
step "6.announce" "$A" 30 announce
announced_at="$(value atMs "$result")"
end
latency "the dissolution, told by the trace" A B "$(gap B "$(value seenAtMs "$result")" "$announced_at")"
expect 6.ended-by-trace byTrace true "$result"
expect 6.ended-by-trace traceKept true "$result"
step "6.window-over" "$A" 200 await-announced 200
snap trace

for dev in "$A" "$B"; do
  if why="$(crashed "$dev")"; then fail "$(role "$dev"): $why of $pkg"; fi
done
echo "OK: « Opération » end to end over the real coordination server, two devices, all steps passed." | tee "$out/result.txt"
