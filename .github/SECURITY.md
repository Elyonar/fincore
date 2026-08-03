# Security Policy

fincore is banking infrastructure; we treat security reports with the highest
priority.

## Reporting a vulnerability

**Do not open a public issue for security vulnerabilities.**

Report privately via GitHub Security Advisories ("Report a vulnerability" on
this repository) or by email to the maintainer. You will receive an
acknowledgement within 72 hours.

Please include: affected module/version, reproduction steps, and impact
assessment if you have one. We ask for coordinated disclosure: give us
reasonable time to ship a fix before any public write-up, and we will credit
you in the advisory unless you prefer otherwise.

## Scope notes

- The platform is pre-1.0 and not yet certified for production use; there are
  no supported release lines yet. Until then, `main` is the only line and
  fixes land there.
- Never include real credentials, customer data, or a production institution's
  configuration in reports or reproductions.
