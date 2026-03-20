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
      audioEnabled: false,
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

  it('shows the remote profile image when a remote stream has stopped', () => {
    const feed: CameraFeed = {
      id: 'remote-a-feed-1',
      ownerId: 'remote-a',
      ownerName: 'Remote A',
      ownerProfileImageUrl: 'https://example.com/remote-a.png',
      publishedFeedId: 'feed-1',
      label: 'Remote A camera',
      local: false,
      muted: true,
      audioEnabled: false,
      online: false
    };

    component.feeds = [feed];
    fixture.detectChanges();

    const offlineAvatar = fixture.nativeElement.querySelector('.offline-avatar') as HTMLImageElement | null;

    expect(fixture.nativeElement.textContent).toContain('Camera van Remote A');
    expect(fixture.nativeElement.textContent).not.toContain('Offline');
    expect(offlineAvatar?.getAttribute('src')).toBe('https://example.com/remote-a.png');
  });

  it('keeps the original camera name visible on the local tile', () => {
    const feed: CameraFeed = {
      id: 'local-feed-1',
      ownerId: 'local-a',
      ownerName: 'Remote A',
      label: 'FaceTime HD Camera',
      local: true,
      muted: true,
      audioEnabled: true,
      online: true
    };

    component.feeds = [feed];
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('FaceTime HD Camera');
  });

  it('emits tile audio toggle actions', () => {
    const toggleSpy = vi.fn();
    const stream = { getAudioTracks: () => [{ kind: 'audio' } as MediaStreamTrack] } as unknown as MediaStream;
    const feed: CameraFeed = {
      id: 'remote-a-track-1',
      ownerId: 'remote-a',
      ownerName: 'Remote A',
      label: 'Remote A camera',
      stream,
      local: false,
      muted: false,
      audioEnabled: true,
      online: true
    };

    component.toggleFeedAudio.subscribe(toggleSpy);
    component.feeds = [feed];
    fixture.detectChanges();

    const audioButton = fixture.nativeElement.querySelector('.secondary') as HTMLButtonElement;
    audioButton.click();

    expect(toggleSpy).toHaveBeenCalledWith('remote-a-track-1');
  });
});
