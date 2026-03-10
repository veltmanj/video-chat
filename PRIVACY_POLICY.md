# Privacy Policy

Last updated: March 10, 2026

This Privacy Policy applies to the PulseRoom video-chat stack and any service deployed from this repository.

This document is a minimal operational draft for the stack and is not legal advice. It should be reviewed by the service operator before public launch.

## 1. Scope

This policy describes the categories of personal data the current stack may process when a user signs in, joins the service, creates a social profile, and uses related room and social features.

The exact deployment may differ. If an operator adds analytics, recordings, support tooling, or new integrations, this policy should be updated before those features are enabled.

## 2. Data the stack may process

The current implementation may process:

- identity data received from the configured sign-in provider, such as the stable subject identifier, email address, display name, and avatar URL when provided
- account and profile data stored by the service, such as handle, biography, profile visibility, follow relationships, access grants, posts, and reactions
- authentication and session data, such as OAuth state values, nonce values, ID tokens, access tokens, and room authorization tokens needed for the active session
- service and security metadata, such as request logs, health checks, and error records reasonably needed to operate, secure, and troubleshoot the stack
- operator-managed backups of the social database where backup scripts are used

## 3. Where the data comes from

The stack currently receives personal data from:

- the user directly, for example when the user edits a profile, writes a post, follows another profile, or grants profile access
- the configured identity provider, such as Google, during sign-in
- the operator's infrastructure and security systems, which may generate technical logs and operational records

## 4. Why the data is used

The current stack uses personal data to:

- authenticate users and verify that the sign-in token is valid for this application
- create and maintain the user profile associated with the authenticated account
- provide social features such as profile discovery, follows, private-profile access grants, posts, and reactions
- deliver requested room and service functionality
- protect the service, investigate abuse, debug failures, and maintain availability
- back up and restore service data where the operator uses the included maintenance scripts

## 5. Legal basis and operator responsibility

The service operator should replace this section with the legal bases and jurisdiction-specific language that apply to the actual deployment.

Depending on how the service is offered, the operator may rely on contractual necessity, legitimate interests, legal obligations, or user consent for specific processing activities. This draft does not determine which basis applies in a live deployment.

## 6. Sharing and third-party providers

The stack may involve third-party providers that process data on the operator's behalf or under their own terms, including:

- identity providers used for authentication, such as Google
- hosting, networking, and infrastructure providers used to run the service

The current stack is not designed to sell personal data or to run first-party advertising based on user profiles.

Operators should expand this section before public launch if they add subprocessors, customer support platforms, analytics tools, or external moderation tooling.

## 7. Retention

Data may be retained for as long as needed to provide the service, maintain security, satisfy legal obligations, resolve disputes, and preserve operator backups.

The current repository does not implement a complete end-user self-service deletion workflow. Operators should define retention periods and deletion procedures appropriate for the production deployment before launch.

## 8. Security

The stack includes authentication checks, HTTPS support in the Docker deployment, and JWT validation for configured providers, but no system can guarantee absolute security.

Users should protect their sign-in methods and use trusted devices and networks. Operators should review access controls, secret management, backup handling, and logging before production use.

## 9. User choices and rights

Depending on the jurisdiction and deployment, users may have rights to access, correct, delete, export, restrict, or object to certain processing of their personal data.

Because this repository is a deployable stack rather than a single hosted service, the operator should publish the correct request channel and response procedure for the live deployment.

## 10. Cookies and related storage

For details about cookies and browser storage used by the current implementation, see the [Cookie Policy](COOKIE_POLICY.md).

## 11. Contact

Before public launch, the operator should replace this section with a real privacy contact, controller identity, and any legally required representative or supervisory authority information.

