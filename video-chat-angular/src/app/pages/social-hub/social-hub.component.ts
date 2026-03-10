import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { SocialPost, SocialProfile, SocialProfileSummary, SocialViewerResponse } from '../../core/models/social.models';
import { SocialService } from '../../core/services/social.service';

@Component({
  selector: 'app-social-hub',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './social-hub.component.html',
  styleUrl: './social-hub.component.scss'
})
export class SocialHubComponent {
  readonly reactionOptions = ['👍', '🔥', '👏', '❤️'];

  me: SocialProfile | null = null;
  accessGrants: SocialProfileSummary[] = [];
  feed: SocialPost[] = [];
  searchResults: SocialProfileSummary[] = [];
  selectedProfile: SocialProfile | null = null;
  selectedGrantHandles: string[] = [];

  displayName = '';
  bio = '';
  visibility: 'PUBLIC' | 'PRIVATE' = 'PUBLIC';
  newPostBody = '';
  searchQuery = '';
  searchScope: 'all' | 'mutual' = 'all';
  statusMessage = '';
  errorMessage = '';
  loading = true;

  constructor(private socialService: SocialService) { }

  ngOnInit(): void {
    void this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading = true;
    this.errorMessage = '';

    try {
      const [viewer, feed] = await Promise.all([
        firstValueFrom(this.socialService.viewer()),
        firstValueFrom(this.socialService.feed())
      ]);

      this.applyViewer(viewer);
      this.feed = feed.posts;
    } catch (error) {
      console.error('Failed to load social hub', error);
      this.errorMessage = 'Could not load the social hub.';
    } finally {
      this.loading = false;
    }
  }

  async saveProfile(): Promise<void> {
    this.resetMessages();

    try {
      const updated = await firstValueFrom(this.socialService.updateProfile({
        displayName: this.displayName,
        bio: this.bio,
        visibility: this.visibility
      }));

      this.me = updated;
      this.statusMessage = 'Profile saved.';
    } catch (error) {
      console.error('Failed to save profile', error);
      this.errorMessage = 'Could not save your profile.';
    }
  }

  async publishPost(): Promise<void> {
    if (!this.newPostBody.trim()) {
      return;
    }

    this.resetMessages();

    try {
      const created = await firstValueFrom(this.socialService.createPost(this.newPostBody.trim()));
      this.feed = [created, ...this.feed];
      if (this.me) {
        this.me = { ...this.me, recentPosts: [created, ...this.me.recentPosts].slice(0, 20) };
      }
      this.newPostBody = '';
      this.statusMessage = 'Post published.';
    } catch (error) {
      console.error('Failed to publish post', error);
      this.errorMessage = 'Could not publish the post.';
    }
  }

  async performSearch(): Promise<void> {
    this.resetMessages();

    try {
      this.searchResults = await firstValueFrom(this.socialService.searchProfiles(this.searchQuery.trim(), this.searchScope));
    } catch (error) {
      console.error('Failed to search profiles', error);
      this.errorMessage = 'Search failed.';
    }
  }

  async openProfile(handle: string): Promise<void> {
    this.resetMessages();

    try {
      this.selectedProfile = await firstValueFrom(this.socialService.profile(handle));
    } catch (error) {
      console.error('Failed to load profile', error);
      this.selectedProfile = null;
      this.errorMessage = 'That profile is private or unavailable.';
    }
  }

  async toggleFollow(profile: SocialProfileSummary): Promise<void> {
    this.resetMessages();

    try {
      const updated = profile.following
        ? await firstValueFrom(this.socialService.unfollow(profile.handle))
        : await firstValueFrom(this.socialService.follow(profile.handle));
      this.mergeSearchProfile(updated);
      if (this.selectedProfile?.handle === updated.handle) {
        await this.openProfile(updated.handle);
      }
      this.statusMessage = updated.following ? `Following @${updated.handle}.` : `Unfollowed @${updated.handle}.`;
    } catch (error) {
      console.error('Failed to update follow state', error);
      this.errorMessage = 'Could not update the connection.';
    }
  }

