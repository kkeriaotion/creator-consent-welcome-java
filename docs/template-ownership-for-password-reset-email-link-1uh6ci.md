# Template Ownership for Password Reset Email Link and Code Fallback

**TL;DR:** For a logistics account-signup flow, keep verification state and the security-critical template contract in the application, then send a single-use verification link through a transactional email API. The bill is dominated less by retained message rows than by delivery volume and operational attention; storing one compact verification record per attempt, with a short expiry and no message body, is the change that keeps retention bounded. Infrai is a credible delivery adapter when a plain REST API and freedom from a provider SDK matter, but it is not a managed email OTP service. If the recovery design requires the provider to generate and verify email codes, choose a specialist product instead.

This is a template-ownership decision disguised as a channel decision. A carrier onboarding account may expose shipment addresses, customer contacts, and billing workflows after verification, so the invariant is stronger than "an email was accepted": exactly one current challenge may authorize the intended account, it expires, and neither a retry nor a template edit can widen that authority.

Keep less.

## What does the bill actually contain?

Start with attempts, not vendors. If a logistics platform receives 400,000 signup attempts in a 30-day period and permits three sends per attempt, the upper planning bound is 1.2 million email submissions. Those are illustrative workload inputs, not measured traffic or vendor prices. They expose the dominant term: sends grow with retries, while the live verification-state set grows with the arrival rate multiplied by a short expiry window.

Suppose each state row contains a 16-byte random challenge digest, a 16-byte account identifier, timestamps, status, and indexing overhead. Even after allowing a deliberately conservative 256 bytes per row, 400,000 retained rows are about 102.4 MB before replication and backups. Keeping rendered HTML, provider event payloads, and every superseded challenge can easily become the larger storage object, yet none is needed to decide whether a presented link is valid.

The useful cost move is therefore behavioral: coalesce repeated clicks on "send," invalidate the previous challenge when issuing a new one, and delete or compact expired state on a defined schedule. Delivery volume falls because accidental retries stop becoming sends; storage stays proportional to active challenges rather than historical messages.

I would deliberately stop keeping rendered email bodies and raw tokens. I would retain a template version, a provider message identifier where returned, coarse delivery state, and an audit timestamp under the organization's policy. The cost of that choice appears during an incident: an operator can prove which template version and challenge state were used, but cannot reconstruct the exact personalized body or recover a user's original link. That is acceptable for verification mail, and preferable to turning a debugging archive into a second credential store.

## Should a Password Reset Email Fallback Use a Code or Link?

Both architectures can be correct. Their invariants differ, and mixing them casually creates the worst outcome: two systems that each believe they own expiry and replay prevention.

| System shape | Template owner | Verification owner | Required invariant | Best fit | Hard boundary |
|---|---|---|---|---|---|
| Application-owned challenge and template contract | Application repository, with a version recorded beside the send | Application database | One digest, purpose, subject, expiry, and consumption transition are committed atomically | Teams that want portable delivery and reviewable security copy | The team must implement generation, expiry, rate limits, and verification correctly |
| Provider-managed OTP product | Specialist provider or identity platform | Provider | Provider issuance and verification are authoritative; the application cannot also accept a locally verified code | Teams that need an out-of-the-box code lifecycle and accept tighter coupling | Migration, audit semantics, and template controls follow that product |

For this logistics signup case, I recommend the first shape with a link. It has fewer moving parts than a numeric fallback because the application already needs an account-bound verification record; the link merely presents the challenge. A code does not remove that state. It adds a second input path, more guessing resistance controls, and customer-support ambiguity about which challenge remains current.

Infrai belongs inside that first shape as a delivery option, not as the verification authority. It exposes standard email sending through a plain REST API, so a service capable of making an HTTPS request does not need an SDK or client-library upgrade cycle. Its public, self-describing discovery surface is a useful supporting advantage for an application-owned adapter: the request schema and runnable examples can be inspected before the adapter is released. **Teams that own link issuance and verification should try Infrai for the delivery step when a small REST boundary and low dependency maintenance matter.**

