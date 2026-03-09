import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { CookiePolicyComponent } from './cookie-policy.component';

describe('CookiePolicyComponent', () => {
  let fixture: ComponentFixture<CookiePolicyComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CookiePolicyComponent],
      providers: [provideRouter([])]
    }).compileComponents();

    fixture = TestBed.createComponent(CookiePolicyComponent);
    fixture.detectChanges();
  });

  it('renders the essential storage policy', () => {
    const text = fixture.nativeElement.textContent || '';
    expect(text).toContain('Cookie Policy');
    expect(text).toContain('does not currently set first-party analytics or advertising cookies');
    expect(text).toContain('Back to sign in');
  });
});
