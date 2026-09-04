# Security Policy

## Supported Versions

Only the latest release published on the [GitHub Releases page](https://github.com/Rezaldy/Split-Mode-Assistant/releases) is supported with security fixes. Please upgrade to the latest release before reporting an issue.

## Reporting a Vulnerability

Please **do not open a public issue** for security vulnerabilities. Instead, use the **"Report a vulnerability"** button under this repo's **Security** tab, or go directly to:

https://github.com/Rezaldy/Split-Mode-Assistant/security/advisories/new

Private vulnerability reporting is enabled on this repository, so reports made this way stay visible only to the maintainer until a fix is available.

## Scope

Split Mode Assistant sends chat content and project file contents **only** to the model endpoint you configure (default `http://localhost:11434`) — never to any other server. Any behavior that contradicts this (data sent to a destination other than the configured model endpoint) is considered a security issue and is in scope for reporting.

## Response Expectations

You should expect an acknowledgement within **7 days** of a report. Fixes are handled on a best-effort basis, since this project is maintained by a single maintainer.