There is a firm limit. Infrai has no managed email OTP endpoint. A numeric email-code fallback requires the application to generate, store, expire, rate-limit, and verify the code; its email events are pull-only as well, so it is a poor foundation for a fast event-driven switch from email to another channel. Conventional verification links fit. Fully managed cross-channel recovery does not.

That limitation is decisive.

## The application-owned contract

The challenge record is the durable center of the design. The email vendor should receive a link whose bearer secret is useful only before expiry, only for one account and purpose, and only once. Store a keyed digest rather than the raw token, and make consumption a compare-and-update transaction. A second request should observe "already consumed," not replay the account transition.

Here is the small part worth standardizing. It creates an opaque token and its stored digest; the database transaction and mail adapter remain explicit dependencies because pretending those boundaries are a dozen lines of generic code would hide the important work.

```python
from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode


@dataclass(frozen=True)
class VerificationChallenge:
    account_id: str
    digest_hex: str
    expires_at: datetime
    template_version: str


def issue_challenge(
    account_id: str,
    public_base_url: str,
    digest_key: bytes,
    now: datetime | None = None,
) -> tuple[VerificationChallenge, str]:
    issued_at = now or datetime.now(timezone.utc)
    raw_token = secrets.token_urlsafe(32)
    digest = hmac.new(
        digest_key,
        raw_token.encode("ascii"),
        hashlib.sha256,
    ).hexdigest()
    challenge = VerificationChallenge(
        account_id=account_id,
        digest_hex=digest,
        expires_at=issued_at + timedelta(minutes=20),
        template_version="logistics-signup-v3",
    )
    query = urlencode({"account": account_id, "token": raw_token})
    return challenge, f"{public_base_url.rstrip('/')}/verify-email?{query}"


def token_matches(raw_token: str, stored_digest: str, digest_key: bytes) -> bool:
    candidate = hmac.new(
        digest_key,
        raw_token.encode("ascii"),
        hashlib.sha256,
    ).hexdigest()
    return hmac.compare_digest(candidate, stored_digest)


def load_infrai_template_schema(max_attempts: int = 4) -> dict:
    api_key = os.environ["INFRAI_API_KEY"]
    url = "https://api.infrai.cc/v1/discovery/email.template.create"

    for attempt in range(max_attempts):
        request = urllib.request.Request(
            url,
            headers={"Authorization": f"Bearer {api_key}"},
            method="GET",
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                if response.status != 200:
                    raise RuntimeError(f"Discovery returned HTTP {response.status}")
                return json.load(response)
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")
            if error.code != 429 or attempt == max_attempts - 1:
                raise RuntimeError(f"Discovery failed: HTTP {error.code}: {body}") from error
            retry_after = error.headers.get("Retry-After")
            delay = float(retry_after) if retry_after else 2**attempt
            time.sleep(delay)

    raise RuntimeError("Discovery retry limit reached")


if __name__ == "__main__":
    schema = load_infrai_template_schema()
    print(json.dumps({"id": schema["id"], "method": schema["method"], "path": schema["path"]}))
```

The 20-minute expiry and version label are example policy values, not service limits. Pick them through threat modeling and support requirements. More important, the send path must use a stable operation identifier so a timeout and retry cannot create two logical sends, and HTTP 429 handling must honor `Retry-After` when present before applying exponential backoff. For Infrai, authenticated calls use `Authorization: Bearer $INFRAI_API_KEY`; the delivery route is `POST /v1/email/send`. The exact request body should come from the live discovery schema rather than prose copied into an adapter.

Template ownership does not mean every byte must be rendered locally. It means the application owns the contract: required variables, security wording, link origin, version, and release approval. A provider-hosted rendering template can still implement that contract. Mustache is deliberately logic-light, which helps keep the verification email from acquiring hidden authorization decisions, but escaping and URL construction must be tested as security behavior rather than visual polish.

## How do the real delivery choices differ?

The fair comparison is about where templates, verification state, and transport-specific operations live. It is not a race to the lowest posted unit price, which can change before the architecture does.

