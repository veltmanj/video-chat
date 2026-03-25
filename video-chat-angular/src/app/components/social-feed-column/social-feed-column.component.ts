import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SocialPost, SocialPostMedia } from '../../core/models/social.models';

interface ComposerUploadView {
  id: string;
  file: File;
  kind: 'IMAGE' | 'VIDEO';
  previewUrl: string;
}

@Component({
  selector: 'app-social-feed-column',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './social-feed-column.component.html',
  styleUrl: './social-feed-column.component.scss'
})
export class SocialFeedColumnComponent {
  @Input() composerUploads: ComposerUploadView[] = [];
  @Input() newPostBody = '';
  @Input() publishingPost = false;
  @Input() canPublishPost = false;
  @Input() statusMessage = '';
  @Input() errorMessage = '';
  @Input() feed: SocialPost[] = [];
  @Input() reactionOptions: string[] = [];
  @Input() mediaUrl: (mediaId: string) => string | null = () => null;
  @Input() formatUploadSize: (bytes: number) => string = (bytes) => `${bytes} B`;

  @Output() newPostBodyChange = new EventEmitter<string>();
  @Output() composerFilesSelected = new EventEmitter<Event>();
  @Output() removeComposerUpload = new EventEmitter<string>();
  @Output() publishPost = new EventEmitter<void>();
  @Output() toggleReaction = new EventEmitter<{ post: SocialPost; reaction: string; }>();

  trackByComposerUpload(_: number, upload: ComposerUploadView): string {
    return upload.id;
  }

  trackByPost(_: number, post: SocialPost): string {
    return post.id;
  }

  trackByMedia(_: number, media: SocialPostMedia): string {
    return media.id;
  }
}
