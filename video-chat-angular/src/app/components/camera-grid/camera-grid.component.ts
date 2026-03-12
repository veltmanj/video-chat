import { CommonModule } from '@angular/common';
import { AfterViewChecked, Component, ElementRef, EventEmitter, Input, Output, QueryList, ViewChildren } from '@angular/core';
import { CameraFeed } from '../../core/models/room.models';

@Component({
  selector: 'app-camera-grid',
  templateUrl: './camera-grid.component.html',
  styleUrl: './camera-grid.component.scss',
  standalone: true,
  imports: [CommonModule]
})
export class CameraGridComponent implements AfterViewChecked {
  @Input() feeds: CameraFeed[] = [];
  @Output() removeFeed = new EventEmitter<string>();

  @ViewChildren('feedVideo') private videoElements!: QueryList<ElementRef<HTMLVideoElement>>;

  getFeedSubtitle(feed: CameraFeed): string {
    return feed.local ? feed.label : `Camera van ${feed.ownerName}`;
  }

  showOfflineAvatar(feed: CameraFeed): boolean {
    return !feed.local && !feed.online && !feed.stream && !!feed.ownerProfileImageUrl;
  }

  getOfflineAvatarAlt(feed: CameraFeed): string {
    return `${feed.ownerName} profielafbeelding`;
  }

  ngAfterViewChecked(): void {
    if (!this.videoElements) {
      return;
    }

    this.videoElements.forEach((elementRef: ElementRef<HTMLVideoElement>) => {
      const node = elementRef.nativeElement;
      const feedId = node.getAttribute('data-feed-id') || '';
      const feed = this.feeds.find((item: CameraFeed) => item.id === feedId);

      if (!feed || !feed.stream) {
        return;
      }

      if (node.srcObject !== feed.stream) {
        node.srcObject = feed.stream;
      }

      const shouldAttemptPlayback = node.paused || node.readyState < HTMLMediaElement.HAVE_CURRENT_DATA || node.videoWidth === 0;
      if (shouldAttemptPlayback) {
        const playAttempt = node.play();
        if (playAttempt && typeof playAttempt.catch === 'function') {
          playAttempt.catch(() => {
            // Browsers may block autoplay intermittently; retry on next change detection cycle.
          });
        }
      }
    });
  }
}
