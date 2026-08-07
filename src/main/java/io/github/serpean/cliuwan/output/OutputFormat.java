package io.github.serpean.cliuwan.output;

/** Output formats the CLI supports. */
public enum OutputFormat {
    /** Indented JSON. The default: pipeable into jq and stable across versions. */
    json,
    /** Plain text, for reading. */
    text,
    /** Markdown tables, to paste into a PR or a report. */
    md
}
