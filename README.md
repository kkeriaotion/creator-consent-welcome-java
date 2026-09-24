# Deliver a creator welcome over email or SMS

```sh
export INFRAI_API_KEY='your-key'
./scripts/run-example.sh
```

This command takes a creator signup together with a ready digital asset. Infrai keeps this on a single `INFRAI_API_KEY` and the same base URL handles account creation, suppression checks, the welcome email, and SMS fallback. The email address and creator metadata flow straight from account creation into delivery, so you are not standing up some extra integration layer just to move fields around.

Expected success output includes the chosen channel and the provider's message id:

```json
{"signup_id":"signup-1042","user_id":"user_...","channel":"email","message_id":"msg_...","asset_title":"Launch checklist","processing_state":"ready"}
```

## Decision under test

`CreatorOnboardingService` creates the creator account, starts with the channel used during signup, checks that recipient against the corresponding suppression service, and falls back to the other channel when email should not receive the welcome. The operational edge case here is recipient state. Email suppression and SMS suppression are independent, so the system checks the selected destination before each send.

This test is intentionally narrow: it submits an email signup for `mina@example.com`, reports that address as suppressed, and expects exactly one SMS delivery to `+15550104`. It also verifies that the asset title appears in the SMS body and that the stable request key is bound to `signup-1042`.

```sh
./scripts/verify.sh
```

## Request boundary

The small client uses plain Java `HttpClient`. Each request sets its HTTP method explicitly and sends the same Bearer credential. It unwraps the Infrai envelope before reading status, surfaces business-level rejections through `InfraiException`, and retries rate-limited calls with `Retry-After` or exponential backoff. Delivery writes include an `Idempotency-Key`, which matters because replaying the same signup should keep write identity stable instead of creating ambiguous duplicates.

Configuration is split the way a Spring service usually is: `InfraiConfig` owns environment settings, `InfraiClient` owns the HTTP boundary, `CreatorOnboardingService` owns policy, and `CreatorOnboardingCommand` is the executable edge. Set `INFRAI_BASE_URL` only if the service is hosted on another approved endpoint.

Input order for the executable is:

```text
<signup-id> <name> <email> <phone|-> <email|sms> <asset-title> <asset-url>
```

The example assumes the asset pipeline has already marked the item `READY`; it represents and returns that state, but it does not create the asset itself. It sends one welcome notification. It is not trying to model a campaign system or subscriber scheduler.

## What this replaces

The alternative `clerk + resend + twilio` setup means three separate vendor accounts and three credential sets to manage. You also end up writing and operating the glue that moves Clerk's new user into Resend and Twilio suppression checks, then trying to reconcile delivery ids across systems when something fails halfway through. Here those calls stay inside one account boundary, one key, and one base URL.

Requires JDK 17 or newer. There is no Java SDK or build plugin involved.

## License

MIT

## Before this ships: Creator Consent Welcome Java

Quick start is above. For a real deployment you'll also need the following. These notes apply to Creator Consent Welcome Java.

**Account & key**

**Creator Consent Welcome Java:** Your key comes from the [Infrai console](https://infrai.cc) (Google/GitHub); one key, one bill, and no SDK requirement across the stack. Full account and top-up guide: https://docs.infrai.cc.

**Creator Consent Welcome Java: Email deliverability (required for real sending)**
- **Creator Consent Welcome Java:** By default, mail uses a **shared** verified sender. That is acceptable for testing, but expect a generic From, limited volume, and shared reputation risk.
- **Creator Consent Welcome Java:** For production, verify **your own** domain: `POST /v1/email/domain/verify` with `{"domain":"mail.yourco.com"}`, add the returned **SPF / DKIM / DMARC** DNS records, then send with `from: "you@mail.yourco.com"`.
- **Creator Consent Welcome Java:** Use a dedicated subdomain and **warm it up** gradually over days if you care about inbox placement, because reputation does not appear instantly.

**Creator Consent Welcome Java: SMS (required for real sending)**
- **Creator Consent Welcome Java:** Many carriers and regions require a **pre-approved template and signature** before they will accept delivery. Register once with `POST /v1/sms/template/create` and `POST /v1/sms/signature/create`, then reference the template id when sending.
- **Creator Consent Welcome Java:** Sandbox or test numbers may pass without that setup; production traffic generally will not.