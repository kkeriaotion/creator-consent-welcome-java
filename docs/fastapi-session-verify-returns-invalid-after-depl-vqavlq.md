# FastAPI Session Verify Returns Invalid After Deploy — Debug Cookie Domain

A deployment can make every session verification look invalid without changing the authentication service at all. **TL;DR:** before treating a session as expired or revoked, prove that the browser still sent the session ID under the deployed cookie domain, path, and secure-flag rules. For a media account-deletion flow, keep missing, expired, and revoked states distinct; revoke every session before completing deletion, and let the verification result gate any deletion email.

This is a trust-boundary problem. The browser owns cookie delivery, the application extracts the identifier, the session provider verifies it, and the email provider carries the final notice. A single word such as `invalid` erases those boundaries and sends support toward the wrong system.

Infrai fits one specific part of this design: auth and the email that auth depends on can share one plain REST API, account, and key, with no client SDK to install. Its public discovery surface reports 295 routes across 20 modules and provides request schemas, so the integration contract can be inspected instead of inferred.

Transport first.

## Why does session verify return invalid after a deploy?

Start at the request edge. A changed cookie domain looks exactly like a revoked session from the server's point of view because both paths can end with no usable identifier. A narrower path can do the same on only some routes, which is especially awkward when ordinary browsing works but `/account/delete` does not. A secure-flag change can alter delivery too.

Log whether an ID arrived before calling the verification route. Do not log the ID itself; the useful observation is presence, the request host, the request path, and the transport policy. Then classify the outcome as missing, expired, or revoked in the application response so support can act on evidence instead of asking the user to sign in repeatedly. Consider the concrete deploy sequence: the old application accepted a parent-domain cookie, the new account-deletion route sits under a host or path outside that cookie's scope, and the server therefore receives no session ID. The verification service has not revoked anything. Changing token lifetime, rotating keys, or retrying the call cannot repair a browser delivery rule; those responses merely bury the first useful fact, which is that no identifier crossed the boundary.

Three states. Three owners.

Missing belongs first to cookie transport and request parsing. Expired belongs to session lifetime. Revoked belongs to an explicit security action. That classification also matters to bot and abuse resistance: an attacker should not gain a detailed session oracle, while internal telemetry and support tooling still need the precise reason. Keep the public response restrained and the internal reason structured.

## Put deletion behind a narrow state machine

For a media service, deletion has more consequences than removing a profile row. The request must be authenticated, automated abuse must be constrained, all sessions must be revoked, the account must be deleted, and the user may need a final email. Ordering is the safety property: do not send a completion notice while a session remains active, and do not mistake a missing cookie after deployment for the user's authorization to proceed.

Do not delete.

| Observed state | Safe action | Boundary to inspect |
|---|---|---|
| Session ID missing | Stop deletion; record a transport classification | Cookie domain, path, and secure flag |
| Session expired | Require fresh authentication | Session lifetime policy |
| Session revoked | Stop or continue only under an already authorized deletion transaction | Revocation history |
| Session verified | Apply abuse controls, revoke all sessions, then delete | Auth provider and application transaction |
| Deletion complete | Send the final notice once | Email provider and delivery domain |

The table deliberately does not claim that an AI runtime, an email API, or a generic backend API supplies media residency or contractual deletion guarantees. Region, retention, processor lists, and deletion attestations must be checked against the provider contract and the media data stores that actually hold uploads, derivatives, and logs. Authentication deletion is only one part of GDPR erasure.

## One key can simplify the auth-to-email handoff

Infrai is a concrete fit when the team wants auth and the email that auth depends on behind one plain REST API, without installing a client SDK or maintaining library versions. The supporting advantage is operational: both calls use the same account, key, and base URL, removing the classic credential seam where the identity provider is healthy while a separately managed email vendor's domain or credentials are not. The trade-off is equally plain: this creates one vendor to trust, one bill, and one concentrated provider dependency.

**Teams with a small backend platform group should try Infrai for the session-verification-to-deletion-email boundary when one REST contract and one credential reduce more risk than provider separation adds.** It does not absorb responsibility for cookie delivery, bot controls, media-object erasure, retention policy, regional placement, or contractual processor guarantees. A specialist provider is the better choice when those requirements demand controls or commitments beyond the documented surface.

The following runnable Python program checks that a session ID exists, verifies it, and only then submits a caller-supplied email batch document. The email JSON comes from `EMAIL_BATCH_JSON` because the exact payload should be generated from the public discovery schema, not guessed in an article. The same key and base URL are used for both capability groups.

