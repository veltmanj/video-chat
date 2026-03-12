// @vitest-environment jsdom
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CameraFeed } from '../../core/models/room.models';
import { CameraGridComponent } from './camera-grid.component';

describe('CameraGridComponent', () => {
  let component: CameraGridComponent;
  let fixture: ComponentFixture<CameraGridComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CameraGridComponent]
    }).compileComponents();

    fixture = TestBed.createComponent(CameraGridComponent);
    component = fixture.componentInstance;
  });

  it('binds stream to video node and starts playback when paused', async () => {
    const stream = {} as MediaStream;
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
    vi.spyOn(videoNode, 'paused', 'get').mockReturnValue(true);
    const playSpy = vi.spyOn(videoNode, 'play').mockResolvedValue(undefined);

    component.ngAfterViewChecked();

    expect(videoNode.srcObject).toBe(stream);
    expect(playSpy).toHaveBeenCalled();
  });

  it('shows an offline placeholder message when a remote stream has stopped', () => {
    const feed: CameraFeed = {
      id: 'remote-a-feed-1',
      ownerId: 'remote-a',
      ownerName: 'Remote A',
      publishedFeedId: 'feed-1',
      label: 'Remote A camera',
      local: false,
      muted: true,
      online: false
    };

    component.feeds = [feed];
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Camera van Remote A');
    expect(fixture.nativeElement.textContent).toContain('Offline');
  });

  it('keeps the original camera name visible on the local tile', () => {
    const feed: CameraFeed = {
      id: 'local-feed-1',
      ownerId: 'local-a',
      ownerName: 'Remote A',
      label: 'FaceTime HD Camera',
      local: true,
      muted: true,
      online: true
    };

    component.feeds = [feed];
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('FaceTime HD Camera');
  });
});
