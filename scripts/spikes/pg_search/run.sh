#!/usr/bin/env bash
# Runs the pg_search spike end to end. Needs Podman or Docker, JDK 25, uv, and the corpus fetched by
# scripts/fetch_sfs_laws.py into data/sfs-lagar. Results go to $OUT (default: build/spike-pg-search).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
out="${OUT:-$root/build/spike-pg-search}"
engine="${ENGINE:-$(command -v docker || command -v podman)}"
image="docker.io/paradedb/paradedb:0.26.5-pg17"
mkdir -p "$out"

python3 -I "$here/prepare.py" "$root/data/sfs-lagar" "$out/chunks.tsv"

if ! "$engine" container exists infoscry-spike-pg 2>/dev/null && ! "$engine" inspect infoscry-spike-pg >/dev/null 2>&1; then
  "$engine" run -d --name infoscry-spike-pg -e POSTGRES_PASSWORD=spike -e POSTGRES_DB=spike \
    -p 127.0.0.1:55432:5432 "$image" >/dev/null
fi
"$engine" start infoscry-spike-pg >/dev/null
until "$engine" exec infoscry-spike-pg pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done

classpath="$(cd "$root" && ./gradlew -q -PskipFrontend --no-configuration-cache --init-script "$here/classpath.gradle" printSpikeClasspath | sed -n 's/^CP=//p')"
java -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED --class-path "$classpath" \
  "$here/LuceneSide.java" "$out/chunks.tsv" "$here/queries.tsv" "$here/probes.txt" "$out"
(cd "$here" && uv run -q --no-project --with 'psycopg[binary]==3.2.13' python -I pg_side.py \
  "postgresql://postgres:spike@127.0.0.1:55432/spike" "$out/chunks.tsv" queries.tsv probes.txt "$out")
python3 -I "$here/compare.py" "$here/queries.tsv" "$out"
