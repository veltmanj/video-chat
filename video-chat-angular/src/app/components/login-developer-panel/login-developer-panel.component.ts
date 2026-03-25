import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';

@Component({
  selector: 'app-login-developer-panel',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './login-developer-panel.component.html',
  styleUrl: './login-developer-panel.component.scss'
})
export class LoginDeveloperPanelComponent {
  @Input() currentOrigin = '';
  @Input() hasGoogleClientId = false;
  @Input() isGoogleIdentityLoaded = false;
  @Input() isGoogleButtonRendered = false;
  @Input() brokerTokenPresent = false;
  @Input() accessTokenPresent = false;
  @Input() identitySummary = 'none';
  @Input() oauthDebugEvents: string[] = [];
  @Input() authDebugMessage = '';
}
