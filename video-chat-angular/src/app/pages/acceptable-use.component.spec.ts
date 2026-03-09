import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AcceptableUseComponent } from './acceptable-use.component';

describe('AcceptableUseComponent', () => {
  let fixture: ComponentFixture<AcceptableUseComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AcceptableUseComponent],
      providers: [provideRouter([])]
    }).compileComponents();

    fixture = TestBed.createComponent(AcceptableUseComponent);
    fixture.detectChanges();
  });

  it('renders the acceptable use page', () => {
    const text = fixture.nativeElement.textContent || '';
    expect(text).toContain('Acceptable Use Policy');
    expect(text).toContain('Prohibited uses');
    expect(text).toContain('Back to sign in');
  });
});
