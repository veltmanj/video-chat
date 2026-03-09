import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-acceptable-use',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <main class="policy-page">
      <section class="policy-card">
        <p class="policy-kicker">Legal</p>
        <h1>Acceptable Use Policy</h1>
        <p class="policy-meta">Last updated: March 9, 2026</p>

        <p>
          PulseRoom may be used only for lawful, authorized communication and collaboration. Abuse, evasion,
          and harmful conduct are not allowed.
        </p>

        <h2>Prohibited uses</h2>
        <ul>
          <li>Illegal content or conduct, including exploitation, trafficking, or severe abuse.</li>
          <li>Harassment, threats, stalking, doxxing, or non-consensual intimate imagery.</li>
          <li>Fraud, impersonation, phishing, spam, malware, or credential theft.</li>
          <li>Unauthorized access, scraping, interception, privilege escalation, or service disruption.</li>
          <li>Sharing content that infringes intellectual property, privacy, or confidentiality rights.</li>
        </ul>

        <h2>Operator action</h2>
        <p>
          The operator may investigate suspected violations and may remove content, suspend rooms, preserve logs,
          restrict access, terminate accounts, or refer matters to providers, rights holders, or authorities
          where appropriate.
        </p>

        <h2>Reporting</h2>
        <p>
          Before public launch, the operator should publish a dedicated abuse-reporting channel and a
          notice-and-takedown process appropriate to the jurisdictions in which the service is offered.
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
export class AcceptableUseComponent {}
