#!/usr/bin/env bash
# Starts and stops InfoScry and its PostgreSQL database together.
#
#   ./server.sh start [--build] [serve options]   database, then the server in the background
#   ./server.sh stop                               the server, then the database (data is kept)
#   ./server.sh restart [--build] [serve options]
#   ./server.sh status
#   ./server.sh logs                               follow the server's output
#   ./server.sh admin <command>                    `infoscry admin` with the database settings, e.g.
#                                                        `admin create-user --admin anna`
#
# Settings come from .env (copied from .env.example on first use). The database runs from compose.yaml
# with Docker or Podman; the server runs on the host so it can use the GPU. `--build` runs
# `./gradlew installDist` first; the app is also built when no build exists yet. Options after the command,
# such as `--port 9000` or `--data-dir <path>`, are passed to `infoscry serve`.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
run_dir="$root/.run"
pid_file="$run_dir/server.pid"
log_file="$run_dir/server.log"
launcher="$root/build/install/infoscry/bin/infoscry"
# From the checkout, so its .tool-versions (asdf) selects JDK 25 and relative paths mean the same everywhere.
cd "$root"

fail() { echo "error: $*" >&2; exit 1; }
say() { echo "$*"; }

load_env() {
    if [[ ! -f "$root/.env" ]]; then
        cp "$root/.env.example" "$root/.env"
        fail "created .env from .env.example; set INFOSCRY_DATABASE_PASSWORD in $root/.env and run again"
    fi
    set -a
    # shellcheck source=/dev/null
    source "$root/.env"
    set +a
}

# Sets `compose` to the Compose command of Docker or Podman, starting the Podman machine when needed.
find_compose() {
    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
        compose=(docker compose)
    elif command -v podman >/dev/null 2>&1; then
        if [[ "$(uname -s)" == "Darwin" ]] && ! podman info >/dev/null 2>&1; then
            say "Starting the Podman machine…"
            podman machine start >/dev/null
        fi
        compose=(podman compose)
        export PODMAN_COMPOSE_WARNING_LOGS=false
    else
        fail "neither Docker nor Podman is available; see docs/installation.md#postgresql"
    fi
}

server_pid() {
    [[ -f "$pid_file" ]] || return 1
    local pid
    pid="$(cat "$pid_file")"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        echo "$pid"
    else
        rm -f "$pid_file"
        return 1
    fi
}

start_database() {
    say "Starting PostgreSQL…"
    (cd "$root" && "${compose[@]}" up -d postgres >/dev/null)
    local user="${INFOSCRY_DATABASE_USER:-infoscry}" name="${INFOSCRY_DATABASE_NAME:-infoscry}"
    for _ in $(seq 1 60); do
        if (cd "$root" && "${compose[@]}" exec -T postgres pg_isready -q -U "$user" -d "$name") >/dev/null 2>&1; then
            say "PostgreSQL is ready on 127.0.0.1:${INFOSCRY_DATABASE_PORT:-5432}."
            return 0
        fi
        sleep 1
    done
    fail "PostgreSQL did not become ready within 60 seconds; see \`${compose[*]} logs postgres\`"
}

check_java() {
    local java="java"
    [[ -n "${JAVA_HOME:-}" ]] && java="$JAVA_HOME/bin/java"
    local version
    version="$("$java" -version 2>&1 | awk -F'"' '/version/ { print $2; exit }')" || true
    [[ "${version%%.*}" =~ ^[0-9]+$ && "${version%%.*}" -ge 25 ]] \
        || fail "InfoScry needs JDK 25, but ${java} is ${version:-missing}; set JAVA_HOME to a JDK 25"
}

start() {
    local build=false
    if [[ "${1:-}" == "--build" ]]; then build=true; shift; fi
    load_env
    if pid="$(server_pid)"; then
        say "InfoScry is already running (pid $pid); see $log_file."
        return
    fi
    find_compose
    check_java
    if $build || [[ ! -x "$launcher" ]]; then
        say "Building InfoScry…"
        (cd "$root" && ./gradlew installDist -q)
    fi
    start_database

    mkdir -p "$run_dir"
    say "Starting InfoScry…"
    nohup "$launcher" serve --json "$@" >"$log_file" 2>&1 &
    local pid=$!
    echo "$pid" >"$pid_file"
    for _ in $(seq 1 120); do
        if ! kill -0 "$pid" 2>/dev/null; then
            rm -f "$pid_file"
            tail -n 20 "$log_file" >&2
            fail "the server stopped while starting; the output is in $log_file"
        fi
        local url
        url="$(sed -n 's/.*"url":"\([^"]*\)".*/\1/p' "$log_file" | head -n 1)"
        if [[ -n "$url" ]]; then
            say "InfoScry is listening on $url (pid $pid); output goes to $log_file."
            grep -q "No users exist yet" "$log_file" \
                && say "No users exist yet; create one with: $0 admin create-user --admin <username>"
            return 0
        fi
        sleep 1
    done
    fail "the server did not report its address within 120 seconds; see $log_file"
}

stop() {
    load_env
    if pid="$(server_pid)"; then
        say "Stopping InfoScry (pid $pid)…"
        kill -TERM "$pid"
        for _ in $(seq 1 30); do
            kill -0 "$pid" 2>/dev/null || break
            sleep 1
        done
        if kill -0 "$pid" 2>/dev/null; then
            say "The server did not stop within 30 seconds; killing it (interrupted work resumes on next start)."
            kill -KILL "$pid" 2>/dev/null || true
        fi
        rm -f "$pid_file"
    else
        say "InfoScry is not running."
    fi
    find_compose
    say "Stopping PostgreSQL (the data is kept)…"
    (cd "$root" && "${compose[@]}" down >/dev/null 2>&1) || fail "could not stop PostgreSQL; see \`${compose[*]} ps\`"
    say "Stopped."
}

status() {
    load_env
    if pid="$(server_pid)"; then
        local url
        url="$(sed -n 's/.*"url":"\([^"]*\)".*/\1/p' "$log_file" | head -n 1)"
        say "InfoScry: running (pid $pid) on ${url:-an unknown address}"
    else
        say "InfoScry: stopped"
    fi
    find_compose
    if (cd "$root" && "${compose[@]}" exec -T postgres pg_isready -q \
        -U "${INFOSCRY_DATABASE_USER:-infoscry}" -d "${INFOSCRY_DATABASE_NAME:-infoscry}") >/dev/null 2>&1; then
        say "PostgreSQL: running on 127.0.0.1:${INFOSCRY_DATABASE_PORT:-5432}"
    else
        say "PostgreSQL: stopped"
    fi
}

command="${1:-}"
[[ $# -gt 0 ]] && shift
case "$command" in
    start) start "$@" ;;
    stop) stop ;;
    restart) stop; start "$@" ;;
    status) status ;;
    admin) load_env; exec "$launcher" admin "$@" ;;
    logs) [[ -f "$log_file" ]] || fail "no server output yet"; tail -f "$log_file" ;;
    *) sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; [[ -z "$command" ]] || exit 2 ;;
esac
