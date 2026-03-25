import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SocialPost, SocialPostMedia, SocialProfile, SocialProfileSummary } from '../../core/models/social.models';

@Component({
  selector: 'app-social-discovery-column',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './social-discovery-column.component.html',
  styleUrl: './social-discovery-column.component.scss'
})
export class SocialDiscoveryColumnComponent {
  @Input() searchQuery = '';
  @Input() searchScope: 'all' | 'mutual' = 'all';
  @Input() canGrantAccess = false;
  @Input() selectedGrantHandles: string[] = [];
  @Input() searchResults: SocialProfileSummary[] = [];
  @Input() canSubmitSelectedAccess = false;
  @Input() selectedProfile: SocialProfile | null = null;
  @Input() selectedProfileAvatarUrl: string | null = null;
  @Input() selectedProfileInitials = 'PR';
  @Input() mediaUrl: (mediaId: string) => string | null = () => null;

  @Output() searchQueryChange = new EventEmitter<string>();
  @Output() searchScopeChange = new EventEmitter<'all' | 'mutual'>();
  @Output() performSearch = new EventEmitter<void>();
  @Output() grantSelectedAccess = new EventEmitter<void>();
  @Output() removeGrantSelection = new EventEmitter<string>();
  @Output() openProfile = new EventEmitter<string>();
  @Output() toggleFollow = new EventEmitter<SocialProfileSummary>();
  @Output() toggleGrantSelection = new EventEmitter<SocialProfileSummary>();

  isGrantSelected(handle: string): boolean {
    return this.selectedGrantHandles.includes(handle);
  }

  trackByHandle(_: number, profile: SocialProfileSummary): string {
    return profile.handle;
  }

  trackByPost(_: number, post: SocialPost): string {
    return post.id;
  }

  trackByMedia(_: number, media: SocialPostMedia): string {
    return media.id;
  }
}
