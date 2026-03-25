import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-login-email-auth',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './login-email-auth.component.html',
  styleUrl: './login-email-auth.component.scss'
})
export class LoginEmailAuthComponent {
  @Input() registrationDisplayName = '';
  @Input() registrationAddress = '';
  @Input() loginAddress = '';
  @Input() requestInFlight = false;

  @Output() registrationDisplayNameChange = new EventEmitter<string>();
  @Output() registrationAddressChange = new EventEmitter<string>();
  @Output() loginAddressChange = new EventEmitter<string>();
  @Output() register = new EventEmitter<void>();
  @Output() login = new EventEmitter<void>();
}
