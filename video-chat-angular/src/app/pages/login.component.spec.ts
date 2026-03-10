import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { type MockedObject, vi } from 'vitest';
import { AuthService } from '../core/services/auth.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let component: LoginComponent;
  let authService: MockedObject<AuthService>;

  beforeEach(async () => {
    authService = {
      hasGoogleClientId: true,
      isDevelopmentMode: false,
      login: vi.fn().mockResolvedValue(undefined)
    } as unknown as MockedObject<AuthService>;

    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authService }
      ]
    }).compileComponents();

    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('hides developer setup details outside development mode', () => {
    const text = fixture.nativeElement.textContent || '';
    expect(text).not.toContain('Development mode');
    expect(text).not.toContain('Missing Google client ID');
  });

  it('shows developer setup details in development mode', () => {
    authService.isDevelopmentMode = true;
    authService.hasGoogleClientId = false;

    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();

    const text = fixture.nativeElement.textContent || '';
    expect(text).toContain('Development mode');
    expect(text).toContain('Missing Google client ID');
  });

  it('shows the legal policy links on the login card', () => {
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
});
