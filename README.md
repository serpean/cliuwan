# cliuwan

A command-line client for [Kiuwan](https://www.kiuwan.com): query your applications and their
security defects from the terminal, or from an AI coding agent.

```console
$ cliuwan defects MY-APP --group-by cwe --format text
Analysis: A-7ea-19fd692b3eb (Complete delivery, 2026-08-06)
Application: MY-APP
Defects: 338 grouped by cwe
============================================================
   168  CWE-89
   103  CWE-79
    67  CWE-798
```

> **Not affiliated with Kiuwan or Idera, Inc.** This is an independent, unofficial client that
> talks to the public [Kiuwan REST API](https://static.kiuwan.com/rest-api/kiuwan-rest-api.json).
> "Kiuwan" is a trademark of its respective owner.

## What it does, and what it does not

|  |  |
|---|---|
| **Does** | Read results of analyses that already exist: applications, analyses, their defects, and their quality-gate (audit) results |
| **Does not** | Run new analyses. That is the [Kiuwan Local Analyzer (KLA)](https://support.kiuwan.com/hc/en-us/articles/37024520067729-Kiuwan-Local-Analyzer-CLI-Command-Line-Interface), a separate and complementary tool |

The two fit together: KLA scans your source and uploads the analysis; `cliuwan` reads the
results back.

## Install

### Native binary (recommended)

No Java required. Starts in about 20 ms.

```bash
# macOS (Apple Silicon)
curl -L https://github.com/serpean/cliuwan/releases/latest/download/cliuwan-darwin-arm64 -o cliuwan

# Linux x64
curl -L https://github.com/serpean/cliuwan/releases/latest/download/cliuwan-linux-x64 -o cliuwan

chmod +x cliuwan && sudo mv cliuwan /usr/local/bin/
cliuwan login
```

| Platform | Asset |
|---|---|
| Linux x64 | `cliuwan-linux-x64` |
| Linux arm64 | `cliuwan-linux-arm64` |
| macOS Apple Silicon | `cliuwan-darwin-arm64` |
| macOS Intel | `cliuwan-darwin-x64` |
| Windows x64 | `cliuwan-windows-x64.exe` |
| Any (needs Java 21) | `cliuwan.jar` |

Every release ships a `SHA256SUMS` file to verify against.

> **macOS**: the binaries are not notarised. Gatekeeper blocks anything downloaded through a
> browser, so either fetch it with `curl` as above or clear the flag with
> `xattr -d com.apple.quarantine cliuwan`.

### From source

Requires **Java 21 or newer**.

```bash
git clone https://github.com/serpean/cliuwan.git
cd cliuwan
./install.sh
```

That builds the project, installs the jar in `~/.local/share/cliuwan/`, the launcher in
`~/.local/bin/cliuwan`, and offers to set up your credentials.

Without installing anything:

```bash
./gradlew build
java -jar build/libs/cliuwan.jar --help
```

To build a native binary yourself, with a GraalVM JDK on `JAVA_HOME`:

```bash
./gradlew nativeCompile
./build/native/nativeCompile/cliuwan --version
```

## Credentials

```bash
cliuwan login
```

Prompts for your Kiuwan username and password (without echoing it), **checks them against the
API**, and only then stores them in `~/.config/cliuwan/credentials` with `600` permissions. If
Kiuwan rejects them, nothing is written — so you never end up with a typo that fails days later.

```bash
cliuwan config     # where credentials come from, without printing the password
cliuwan logout     # delete the stored file
```

### For CI and containers

```bash
export KIUWAN_USER=your_username
export KIUWAN_PASS=your_password

# or a non-interactive login
echo "$KIUWAN_PASS" | cliuwan login -u your_username --password-stdin
```

Environment variables take precedence over the file. There is deliberately **no `--pass`
option**: a password passed as an argument lands in your shell history and is visible in `ps`.

> **Windows**: POSIX permissions do not exist there, so the credentials file is written without
> the `600` hardening applied on Linux and macOS. It inherits whatever your user profile
> directory grants. If that matters in your environment, use `KIUWAN_USER` / `KIUWAN_PASS`
> instead, or restrict the file with ACLs.

## Usage

### Find an application

```bash
cliuwan apps PAYMENTS               # full JSON
cliuwan apps PAYMENTS --names-only  # just names, one per line
cliuwan apps MY-APP --exact         # exact match
```

### Which analysis am I looking at?

This matters more than it sounds. `cliuwan` reads the **most recent successful analysis** by
default, which is usually a delivery. The baseline can be months older and tell a completely
different story.

```bash
cliuwan analyses MY-APP --format text
```
```
DATE         TYPE               STATUS    MODEL            AUDIT  CODE                 LABEL
2026-08-06   Complete delivery  SUCCESS   DeudaTecnica     OK     A-7ea-19fd692b3eb    1.0.0
2026-08-06   Complete delivery  SUCCESS   CQM_Blocker      FAIL   A-7ea-19fd692b3e7    1.0.0_blocker
2026-08-06   Complete delivery  FAIL      DeudaTecnica     -      A-7ea-19fd67aedfa    1.0.0
2026-06-29   Baseline           SUCCESS   DeudaTecnica     -      A-7ea-19f1258cc37    1.0.0
```

Failed analyses are skipped automatically — asking them for defects returns a 404. Pick one
explicitly with `--analysis baseline`, `--analysis delivery`, or an analysis code.

### Several quality models per commit

Kiuwan CI can analyse one commit under several quality models (say, a technical-debt model and
a blocker-only gate). Each one is a separate delivery, with its own `MODEL` and `AUDIT` (the
gate verdict, `OK` or `FAIL`). Without `--model`, `cliuwan` reads the newest analysis of any
model, as before. To pick one:

```bash
cliuwan models --format text                                 # the model names this account has
cliuwan analyses MY-APP --model CQM_Blocker --format text    # only that model (exact, any case)
cliuwan analyses MY-APP --label 1.2.0                        # labels containing "1.2.0"
cliuwan defects MY-APP --model CQM_Blocker --analysis delivery --group-by rule
cliuwan defects MY-APP --model CQM_Blocker --label 1.2.0      # that version, that model
```

`--model` and `--label` narrow the candidates before `--analysis latest|baseline|delivery`
picks the newest. They are ignored when `--analysis` is a literal code.

### Quality gate (audit)

`cliuwan audit` shows whether the gate let an analysis through, which checkpoint stopped it,
and which rules raised the defects. It resolves the analysis like `defects`, but defaults to
`--analysis delivery`, because baselines are not audited.

```console
$ cliuwan audit MY-APP --all-models --label 1.2.0 --format text
Application: MY-APP
==============================================================================
DeudaTecnica: OK (score 100, threshold 50)   A-7ea-19fd692b3eb  1.2.0  2026-08-06
  Defects: 432 (new 0, removed 0)
  [OK] No new defects - Threshold for maximum new defects (mandatory)
==============================================================================
CQM_Blocker: FAIL (score 0, threshold 100)   A-7ea-19fd692b3e7  1.2.0_blocker  2026-08-06
  Defects: 49 (new 1, removed 384)
  [FAIL] chk_blocker - Threshold for maximum new defects (mandatory)
      OPT.JAVA.CMETRICS.TCC: 1 defects
```

```bash
cliuwan audit MY-APP                              # newest delivery, any model
cliuwan audit MY-APP --model CQM_Blocker          # newest delivery of that model
cliuwan audit MY-APP --all-models                 # newest delivery of every model
cliuwan audit MY-APP --analysis A-7ea-19fd692b3e7 # one analysis by code
cliuwan audit MY-APP --all-models --fail-on-fail  # exit 5 if any gate failed
```

`--all-models` finds the models in the analysis listing, so a model added to the CI shows up
without any change. Its JSON output is an array; a single audit is one object.

### Query defects

```bash
cliuwan defects MY-APP                              # every defect
cliuwan defects MY-APP -p 'Very high,High'          # only the serious ones
cliuwan defects MY-APP --file src/main/auth         # a specific path
cliuwan defects MY-APP -l Java -c Security          # by language and characteristic
cliuwan defects MY-APP --cwe 89,79                  # by CWE
```

### Summarise instead of listing

The difference between reading 500 lines of JSON and reading three:

```console
$ cliuwan defects MY-APP --group-by rule --format text
Application: MY-APP
Defects: 503 grouped by rule
============================================================
   168  Cross-site scripting
   168  SQL Injection
   167  Use of Hard-coded Credentials
```

Available criteria: `rule`, `cwe`, `file`, `priority`, `language`, `characteristic`.

### Shrink the output

```bash
cliuwan defects MY-APP --count                          # just the total
cliuwan defects MY-APP --fields rule,file,line,cweId    # only these fields
cliuwan defects MY-APP --limit 20                       # first 20
cliuwan defects MY-APP --list-fields                    # what fields exist
```

### Formats

```bash
cliuwan defects MY-APP -f json    # default: pipe it into jq
cliuwan defects MY-APP -f md      # markdown table for a PR
cliuwan defects MY-APP -f text    # human-readable listing
```

### Compose with jq

```bash
# The 10 files with the most critical defects
cliuwan defects MY-APP -p 'Very high' --fields file \
  | jq -r 'group_by(.file) | map({f:.[0].file, n:length}) | sort_by(-.n) | .[:10][] | "\(.n)\t\(.f)"'
```

### Quality gate in a pipeline

```bash
cliuwan defects MY-APP -p 'Very high' --fail-on-found
# exit 4 if any critical defect exists, 0 if clean

cliuwan audit MY-APP --all-models --fail-on-fail
# exit 5 if the Kiuwan quality gate of any model failed
```

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Success |
| 1 | Kiuwan API or network error |
| 2 | Incorrect usage |
| 3 | Credentials missing or rejected |
| 4 | Defects found while `--fail-on-found` was set |
| 5 | An audit failed while `--fail-on-fail` was set |

Errors go to **stderr**, so `cliuwan ... | jq` keeps working when something fails.

## Cache

Responses are cached for 5 minutes under `~/.cache/cliuwan/` (or `$XDG_CACHE_HOME/cliuwan/`),
with `600` permissions since they can contain source paths and vulnerability details.

Entries are scoped by user, password and base URL, so switching accounts or instances never
reuses the previous one's data. Rotating your password invalidates the cache, which is the
behaviour you want.

```bash
cliuwan defects MY-APP --no-cache        # bypass it
cliuwan defects MY-APP --cache-ttl 3600  # change the lifetime, in seconds
cliuwan cache clear                      # empty it
cliuwan cache info                       # where it lives
```

## Using it with an AI coding agent

This is what the tool was built for. A CLI beats an MCP server here for two reasons: it only
occupies context when it is actually invoked, and its output can be trimmed with `jq` before
the model ever reads it.

`install.sh` registers a [Claude Code](https://claude.com/claude-code) skill in
`~/.claude/skills/cliuwan/` that teaches the agent when to reach for the command and — more
importantly — to aggregate with `--group-by` before listing, so hundreds of defects never get
dumped into the conversation.

With it installed you can just ask, in any repository:

> "What critical vulnerabilities does MY-APP have?"
> "Does the file I'm editing have any defects reported in Kiuwan?"

## Notes on the Kiuwan API

Three behaviours worth knowing, all handled here:

- **Results are paginated** (`count`, 500 by default). Asking for a single page silently loses
  data on large applications. `cliuwan` walks every page.
- **`/applications/defects` reports the latest baseline**, not the latest analysis. On a
  project with an old baseline that means stale results. `cliuwan` resolves the most recent
  successful analysis and reads `/apps/analysis/{code}/defects` instead, and prints on stderr
  which analysis the data came from.
- **A 404 on the defects of an analysis can be transient.** Kiuwan answers
  `Application not found` (code 03) even when the application exists. It was seen on a
  blocker-model delivery that read fine minutes later, which fits an analysis still being
  processed. `cliuwan` then names the analysis and its model and suggests a retry with
  `--no-cache` or `cliuwan audit`. Errors are never cached.

## Development

```bash
./gradlew test    # 68 tests, no network required
./gradlew build   # produces build/libs/cliuwan.jar
```

Tests spin up a JDK `HttpServer` that mimics the Kiuwan API rather than pulling in WireMock,
to keep the dependency list short. There is no Spring: the CLI is invoked once per query, so
startup dominates — Picocli plus the JDK HTTP client start in about 0.2s on the JVM, and about
0.02s once compiled ahead of time.

### A note on the native build

GraalVM strips record metadata unless it is registered, because static analysis cannot see
that Jackson and `Class.getRecordComponents()` reach for it reflectively. The registrations
live in `src/main/resources/META-INF/native-image/`; Picocli generates its own through
`picocli-codegen`.

This failure mode is worth knowing about: the image links cleanly and then throws
`UnsupportedFeatureError` on first use. That is why the release workflow **runs** every binary
it builds — exercising `--list-fields`, which goes through `getRecordComponents()` — before
publishing it. If you add a type that gets serialized, register it there and check the binary,
not just the test suite.

Contributions are welcome. Please keep the test suite green and add coverage for whatever you
change.

## License

[MIT](LICENSE)