  async grantSelectedAccess(): Promise<void> {
    if (!this.me || !this.selectedGrantHandles.length) {
      return;
    }

    this.resetMessages();

    try {
      const result = await firstValueFrom(this.socialService.grantAccess(this.me.handle, this.selectedGrantHandles));
      if (result.grantedHandles.length) {
        this.statusMessage = `Granted access to @${result.grantedHandles.join(', @')}.`;
        this.markGrantedAccess(result.grantedHandles);
        this.selectedGrantHandles = this.selectedGrantHandles
          .filter((handle) => !result.grantedHandles.includes(handle));
        await this.refreshViewerOnly();
      }
      if (result.missingHandles.length) {
        this.errorMessage = `Missing profiles: ${result.missingHandles.join(', ')}`;
      }
    } catch (error) {
      console.error('Failed to grant access', error);
      this.errorMessage = 'Could not grant access.';
    }
  }

  async toggleReaction(post: SocialPost, reactionType: string): Promise<void> {
    this.resetMessages();

    try {
      const updated = post.viewerReactions.includes(reactionType)
        ? await firstValueFrom(this.socialService.removeReaction(post.id, reactionType))
        : await firstValueFrom(this.socialService.addReaction(post.id, reactionType));
      this.replacePost(updated);
    } catch (error) {
      console.error('Failed to update reaction', error);
      this.errorMessage = 'Could not update the reaction.';
    }
  }

  get canGrantAccess(): boolean {
    return !!this.me && (this.visibility === 'PRIVATE' || this.me.visibility === 'PRIVATE');
  }

  get canSubmitSelectedAccess(): boolean {
    return this.canGrantAccess && this.selectedGrantHandles.length > 0;
  }

  isGrantSelected(handle: string): boolean {
    return this.selectedGrantHandles.includes(handle);
  }

  toggleGrantSelection(profile: SocialProfileSummary): void {
    if (!this.canGrantAccess || profile.accessGranted) {
      return;
    }

    this.selectedGrantHandles = this.isGrantSelected(profile.handle)
      ? this.selectedGrantHandles.filter((handle) => handle !== profile.handle)
      : [...this.selectedGrantHandles, profile.handle];
  }

  removeGrantSelection(handle: string): void {
    this.selectedGrantHandles = this.selectedGrantHandles.filter((candidate) => candidate !== handle);
  }

  trackByHandle(_: number, profile: SocialProfileSummary): string {
    return profile.handle;
  }

  trackByPost(_: number, post: SocialPost): string {
    return post.id;
  }

  private async refreshViewerOnly(): Promise<void> {
    const viewer = await firstValueFrom(this.socialService.viewer());
    this.applyViewer(viewer);
  }

  private applyViewer(viewer: SocialViewerResponse): void {
    this.me = viewer.me;
    this.accessGrants = viewer.accessGrants;
    this.displayName = viewer.me.displayName;
    this.bio = viewer.me.bio;
    this.visibility = viewer.me.visibility;
  }

  private mergeSearchProfile(updated: SocialProfileSummary): void {
    this.searchResults = this.searchResults.map((candidate) => candidate.handle === updated.handle ? updated : candidate);
  }

  private markGrantedAccess(handles: string[]): void {
    this.searchResults = this.searchResults.map((candidate) => handles.includes(candidate.handle)
      ? { ...candidate, accessGranted: true, canView: true }
      : candidate);

    if (this.selectedProfile && handles.includes(this.selectedProfile.handle)) {
      this.selectedProfile = { ...this.selectedProfile, accessGranted: true, canView: true };
    }
  }

  private replacePost(updated: SocialPost): void {
    this.feed = this.feed.map((post) => post.id === updated.id ? updated : post);
    if (this.selectedProfile) {
      this.selectedProfile = {
        ...this.selectedProfile,
        recentPosts: this.selectedProfile.recentPosts.map((post) => post.id === updated.id ? updated : post)
      };
    }
    if (this.me) {
      this.me = {
        ...this.me,
        recentPosts: this.me.recentPosts.map((post) => post.id === updated.id ? updated : post)
      };
    }
  }

  private resetMessages(): void {
    this.statusMessage = '';
    this.errorMessage = '';
  }
}
