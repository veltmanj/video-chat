import { AfterViewChecked, Component, ElementRef, EventEmitter, Input, Output, QueryList, ViewChildren } from '@angular/core';
import { CameraFeed } from '../../core/models/room.models';

@Component({
  selector: 'app-camera-grid',
  templateUrl: './camera-grid.component.html',
  styleUrl: './camera-grid.component.scss'
})
export class CameraGridComponent implements AfterViewChecked {
  @Input() feeds: CameraFeed[] = [];
  @Output() removeFeed = new EventEmitter<string>();

  @ViewChildren('feedVideo') private videoElements!: QueryList<ElementRef<HTMLVideoElement>>;

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

      if (node.paused) {
        node.play().catch(() => {
          // Browsers may block autoplay intermittently; retry on next change detection cycle.
        });
      }
    });
  }
}
