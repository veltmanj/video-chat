import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-cookie-policy',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <main class="policy-page">
      <section class="policy-card">
        <p class="policy-kicker">Legal</p>
        <h1>Cookie Policy</h1>
        <p class="policy-meta">Last updated: March 9, 2026</p>

        <p>
          PulseRoom currently uses only sign-in related storage that is necessary to authenticate a user,
          maintain the active browser session, and authorize room access. The application does not currently
          set first-party analytics or advertising cookies.
        </p>

        <h2>What this app uses</h2>
        <ul>
          <li>Essential browser session storage for OAuth state, nonce, and active sign-in tokens.</li>
          <li>Essential browser session storage for the room authorization token during the current tab session.</li>
          <li>No first-party analytics, profiling, or advertising cookies at this time.</li>
        </ul>

        <h2>Third-party sign-in providers</h2>
        <p>
          When you choose a provider such as Google, that provider may set cookies on its own domains to
          authenticate you, apply fraud prevention controls, and complete the redirect back to this app.
          Those cookies are controlled by the provider, not by PulseRoom.
        </p>

        <h2>Why we do not show an opt-in banner</h2>
        <p>
          The current implementation is limited to storage that is necessary to deliver the sign-in flow and
          the room you explicitly requested. If optional analytics, advertising, or personalization cookies are
          added later, the login flow and cookie controls should be updated before those cookies are enabled.
        </p>

        <h2>How to control this storage</h2>
        <ul>
          <li>Sign out of the application when you are finished.</li>
          <li>Close the browser tab to clear session-scoped storage.</li>
          <li>Clear site data in your browser if you want to remove stored tokens immediately.</li>
          <li>Be aware that blocking provider cookies on Google or other identity domains can prevent sign-in.</li>
        </ul>

        <h2>Review expectation</h2>
        <p>
          This policy should be reviewed whenever the stack adds analytics, telemetry, consent tooling,
          embedded third-party widgets, or additional identity providers.
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
export class CookiePolicyComponent {}
