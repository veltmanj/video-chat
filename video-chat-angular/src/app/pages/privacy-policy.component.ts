import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-privacy-policy',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <main class="policy-page">
      <section class="policy-card">
        <p class="policy-kicker">Legal</p>
        <h1>Privacy Policy</h1>
        <p class="policy-meta">Last updated: March 10, 2026</p>

        <p>
          PulseRoom processes only the data needed to sign users in, provide room access, and support the
          current social profile features exposed by this stack. This page summarizes the current implementation
          and should be reviewed by the operator before public launch.
        </p>

        <h2>Data we may process</h2>
        <ul>
          <li>Identity-provider data such as your account subject, email address, display name, and avatar URL when supplied.</li>
          <li>Profile and social data such as handle, biography, visibility setting, follows, access grants, posts, and reactions.</li>
          <li>Authentication and session data such as OAuth state, nonce values, ID tokens, access tokens, and room authorization tokens.</li>
          <li>Operational and security records reasonably needed to run, protect, and troubleshoot the service.</li>
        </ul>

        <h2>Why this data is used</h2>
        <p>
          The current stack uses this data to authenticate users, create and maintain profiles, deliver room and
          social features, validate access, investigate abuse, and keep the service available.
        </p>

        <h2>Third-party providers</h2>
        <p>
          Sign-in may rely on providers such as Google, and deployments may rely on hosting or infrastructure
          providers selected by the operator. Those services operate under their own terms and privacy practices.
        </p>

        <h2>Retention and operator review</h2>
        <p>
          Data may be retained for active service use, security review, and operator-managed backups. This
          repository does not yet provide a complete production deletion workflow, so operators should define
          retention periods, support contacts, and jurisdiction-specific rights handling before public launch.
        </p>

        <a class="back-link" routerLink="/login">Back to sign in</a>
      </section>
    </main>
  `,
  styles: [`
    :host {
      display: block;
      min-height: 100vh;
      background:
        radial-gradient(circle at top left, rgba(56, 189, 248, 0.18), transparent 28%),
        linear-gradient(135deg, #020617 0%, #0f172a 45%, #111827 100%);
      color: #e5e7eb;
    }

    .policy-page {
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 2rem;
    }

    .policy-card {
      width: min(100%, 760px);
      padding: clamp(1.6rem, 4vw, 2.5rem);
      border-radius: 1.75rem;
      border: 1px solid rgba(148, 163, 184, 0.18);
      background: rgba(15, 23, 42, 0.76);
      backdrop-filter: blur(14px);
      box-shadow: 0 32px 80px rgba(2, 6, 23, 0.45);
    }

    .policy-kicker,
    .policy-meta {
      margin: 0;
      text-transform: uppercase;
      letter-spacing: 0.16em;
      font-size: 0.72rem;
      color: #7dd3fc;
    }

    h1,
    h2,
    p,
    ul {
      margin: 0;
    }

    h1 {
      margin-top: 0.5rem;
      font-size: clamp(2.2rem, 5vw, 3.4rem);
      letter-spacing: -0.04em;
      line-height: 0.96;
    }

    .policy-meta {
      margin-top: 0.85rem;
    }

    h2 {
      margin-top: 1.5rem;
      font-size: 1rem;
      color: #f8fafc;
    }

    p,
    li {
      margin-top: 0.75rem;
      line-height: 1.65;
      color: rgba(226, 232, 240, 0.84);
    }

    ul {
      padding-left: 1.2rem;
    }

    .back-link {
      display: inline-flex;
      margin-top: 1.75rem;
      color: #facc15;
      font-weight: 700;
      text-decoration: none;
    }

    .back-link:hover {
      text-decoration: underline;
    }
  `]
})
export class PrivacyPolicyComponent {}
