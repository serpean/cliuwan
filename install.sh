#!/usr/bin/env bash
# Installs cliuwan for the current user and, optionally, registers the Claude Code skill
# that makes it discoverable to the agent.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHARE_DIR="${HOME}/.local/share/cliuwan"
BIN_DIR="${HOME}/.local/bin"
SKILLS_DIR="${HOME}/.claude/skills"

echo "==> Building"
(cd "${REPO_DIR}" && ./gradlew build --console=plain -q)

JAR="${REPO_DIR}/build/libs/cliuwan.jar"
if [[ ! -f "${JAR}" ]]; then
  echo "Error: ${JAR} was not produced" >&2
  exit 1
fi

echo "==> Installing the jar into ${SHARE_DIR}"
mkdir -p "${SHARE_DIR}" "${BIN_DIR}"
install -m 644 "${JAR}" "${SHARE_DIR}/cliuwan.jar"

echo "==> Installing the launcher into ${BIN_DIR}/cliuwan"
install -m 755 "${REPO_DIR}/bin/cliuwan" "${BIN_DIR}/cliuwan"

# The Claude Code skill is optional: only install it if Claude Code is set up, so this
# script stays useful for people who just want the CLI.
if [[ -d "${HOME}/.claude" ]]; then
  echo "==> Registering the Claude Code skill in ${SKILLS_DIR}/cliuwan"
  mkdir -p "${SKILLS_DIR}"
  rm -rf "${SKILLS_DIR}/cliuwan"
  cp -R "${REPO_DIR}/.claude/skills/cliuwan" "${SKILLS_DIR}/cliuwan"
else
  echo "==> Claude Code not detected (~/.claude missing); skipping the skill"
fi

echo
echo "Installation complete."
echo

if ! command -v cliuwan >/dev/null 2>&1; then
  echo "NOTE: ${BIN_DIR} is not on your PATH. Add this to your shell profile:"
  echo "  export PATH=\"\${HOME}/.local/bin:\${PATH}\""
  echo
fi

# Credential setup is offered here because it is the step that actually makes the CLI
# usable, and leaving it as homework means it never gets done.
#
# The CLI itself is asked rather than checking the path by hand, so there is only one
# place that knows where credentials live.
if "${BIN_DIR}/cliuwan" config >/dev/null 2>&1; then
  echo "Credentials already configured."
  "${BIN_DIR}/cliuwan" config | grep "Credentials source:" || true
  echo "To change them:  cliuwan login"
elif [[ "${CLIUWAN_SKIP_LOGIN:-0}" == "1" ]] || [[ ! -t 0 ]]; then
  # With no interactive terminal (or when explicitly disabled) the password cannot be
  # read without echoing it.
  echo "Next step: set up your credentials with"
  echo "  cliuwan login"
else
  echo "Kiuwan credentials are not configured yet."
  read -r -p "Set them up now? [Y/n] " answer
  case "${answer}" in
    [nN]*)
      echo "Fine. Whenever you are ready:  cliuwan login"
      ;;
    *)
      echo
      "${BIN_DIR}/cliuwan" login || {
        echo
        echo "Login did not complete. You can retry with:  cliuwan login"
      }
      ;;
  esac
fi

echo
echo "Verify the installation with:  cliuwan config"
