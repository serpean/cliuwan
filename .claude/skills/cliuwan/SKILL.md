---
name: cliuwan
description: Query Kiuwan security defects and vulnerabilities for an application through the `cliuwan` CLI. Use it whenever the question is about vulnerabilities, defects, security technical debt, CWEs, Kiuwan findings, or an application's security posture; also when deciding what to fix first, or checking whether a specific file has reported defects. It does not run new analyses (that is the Kiuwan Local Analyzer).
---

# Querying Kiuwan from the CLI

The `cliuwan` command reads the Kiuwan REST API: registered applications and the defects of
analyses that **already exist**. It does not run new analyses.

## Before you start

Check the command is available and authenticated:

```bash
cliuwan config
```

If it exits with code 3 there are no credentials. **Do not ask for them in chat and do not
guess them**: tell the user to run `cliuwan login`, which prompts on the console without
echoing and validates against the API before storing anything. In Claude Code they can run it
with `! cliuwan login`.

## Find the exact application name

The name in Kiuwan rarely matches the repository name. Look it up first:

```bash
cliuwan apps PAYMENTS --names-only
```

If several come back, ask the user which one rather than picking for them.

## Check which analysis you are reading

`cliuwan` defaults to the most recent successful analysis, but it is worth confirming: an
application whose last delivery failed will fall back to an older one, and a baseline can be
months stale.

```bash
cliuwan analyses MY-APP --format text
```

Every `defects` call prints the analysis and its date on stderr. Mention that date when you
report findings — "338 defects as of 2026-08-06" is useful, "338 defects" is not.

## Main rule: aggregate before listing

An application can have hundreds or thousands of defects. **Never** ask for the full listing
as a first step. Always start from a summary:

```bash
cliuwan defects MY-APP --group-by cwe       # what kinds of vulnerability?
cliuwan defects MY-APP --group-by rule      # which rules are broken most?
cliuwan defects MY-APP --group-by file      # where is it concentrated?
cliuwan defects MY-APP --group-by priority  # how severe overall?
```

Only once you have the shape should you drill into what matters.

## Ask for the fields you need

The full detail of a defect carries 16 fields, most of them irrelevant to any given question.
Project instead:

```bash
cliuwan defects MY-APP -p 'Very high' --fields rule,file,line,cweId
```

Available fields: `cliuwan defects X --list-fields`.

## Filter server-side whenever you can

These are applied by the API, so they never download what they will discard:

```bash
cliuwan defects MY-APP -p 'Very high,High'     # by priority
cliuwan defects MY-APP -l Java                 # by language
cliuwan defects MY-APP -c Security             # by characteristic
cliuwan defects MY-APP --file src/main/auth    # by file path
```

`--cwe 89,79` filters client-side (the API has no such parameter), so pair it with one of the
above on large applications.

## Compose with jq

The default output is JSON, so it can be trimmed before you read it:

```bash
# The 10 files with the most critical defects
cliuwan defects MY-APP -p 'Very high' --fields file \
  | jq -r 'group_by(.file) | map({f:.[0].file, n:length}) | sort_by(-.n) | .[:10][] | "\(.n)\t\(.f)"'

# Does the file I am editing have defects?
cliuwan defects MY-APP --file src/main/java/com/app/Dao.java --fields rule,line,cweId
```

## Formats

- `--format json` (default): to process, or to read yourself.
- `--format md`: markdown table, when the user wants to paste it into a PR or a report.
- `--format text`: plain readable listing.

## Reading priorities

Kiuwan uses five levels: `Very high`, `High`, `Normal`, `Low`, `Very low`. Quote them (they
contain a space). If the user says "critical" or "what's urgent", that means
`'Very high,High'`.

## Judge findings before reporting them

Kiuwan produces false positives, and the `HardcodedUsernamePassword` rule (CWE-798) is a
frequent offender: it flags UUIDs, fixture identifiers and test constants as credentials.
Before telling a user they have hard-coded credentials, open the file and look. Report what
the code actually does, and say plainly when a finding does not hold up.

## Cache

Responses are cached on disk for 5 minutes. If the user has just re-run an analysis in Kiuwan
and wants fresh data, add `--no-cache`.

## Exit codes

| Code | Meaning | What to do |
|---|---|---|
| 0 | Success | — |
| 1 | API or network error | Show the stderr message to the user |
| 2 | Incorrect usage | Check the syntax with `cliuwan defects --help` |
| 3 | Credentials missing or rejected | Ask the user to run `cliuwan login` |
| 4 | Defects found with `--fail-on-found` | That is the expected quality-gate result |

## What this CLI does not do

It does not run analyses. If the user wants to **analyse** code rather than query results, the
tool is the Kiuwan Local Analyzer (`agent.sh`), a separate product that scans source and
uploads the analysis. Say so plainly instead of trying to force this CLI into the job.
