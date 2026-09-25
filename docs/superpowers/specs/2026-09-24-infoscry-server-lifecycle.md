# InfoScry server lifecycle

**Status:** Approved local-use contract (2026-09-24).

## Purpose and scope

On this single-user Mac, starting InfoScry should leave the terminal free while
the local server continues to run. `infoscry serve` and `infoscry --serve` are
equivalent. `infoscry stop` stops that server from a separate terminal. This is
manual start/stop, not launch-at-login, a system service, or a release package.
Standalone `infoscry import` remains foreground unless a running server accepts
the job, as the existing product contract requires.

## Start

- Both public start spellings launch the current Java application through
  macOS `nohup` as a detached child, with inherited environment variables
  (including configured LLM key variables) and no terminal input. The child
  runs the existing server path;
  it alone owns the data-directory lock, jobs, database, and loopback listener.
- The short-lived CLI waits for the child's `runtime.json`. Because the server
  writes that file only after binding `127.0.0.1`, a matching live child PID in
  the file is the readiness signal. The CLI reports the actual URL and PID and
  returns only after readiness. A bounded timeout or child exit is a failed
  start, not a success message. A timed-out child that this invocation created
  receives a soft stop request; the CLI never force-kills a process.
- If the same data directory already has a responsive InfoScry server, a new
  start does not create another one. It reports the existing URL and opens it
  as below. An unresponsive or unverifiable owner produces an actionable error
  rather than a second server or a guessed URL.
- In ordinary text mode the short-lived CLI opens the ready URL with the macOS
  default browser. Failure to open the browser is a warning containing the URL;
  it does not stop the server. `--json` reports one stable machine-readable
  response and does not open a browser. The child never opens a browser.
- Child standard output and errors go to a private file under the selected
  data directory's logs, so closing the terminal does not close its pipes.
  Startup errors point to that file without exposing keys or document text in
  CLI output.

## Stop

- `infoscry stop [--data-dir PATH]` reads only that directory's `runtime.json`.
  Runtime metadata records the process start instant in addition to PID, port,
  and bearer token. The CLI checks that the PID is live and its start instant
  still matches before signalling; it does not signal a reused PID, an old
  runtime file without identity metadata, or a different process.
- Stop sends a graceful termination request to the verified process, then
  waits for it to exit. The existing shutdown hook closes the server and
  application context and removes `runtime.json`. Success means the process
  exited, not merely that a signal was sent. Stop has no force-kill mode;
  committed import checkpoints remain available after a later restart.
- A missing, stale, or unverifiable server gets a clear nonzero result and a
  manual remedy. No unrelated process is stopped and no external source file
  is changed. The same data-directory option works for both start and stop.

## Verification and documentation

Use disposable data directories and process-level tests for background
readiness, browser invocation without opening a real browser, repeated start,
graceful stop, startup failure, and stale or mismatched process identity. Run
the normal test suite and test the compiled `installDist` launcher directly.
Update `README.md`, the current implementation status, and the main design
contract together so none still instructs users to keep a terminal open or
press Ctrl-C to stop the public server command.
