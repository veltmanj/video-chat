import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { type MockedObject, vi } from 'vitest';
import { AuthService } from '../core/services/auth.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let component: LoginComponent;
  let authService: MockedObject<AuthService> & { authState$: BehaviorSubject<boolean>; };
  let router: Router;

  beforeEach(async () => {
    authService = {
      accessToken: null,
      authState$: new BehaviorSubject(false),
      brokerToken: null,
      hasGoogleClientId: true,
      identityClaims: null,
      initialize: vi.fn().mockResolvedValue(undefined),
      isAuthenticated: false,
      isDevelopmentMode: false,
      isGoogleButtonRendered: false,
      isGoogleIdentityLoaded: true,
      oauthDebugEvents: [],
      renderGoogleButton: vi.fn().mockResolvedValue(undefined)
    } as unknown as MockedObject<AuthService> & { authState$: BehaviorSubject<boolean>; };

    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authService }
      ]
    }).compileComponents();

    router = TestBed.inject(Router);
  });

  function createComponent(): void {
    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('hides developer setup details outside development mode', () => {
    createComponent();

    const text = fixture.nativeElement.textContent || '';
    expect(text).not.toContain('Development mode');
    expect(text).not.toContain('Missing Google client ID');
  });

  it('shows developer setup details in development mode', () => {
    authService.isDevelopmentMode = true;
    authService.hasGoogleClientId = false;

    createComponent();

    const text = fixture.nativeElement.textContent || '';
    expect(text).toContain('Development mode');
    expect(text).toContain('Missing Google client ID');
    expect(text).toContain('Google Sign-In popup flow does not use a redirect URI');
  });

  it('shows the legal policy links on the login card', () => {
    createComponent();

    const text = fixture.nativeElement.textContent || '';
    const links = Array.from(fixture.nativeElement.querySelectorAll('a')) as HTMLAnchorElement[];
    const hrefs = links.map((link) => link.getAttribute('href'));

    expect(text).toContain('Terms of Service');
    expect(text).toContain('Privacy Policy');
    expect(text).toContain('Acceptable Use Policy');
    expect(text).toContain('Cookie Policy');
    expect(hrefs).toContain('/privacy');
    expect(hrefs).toContain('/terms');
    expect(hrefs).toContain('/acceptable-use');
    expect(hrefs).toContain('/cookies');
  });

  it('redirects into the app when auth is already complete on the login route', async () => {
    const navigateSpy = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    authService.isAuthenticated = true;
    authService.authState$ = new BehaviorSubject(true);

    createComponent();
    await fixture.whenStable();

    expect(navigateSpy).toHaveBeenCalledWith('/');
  });

  it('renders the Google sign-in button when configured', async () => {
    createComponent();
    await fixture.whenStable();

    expect(authService.renderGoogleButton).toHaveBeenCalledTimes(1);
  });

  it('redirects after a successful GIS callback updates auth state', async () => {
    const navigateSpy = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);

    createComponent();
    authService.authState$.next(true);
    await fixture.whenStable();

    expect(navigateSpy).toHaveBeenCalledWith('/');
  });

  it('shows Google sign-in diagnostics in development mode', async () => {
    authService.isDevelopmentMode = true;
    authService.isGoogleButtonRendered = true;
    authService.oauthDebugEvents = ['credential-received:btn'];
    authService.identityClaims = {
      sub: 'google-user-id',
      email: 'operator@example.com'
    };
    authService.brokerToken = 'google-id-token';

    createComponent();
    await fixture.whenStable();

    const text = fixture.nativeElement.textContent || '';
    expect(text).toContain('Google sign-in diagnostics');
    expect(text).toContain('Google button rendered');
    expect(text).toContain('credential-received:btn');
    expect(text).toContain('operator@example.com');
  });
});
