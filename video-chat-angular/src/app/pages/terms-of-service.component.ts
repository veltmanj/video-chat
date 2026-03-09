import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-terms-of-service',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <main class="policy-page">
      <section class="policy-card">
        <p class="policy-kicker">Legal</p>
        <h1>Terms of Service</h1>
        <p class="policy-meta">Last updated: March 9, 2026</p>

        <p>
          These terms govern access to PulseRoom and the related real-time room services exposed by this stack.
          By using the service, you agree to these terms, the Acceptable Use Policy, and the Cookie Policy.
        </p>

        <h2>Use of the service</h2>
        <p>
          You may use the service only for lawful, authorized communication and collaboration. You are responsible
          for your account, your content, and the way you use supported sign-in providers and room features.
        </p>

        <h2>Availability</h2>
        <p>
          The service may change, be interrupted, or be discontinued at any time. No guarantee is given that the
          service will be uninterrupted, error-free, or suitable for every use case.
        </p>

        <h2>Content and enforcement</h2>
        <p>
          The operator may remove content, restrict access, suspend rooms, or terminate accounts where reasonably
          necessary to enforce platform rules, protect users, respond to abuse, or comply with law.
        </p>

        <h2>Third-party providers</h2>
        <p>
          Sign-in and infrastructure may rely on third-party providers. Their services are governed by their own
          terms and policies.
        </p>

        <h2>Disclaimers and liability</h2>
        <p>
          The service is provided on an "as is" and "as available" basis to the maximum extent permitted by law.
          Operators and contributors disclaim warranties to the maximum extent permitted by law. Nothing on this
          page should be treated as finalized legal advice without operator review.
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
    p {
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

    p {
      margin-top: 0.75rem;
      line-height: 1.65;
      color: rgba(226, 232, 240, 0.84);
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
export class TermsOfServiceComponent {}
