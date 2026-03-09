# Cookie Policy

Last updated: March 9, 2026

This Cookie Policy applies to the PulseRoom video-chat stack and any service deployed from this repository.

This document is a minimal operational draft for the stack and is not legal advice. It should be reviewed by the service operator before public launch.

## 1. Current storage model

The current implementation uses only storage that is necessary to authenticate a user, maintain the active browser session, and authorize room access.

The stack does not currently set first-party analytics or advertising cookies.

## 2. What the stack uses

- session-scoped browser storage for OAuth state, nonce, and active sign-in tokens
- session-scoped browser storage for room authorization during the active browser tab session
- provider-managed cookies on third-party domains, such as Google, where needed to complete sign-in

## 3. Why there is no optional consent banner

At the moment, the stack is limited to storage that is necessary to provide the sign-in and room experience explicitly requested by the user.

If optional analytics, advertising, or personalization technologies are added later, the operator should review the stack again and implement consent controls before enabling those technologies.

## 4. User controls

Users can sign out, close the browser tab, or clear site data in the browser to remove session-scoped storage.

Blocking provider cookies on third-party identity domains may prevent sign-in from functioning correctly.

## 5. Review expectation

This policy should be reviewed whenever the stack adds analytics, telemetry, embedded third-party widgets, consent tooling, or additional sign-in providers.
