#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
usage: superintelligence/train.sh [options] [-- extra trainer args]

  -n NAME     run name: out/evidence/NAME.csv, .log, trainer-replays/NAME   (default: auto-<date>)
  -H HOURS    stop after the generation that crosses HOURS, then checkpoint   (default: none)
  -g GENS     generations to add                                            (default: 30, or 100000 with -H)
  -p PROFILE  reward profile: default | depth_only                           (default: default)
  -d DIR      demonstration recordings to imitate
  -r          resume from out/run/weights.bin
  -k          print what --auto would choose on this machine, then exit

Workers and update threads come from a RAM and CPU probe (--auto) unless given after --.
The JDK is JAVA_HOME, else java on PATH, else ~/.local/jdk21.
EOF
}

name="auto-$(date +%Y%m%d-%H%M)"
hours=""
gens=""
profile="default"
demos=""
resume=0
probe=0
while getopts "n:H:g:p:d:rkh" opt; do
    case "$opt" in
        n) name="$OPTARG" ;;
        H) hours="$OPTARG" ;;
        g) gens="$OPTARG" ;;
        p) profile="$OPTARG" ;;
        d) demos="$(realpath "$OPTARG")" ;;
        r) resume=1 ;;
        k) probe=1 ;;
        h) usage; exit 0 ;;
        *) usage; exit 1 ;;
    esac
done
shift $((OPTIND - 1))
[ "${1:-}" = "--" ] && shift

if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null 2>&1 && [ -x "$HOME/.local/jdk21/bin/java" ]; then
    export JAVA_HOME="$HOME/.local/jdk21"
fi
if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null 2>&1; then
    echo "[ERROR] no JDK: set JAVA_HOME, put java on PATH, or unpack a JDK 17+ into ~/.local/jdk21" >&2
    exit 1
fi

module="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(dirname "$module")"
cd "$repo"
chmod +x gradlew

if [ "$probe" = 1 ]; then
    exec ./gradlew -q --console=plain :superintelligence:autotune
fi

group="$(printf '%s' "$name" | tr -c 'A-Za-z0-9._-' '-')"
evidence="$module/out/evidence"
bin="$module/out/run/weights.bin"
mkdir -p "$evidence" "$module/out/run"
csv="$evidence/$group.csv"
log="$evidence/$group.log"
[ -f "$csv" ] && mv -f "$csv" "$csv.prev"

[ -z "$gens" ] && { [ -n "$hours" ] && gens=100000 || gens=30; }

args=(--auto --reward-profile "$profile" --generations "$gens" --replay-group "$group"
      --metrics "$csv" --save "$bin" --checkpoint-every 5)
[ -n "$hours" ] && args+=(--hours "$hours")
[ "$resume" = 1 ] && args+=(--resume "$bin")
[ -n "$demos" ] && args+=(--demos "$demos")
args+=("$@")

echo "[OK] run $name  log $log"
echo "     ./gradlew :superintelligence:train --args=\"${args[*]}\""
./gradlew --console=plain :superintelligence:train --args="${args[*]}" 2>&1 | tee "$log"
exit "${PIPESTATUS[0]}"
