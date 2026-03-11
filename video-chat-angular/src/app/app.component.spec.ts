import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
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
            profileName: null
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
            profileImageUrl: 'https://example.com/avatar.png',
            profileName: 'Operator Name'
          }
        }
      ]
    }).compileComponents();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const profileLink = fixture.nativeElement.querySelector('.profile-link') as HTMLAnchorElement | null;
    const profileImage = fixture.nativeElement.querySelector('.profile-link img') as HTMLImageElement | null;

    expect(profileLink).not.toBeNull();
    expect(profileLink?.getAttribute('href')).toBe('/social');
    expect(profileImage?.getAttribute('src')).toBe('https://example.com/avatar.png');
  });

  it('falls back to initials when the profile image fails to load', async () => {
    TestBed.resetTestingModule();

    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter(appRoutes),
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: true,
            profileImageUrl: 'https://example.com/avatar.png',
            profileName: 'Operator Name'
          }
        }
      ]
    }).compileComponents();

    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();

    const profileImage = fixture.nativeElement.querySelector('.profile-link img') as HTMLImageElement;
    profileImage.dispatchEvent(new Event('error'));
    fixture.detectChanges();

    const fallback = fixture.nativeElement.querySelector('.profile-link span') as HTMLSpanElement | null;
    expect(fallback?.textContent?.trim()).toBe('ON');
  });
});
