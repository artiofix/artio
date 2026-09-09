# Security Policy

Artio is a FIX engine built on [Aeron](https://aeron.io), maintained by [Adaptive](https://weareadaptive.com).

## Reporting a Vulnerability

Please report suspected security vulnerabilities using **[GitHub Security Advisories](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability)**.

If you are unable to use GitHub Security Advisories, you can email security@weareadaptive.com.

Please don't report a suspected vulnerability through a public GitHub issue, pull request, or discussion — a public report can expose users before a fix or mitigation exists.

Where possible, please include:

- Whether the issue is in regular FIX or FIXP (iLink3/Binary EntryPoint), and the version
- A description of the issue and its potential impact
- Reproduction steps or a minimal proof of concept
- Any evidence of active exploitation

We will acknowledge reports. Acknowledgement isn't a commitment that the report is valid, that a fix will be provided, or to a disclosure date.

## Classifying reports

Not every defect is a security vulnerability. When triaging a report, maintainers ask whether it affects:

- **Confidentiality** — does it leak data, secrets, or internal system details to someone who shouldn't have them?
- **Integrity** — does it let someone modify data or state, or bypass an authorization or validation check, that they shouldn't be able to?
- **Availability** — could it crash, hang, or meaningfully degrade the system for others?

and whether it does so by crossing a trust boundary or bypassing a control Artio or Aeron itself provides (see [deployment assumptions](#scope-and-deployment-assumptions)), rather than requiring an adversary or capability outside that trust model — a compromised host, OS, credentials, or network; a misconfiguration; or a deployment that contradicts those assumptions. Reports that don't meet this bar are usually hardening suggestions, ordinary bugs, or out of scope.

This also excludes automated scanner findings that simply flag an already-known CVE in a dependency — please report those to the dependency's own project, not as an Artio vulnerability.

## Scope and deployment assumptions

- **Artio runs on top of Aeron Transport and Aeron Archive** — communication between the Artio Engine and Artio Libraries uses Aeron underneath, so Artio inherits Aeron's own trust model rather than defining a separate one. See [Aeron's own `SECURITY.md`](https://github.com/aeron-io/aeron/security/policy) for the underlying Transport/Archive assumptions (no authentication or encryption on transport traffic by default; enable Aeron Transport Security or your own network-layer encryption on untrusted networks).
- **Artio doesn't implement TLS for FIX connections.** If you need encrypted FIX (FIXS), terminate TLS externally — e.g. stunnel or a load balancer — in front of Artio.
- **Artio authenticates FIX sessions via an application-supplied `AuthenticationStrategy`**, invoked with the credentials from an incoming Logon message — this only applies when Artio is acting as the Acceptor; Artio does not itself authenticate the remote counterparty when initiating a connection. Passwords are otherwise masked before being logged. Aeron/Artio don't provide a default implementation — the authentication logic itself is the application's responsibility, same as Aeron Archive/Cluster's own auth model.
- **Artio does not checksum messages in flight.** Deployments on non-ECC memory are correspondingly more exposed to message corruption than they would be with in-flight checksumming — a deployment/hardware consideration, not a code-level vulnerability by itself.
- **Operators are responsible for securing the host, OS, credentials, access controls, and network Artio and Aeron run on.** A compromised host or trusted network isn't an Artio vulnerability by itself.

## Triage and handling

Maintainers, with Adaptive's security function where relevant, determine:

1. Whether it affects Artio itself, or an external dependency/deployment choice;
2. Whether it meets the vulnerability bar above;
3. Affected versions and deployment scenarios;
4. Severity and practical exploitability;
5. The right response — fix, mitigation, documentation, or none; and
6. Whether a public advisory is warranted.

We won't disclose report details publicly while remediation is in progress, except where required by law or necessary to protect users.

## Security advisories, CVEs, and disclosure

Once a vulnerability is confirmed, we follow a coordinated disclosure sequence:

1. Develop and review the fix privately, in the draft GitHub Security Advisory.
2. Ship the fix in a release.
3. Publish the advisory, requesting a CVE where warranted.
4. The CVE is indexed into public vulnerability databases (e.g. NVD) once assigned.

An advisory may include affected component(s), affected/fixed versions, severity and impact, a description, mitigation/upgrade guidance, and credit to the reporter (unless they prefer anonymity). Credit is based on the details provided in the original report; we generally won't update it in response to later requests. We won't publish exploit details that would put un-upgraded users at unnecessary risk.

### EU Cyber Resilience Act reporting

Separately from the disclosure process above: if a vulnerability is confirmed to be **actively exploited**, or an incident meets the [EU Cyber Resilience Act](https://eur-lex.europa.eu/eli/reg/2024/2847/oj/eng)'s threshold for a severe security incident, Adaptive additionally reports it to the relevant EU authorities — a 24-hour early warning, a 72-hour notification, and a final report once a fix is available. This is triggered by **evidence of exploitation, not by knowledge of a vulnerability alone**, and runs alongside, not instead of, the public disclosure process above.

## Supported versions

Upgrade to the latest release where practical — the primary remediation path today. Backports to older release lines aren't guaranteed unless stated in the release notes or advisory. 