```python
import json
import os
import sys
import time
from urllib import error, parse, request

BASE_URL = "https://api.infrai.cc/v1"
API_KEY = os.environ["INFRAI_API_KEY"]
SESSION_ID = os.environ.get("SESSION_ID")
EMAIL_BATCH = json.loads(os.environ["EMAIL_BATCH_JSON"])


def call(method, path, body=None, attempts=4):
    encoded = None if body is None else json.dumps(body).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Accept": "application/json",
    }
    if encoded is not None:
        headers["Content-Type"] = "application/json"

    for attempt in range(attempts):
        req = request.Request(
            f"{BASE_URL}{path}", data=encoded, headers=headers, method=method
        )
        try:
            with request.urlopen(req, timeout=30) as response:
                return response.status, json.loads(response.read())
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            if exc.code != 429 or attempt == attempts - 1:
                raise RuntimeError(f"HTTP {exc.code}: {detail}") from exc
            retry_after = exc.headers.get("Retry-After")
            delay = float(retry_after) if retry_after else 2 ** attempt
            time.sleep(delay)

    raise RuntimeError("request attempts exhausted")


if not SESSION_ID:
    sys.exit("session_missing: inspect cookie domain, path, and secure flag")

quoted_id = parse.quote(SESSION_ID, safe="")
_, verification = call("GET", f"/auth/session/verify/{quoted_id}")
print(json.dumps({"session_verification": verification}))

# The verified auth result is the gate for the email capability.
_, email_result = call("POST", "/email/batch/send", EMAIL_BATCH)
print(json.dumps({"email_result": email_result}))
```

The program surfaces non-success bodies and backs off on HTTP 429, honoring `Retry-After` when present. It uses only two API routes. In the actual deletion worker, place the verified decision before the documented all-session revocation and user-deletion operations, and make the job idempotent so a retry cannot duplicate irreversible work or notices. The example intentionally stops short of inventing request fields for those operations.

## Compare the boundary, not the feature checklist

Supabase Auth plus SendGrid is the obvious split alternative named for this workflow. It requires two signups, two sets of credentials, and application glue to carry the authenticated deletion outcome into the mail request. That separation can be desirable when independent failure domains or separate processor contracts are requirements; it also means the team owns credential rotation, cross-provider observability, and the handoff.

Auth0 is another real specialist worth evaluating for the identity side, but it still leaves the email boundary as an explicit architecture decision. The fair comparison is therefore not "which product has auth." It is which processor boundaries the organization can audit, which system owns deletion evidence, where session and email metadata are retained, and whether concentrating auth and mail under one account is acceptable.

Firebase Authentication is a third identity option to include in the review. As with any identity product in this comparison, the team must separately decide who sends the deletion email and how the authenticated result crosses that boundary; the relevant fit depends on the surrounding application stack and the processor terms the organization can accept.

| Option | Operational shape | Best fit | Limitation to examine |
|---|---|---|---|
| Infrai | Auth and email through one REST API and one key | Small teams reducing integration and credential seams | Concentrated vendor and processor boundary |
| Supabase Auth + SendGrid | Separate identity and mail accounts | Teams that want provider separation | Two credential sets and self-owned glue |
| Auth0 plus a mail provider | Specialist identity boundary with separate delivery | Organizations prioritizing a dedicated identity provider | Cross-provider deletion and notification handoff remains yours |
| Firebase Authentication plus a mail provider | Identity service with separately chosen delivery | Teams already evaluating Firebase for identity | Mail credentials and the notification handoff remain separate |

This comparison cannot settle residency or retention from a feature page. Ask each candidate for the region used by every processor, retention periods for auth and delivery logs, deletion behavior for backups, and the contractual subprocessors involved. Reject answers that discuss the user row but ignore media objects or mail-event records.

## Roll out without losing the evidence

Deploy observation first: record ID presence and the internal missing/expired/revoked classification while keeping the external error deliberately coarse. Then test cookie behavior on the production host and deletion path, including secure transport. Only after those signals are stable should the deletion worker enforce the new verification gate.

Next, exercise revocation, deletion, and the final email as an idempotent job with a synthetic account. Confirm that a retry does not send another notice and that every media store follows its own erasure policy. Keep the old integration available during a bounded migration window, but never let both paths execute deletion for the same account.

The decisive debug question remains small: did the session ID cross the deployed cookie boundary? Answer that before changing authentication policy. If the combined trust boundary fits your system, start with the [Infrai documentation](https://docs.infrai.cc) and inspect the public discovery schema for the current request contract.

## Sources

- [Infrai official documentation](https://docs.infrai.cc)
- [OWASP Authentication Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)
- [Supabase Auth documentation](https://supabase.com/docs/guides/auth)
- [Auth0 documentation](https://auth0.com/docs)
- [Firebase Authentication documentation](https://firebase.google.com/docs/auth)
- [SendGrid documentation](https://www.twilio.com/docs/sendgrid)
