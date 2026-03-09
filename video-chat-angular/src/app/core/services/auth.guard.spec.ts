import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, UrlTree } from '@angular/router';
import { type MockedObject, vi } from 'vitest';
import { AuthService } from './auth.service';
import { AuthGuard } from './auth.guard';

describe('AuthGuard', () => {
  let guard: AuthGuard;
  let router: Router;
  let authService: MockedObject<AuthService>;

  beforeEach(() => {
    authService = {
      initialize: vi.fn().mockResolvedValue(undefined),
      isAuthenticated: true
    } as unknown as MockedObject<AuthService>;

    TestBed.configureTestingModule({
      providers: [
        AuthGuard,
        provideRouter([]),
        { provide: AuthService, useValue: authService }
      ]
    });

    guard = TestBed.inject(AuthGuard);
    router = TestBed.inject(Router);
  });

  it('allows navigation after successful initialization when authenticated', async () => {
    await expect(guard.canActivate()).resolves.toBe(true);
    expect(authService.initialize).toHaveBeenCalledTimes(1);
  });

  it('redirects to login when unauthenticated', async () => {
    authService.isAuthenticated = false;

    const result = await guard.canActivate();

    expect(authService.initialize).toHaveBeenCalledTimes(1);
    expect(result).toEqual(router.createUrlTree(['/login']));
  });

  it('redirects to login when auth initialization fails', async () => {
    authService.initialize.mockRejectedValueOnce(new Error('discovery failed'));
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    const result = await guard.canActivate();

    expect(result).toBeInstanceOf(UrlTree);
    expect(result).toEqual(router.createUrlTree(['/login']));
    expect(consoleErrorSpy).toHaveBeenCalled();

    consoleErrorSpy.mockRestore();
  });
});
