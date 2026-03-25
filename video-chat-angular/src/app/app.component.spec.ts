import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { vi } from 'vitest';
import { appRoutes } from './app.routes';
import { AppComponent } from './app.component';
import { AuthService } from './core/services/auth.service';

describe('AppComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter(appRoutes),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: false,
            profileImageUrl: null,
            profileName: null,
            logout: vi.fn()
          }
        }
      ]
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(AppComponent);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('shows the round social profile button for authenticated users', async () => {
    TestBed.resetTestingModule();

    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter(appRoutes),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: true,
            profileName: 'Operator Name',
            logout: vi.fn()
          }
        }
      ]
    }).compileComponents();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const profileLink = fixture.nativeElement.querySelector('.profile-link') as HTMLAnchorElement | null;
    const profileInitials = fixture.nativeElement.querySelector('.profile-link span') as HTMLSpanElement | null;
    const profileMenu = fixture.nativeElement.querySelector('.profile-menu') as HTMLDivElement | null;

    expect(profileLink).not.toBeNull();
    expect(profileLink?.getAttribute('href')).toBe('/social');
    expect(profileInitials?.textContent?.trim()).toBe('ON');
    expect(profileMenu).toBeNull();
  });

  it('opens the profile menu on right click and logs out from the menu', async () => {
    TestBed.resetTestingModule();

    const logoutSpy = vi.fn();

    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter(appRoutes),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: true,
            profileName: 'Operator Name',
            logout: logoutSpy
          }
        }
      ]
    }).compileComponents();

    const fixture = TestBed.createComponent(AppComponent);
    const router = TestBed.inject(Router);
    const navigateSpy = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fixture.detectChanges();

    const profileLink = fixture.nativeElement.querySelector('.profile-link') as HTMLAnchorElement;
    const contextMenuEvent = new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: 2 });
    const preventDefaultSpy = vi.spyOn(contextMenuEvent, 'preventDefault');

    profileLink.dispatchEvent(contextMenuEvent);
    fixture.detectChanges();

    const logoutButton = fixture.nativeElement.querySelector('.profile-menu-item') as HTMLButtonElement;
    expect(preventDefaultSpy).toHaveBeenCalled();
    expect(logoutButton.textContent?.trim()).toBe('Logout');

    logoutButton.click();

    expect(logoutSpy).toHaveBeenCalled();
    expect(navigateSpy).toHaveBeenCalledWith('/login');
  });

  it('closes the profile menu when clicking outside the profile area', async () => {
    TestBed.resetTestingModule();

    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter(appRoutes),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: true,
            profileName: 'Operator Name',
            logout: vi.fn()
          }
        }
      ]
    }).compileComponents();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const profileLink = fixture.nativeElement.querySelector('.profile-link') as HTMLAnchorElement;
    profileLink.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: 2 }));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.profile-menu')).not.toBeNull();

    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.profile-menu')).toBeNull();
  });

  it('renders initials for the profile button', async () => {
    TestBed.resetTestingModule();

    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter(appRoutes),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: true,
            profileName: 'Operator Name',
            logout: vi.fn()
          }
        }
      ]
    }).compileComponents();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const fallback = fixture.nativeElement.querySelector('.profile-link span') as HTMLSpanElement | null;
    expect(fallback?.textContent?.trim()).toBe('ON');
  });
});
