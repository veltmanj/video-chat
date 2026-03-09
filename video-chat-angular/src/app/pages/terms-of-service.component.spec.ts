import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TermsOfServiceComponent } from './terms-of-service.component';

describe('TermsOfServiceComponent', () => {
  let fixture: ComponentFixture<TermsOfServiceComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TermsOfServiceComponent],
      providers: [provideRouter([])]
    }).compileComponents();

    fixture = TestBed.createComponent(TermsOfServiceComponent);
    fixture.detectChanges();
  });

  it('renders the terms page', () => {
    const text = fixture.nativeElement.textContent || '';
    expect(text).toContain('Terms of Service');
    expect(text).toContain('Acceptable Use Policy');
    expect(text).toContain('Back to sign in');
  });
});
