import { HttpEvent, HttpHandler, HttpInterceptor, HttpRequest } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthService } from './auth.service';

@Injectable()
export class AuthInterceptor implements HttpInterceptor {
  constructor(private auth: AuthService) { }

  intercept(req: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
    const token = this.resolveToken(req.url);
    if (token) {
      const cloned = req.clone({
        setHeaders: {
          Authorization: `Bearer ${token}`
        }
      });
      return next.handle(cloned);
    }
    return next.handle(req);
  }

  private resolveToken(url: string): string | null {
    return this.isSocialApiRequest(url) ? this.auth.idToken : this.auth.accessToken;
  }

  private isSocialApiRequest(url: string): boolean {
    return url.startsWith('/social-api/')
      || url.includes(`${window.location.origin}/social-api/`);
  }
}
