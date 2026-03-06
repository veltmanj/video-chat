import { ComponentFixture, TestBed } from '@angular/core/testing';
import { CameraFeed } from '../../core/models/room.models';
import { CameraGridComponent } from './camera-grid.component';

describe('CameraGridComponent', () => {
  let component: CameraGridComponent;
  let fixture: ComponentFixture<CameraGridComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [CameraGridComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(CameraGridComponent);
    component = fixture.componentInstance;
  });

  it('binds stream to video node and starts playback when paused', async () => {
    const stream = new MediaStream();
    const feed: CameraFeed = {
      id: 'remote-a-track-1',
      ownerId: 'remote-a',
      ownerName: 'Remote A',
      label: 'Remote A camera',
      stream,
      local: false,
      muted: true,
      online: true
    };

    component.feeds = [feed];
    fixture.detectChanges();

    const videoNode = fixture.nativeElement.querySelector('video') as HTMLVideoElement;
    spyOnProperty(videoNode, 'paused', 'get').and.returnValue(true);
    const playSpy = spyOn(videoNode, 'play').and.returnValue(Promise.resolve());

    component.ngAfterViewChecked();

    expect(videoNode.srcObject).toBe(stream);
    expect(playSpy).toHaveBeenCalled();
  });
});