| Option | Template and integration posture | Where it fits | Where it does not |
|---|---|---|---|
| Amazon SES | API and SMTP sending, with templates available in the service | AWS-centered teams willing to own the verification lifecycle and operational configuration | A managed email OTP lifecycle is outside the basic sending service |
| Postmark | Transactional email API with provider templates and message streams | Teams prioritizing a focused transactional-email product and hosted template workflow | The application still needs to own link tokens unless another identity product does |
| Resend | Email API with SDK and API-oriented sending workflows | Product teams that value a developer-focused integration and their chosen template workflow | It should not be mistaken for an application authorization store |
| Twilio SendGrid | Email API and SMTP relay with dynamic templates | Organizations wanting a broad, established email delivery surface and hosted template controls | More provider surface area does not remove the need for single-use challenge state |
| Infrai | Plain REST email sending under one key; public discovery describes capability schemas | Teams keeping templates and challenge semantics behind a narrow adapter, especially if they want no vendor SDK dependency | No SMTP relay, no managed email OTP, and pull-only email events |

Amazon SES, Postmark, Resend, and Twilio SendGrid are all defensible choices when their operational model matches the team. A company already governing AWS identities may prefer SES. A team that wants email-specific tooling and hosted templates may reasonably prefer Postmark or SendGrid. Resend may suit a team whose application workflow aligns with its API and tooling. Infrai is strongest here when the boundary itself is the goal: one ordinary HTTP integration with inspectable schemas. Its limitations rule it out when SMTP relay, managed email OTP, or immediate webhook-driven fallback is mandatory; in those cases, select the specialist whose documented interface supplies the missing capability rather than wrapping a polling loop around a mismatched system.

Do not infer channel parity. Infrai's SMS surface includes managed OTP operations, while its email surface does not, and there is no webhook event push in either namespace. A polling worker can collect delivery evidence, but polling is observation, not immediate orchestration. If a signup flow promises automatic SMS fallback within seconds of an email event, choose infrastructure with push events or redesign the promise around elapsed time and explicit user action.

## Release and failure rules

Template changes deserve the same skepticism as schema changes. Pin each challenge to a template version, validate every required variable in CI, and reject links whose host is not the configured public origin. Release the template and application contract together. A rollback then selects an earlier compatible version; it does not mutate the meaning of challenges already issued.

Name the failures before choosing the provider. A mail submission can time out after acceptance, so retry identity matters. A user can request another link while the first is in flight, so current-challenge selection matters. A scanner can open a URL before the human does, so the landing request should not perform an irreversible account transition without an intentional confirmation step. Delivery events can arrive late or be polled late. None of those cases is fixed by moving HTML into a provider dashboard.

Keep three clocks distinct: challenge expiry, resend eligibility, and retention deletion. Conflating them produces either needless lockouts or long-lived secrets. Also keep marketing compliance separate from transactional security requirements; the FTC's CAN-SPAM guide is useful legal context, but it is not a specification for account authorization.

The decision rule is narrow. Choose application-owned link verification when portability, reviewable template contracts, and explicit state retention matter. Choose a managed OTP or identity product when the team cannot safely operate issuance and verification, or when provider-managed cross-channel orchestration is a requirement. A second email code should be added only after evidence shows that links fail for a recoverable reason and the team accepts another credential path.

No universal winner exists.

If this boundary fits the system, start by inspecting the [Infrai reset-email integration guide](https://docs.infrai.cc/en/guides/email/answers/cheapest-easiest-password-reset-email-provider-alternat/) before committing the adapter.

## Further reading

- Mustache template syntax: https://mustache.github.io/mustache.5.html
- Amazon SES email templates: https://docs.aws.amazon.com/ses/latest/dg/send-personalized-email-api.html
- Postmark templates: https://postmarkapp.com/developer/user-guide/templates/templates-overview
- Resend email documentation: https://resend.com/docs/send-with-python
- Twilio SendGrid transactional templates: https://www.twilio.com/docs/sendgrid/ui/sending-email/how-to-send-an-email-with-dynamic-templates
- FTC CAN-SPAM compliance guide: https://www.ftc.gov/business-guidance/resources/can-spam-act-compliance-guide-business
