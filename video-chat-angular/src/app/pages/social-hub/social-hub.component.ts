import { CommonModule } from '@angular/common';
import { ChangeDetectorRef, Component, DestroyRef, NgZone, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import {
  SocialPost,
  SocialPostMedia,
  SocialProfile,
  SocialProfileSummary,
  SocialViewerResponse
} from '../../core/models/social.models';
import { SocialService } from '../../core/services/social.service';

interface ComposerUpload {
  id: string;
  file: File;
  kind: 'IMAGE' | 'VIDEO';
  previewUrl: string;
}

@Component({
  selector: 'app-social-hub',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './social-hub.component.html',
  styleUrl: './social-hub.component.scss'
})
export class SocialHubComponent {
  private static readonly AUTO_REFRESH_INTERVAL_MS = 5000;
  private static readonly MAX_FILES_PER_POST = 4;

  readonly reactionOptions = ['👍', '🔥', '👏', '❤️'];
  private readonly ngZone = inject(NgZone);
  private readonly changeDetectorRef = inject(ChangeDetectorRef);
  private readonly destroyRef = inject(DestroyRef);
  private autoRefreshTimer: ReturnType<typeof setInterval> | null = null;
  private readonly mediaObjectUrls = new Map<string, string>();
  private readonly loadingMediaIds = new Set<string>();

  me: SocialProfile | null = null;
  accessGrants: SocialProfileSummary[] = [];
  feed: SocialPost[] = [];
  searchResults: SocialProfileSummary[] = [];
  selectedProfile: SocialProfile | null = null;
  selectedGrantHandles: string[] = [];
  composerUploads: ComposerUpload[] = [];

  displayName = '';
  bio = '';
  visibility: 'PUBLIC' | 'PRIVATE' = 'PUBLIC';
  newPostBody = '';
  searchQuery = '';
  searchScope: 'all' | 'mutual' = 'all';
  statusMessage = '';
  errorMessage = '';
  loading = true;
  publishingPost = false;

  constructor(private socialService: SocialService) { }

  ngOnInit(): void {
    void this.refresh();
    this.startAutoRefresh();
    this.destroyRef.onDestroy(() => {
      this.revokeRemoteMediaUrls();
      this.clearComposerUploads();
    });
  }

  async refresh(showLoading = true): Promise<void> {
    if (showLoading) {
      this.runInZone(() => {
        this.loading = true;
        this.errorMessage = '';
      });
    }

    const selectedProfileHandle = this.selectedProfile?.handle ?? null;

    try {
      const [viewer, feed, selectedProfile] = await Promise.all([
        firstValueFrom(this.socialService.viewer()),
        firstValueFrom(this.socialService.feed()),
        selectedProfileHandle
          ? firstValueFrom(this.socialService.profile(selectedProfileHandle)).catch((error) => {
            console.error('Failed to refresh selected profile', error);
            return null;
          })
          : Promise.resolve<SocialProfile | null>(null)
      ]);

      this.runInZone(() => {
        this.applyViewer(viewer);
        this.feed = feed.posts;
        if (selectedProfileHandle) {
          this.selectedProfile = selectedProfile;
        }
      });
      this.queueMediaLoadsFromCurrentState();
    } catch (error) {
      console.error('Failed to load social hub', error);
      this.runInZone(() => {
        this.errorMessage = 'Could not load the social hub.';
      });
    } finally {
      if (showLoading) {
        this.runInZone(() => {
          this.loading = false;
        });
      }
    }
  }

  async saveProfile(): Promise<void> {
    this.runInZone(() => this.resetMessages());

    try {
      const updated = await firstValueFrom(this.socialService.updateProfile({
        displayName: this.displayName,
        bio: this.bio,
        visibility: this.visibility
      }));

      this.runInZone(() => {
        this.me = updated;
        this.statusMessage = 'Profile saved.';
      });
      this.syncMediaForPosts(updated.recentPosts);
    } catch (error) {
      console.error('Failed to save profile', error);
      this.runInZone(() => {
        this.errorMessage = 'Could not save your profile.';
      });
    }
  }

  async publishPost(): Promise<void> {
    if (!this.newPostBody.trim() && !this.composerUploads.length) {
      return;
    }

    this.runInZone(() => {
      this.resetMessages();
      this.publishingPost = true;
    });

    try {
      const uploadedMedia = await Promise.all(
        this.composerUploads.map((upload) => firstValueFrom(this.socialService.uploadMedia(upload.file)))
      );
      const created = await firstValueFrom(
        this.socialService.createPost(
          this.newPostBody.trim(),
          uploadedMedia.map((media) => media.id)
        )
      );

      this.runInZone(() => {
        this.feed = [created, ...this.feed];
        if (this.me) {
          this.me = { ...this.me, recentPosts: [created, ...this.me.recentPosts].slice(0, 20) };
        }
        this.newPostBody = '';
        this.clearComposerUploads();
        this.statusMessage = 'Post published.';
      });
      this.syncMediaForPosts([created]);
    } catch (error) {
      console.error('Failed to publish post', error);
      this.runInZone(() => {
        this.errorMessage = 'Could not publish the post.';
      });
    } finally {
      this.runInZone(() => {
        this.publishingPost = false;
      });
    }
  }

  async performSearch(): Promise<void> {
    this.runInZone(() => this.resetMessages());

    try {
      const results = await firstValueFrom(this.socialService.searchProfiles(this.searchQuery.trim(), this.searchScope));
      this.runInZone(() => {
        this.searchResults = results;
      });
    } catch (error) {
      console.error('Failed to search profiles', error);
      this.runInZone(() => {
        this.errorMessage = 'Search failed.';
      });
    }
  }

  async openProfile(handle: string): Promise<void> {
    this.runInZone(() => this.resetMessages());

    try {
      const profile = await firstValueFrom(this.socialService.profile(handle));
      this.runInZone(() => {
        this.selectedProfile = profile;
      });
      this.syncMediaForPosts(profile.recentPosts);
    } catch (error) {
      console.error('Failed to load profile', error);
      this.runInZone(() => {
        this.selectedProfile = null;
        this.errorMessage = 'That profile is private or unavailable.';
      });
    }
  }

  async toggleFollow(profile: SocialProfileSummary): Promise<void> {
    this.runInZone(() => this.resetMessages());

    try {
      const updated = profile.following
        ? await firstValueFrom(this.socialService.unfollow(profile.handle))
        : await firstValueFrom(this.socialService.follow(profile.handle));
      this.runInZone(() => {
        this.mergeSearchProfile(updated);
        this.statusMessage = updated.following ? `Following @${updated.handle}.` : `Unfollowed @${updated.handle}.`;
      });
      await this.refreshViewerOnly();
      if (this.selectedProfile?.handle === updated.handle) {
        await this.openProfile(updated.handle);
      }
    } catch (error) {
      console.error('Failed to update follow state', error);
      this.runInZone(() => {
        this.errorMessage = 'Could not update the connection.';
      });
    }
  }

  async grantSelectedAccess(): Promise<void> {
    if (!this.me || !this.selectedGrantHandles.length) {
      return;
    }

    this.runInZone(() => this.resetMessages());

    try {
      const result = await firstValueFrom(this.socialService.grantAccess(this.me.handle, this.selectedGrantHandles));
      if (result.grantedHandles.length) {
        this.runInZone(() => {
          this.statusMessage = `Granted access to @${result.grantedHandles.join(', @')}.`;
          this.markGrantedAccess(result.grantedHandles);
          this.selectedGrantHandles = this.selectedGrantHandles
            .filter((handle) => !result.grantedHandles.includes(handle));
        });
        await this.refreshViewerOnly();
      }
      if (result.missingHandles.length) {
        this.runInZone(() => {
          this.errorMessage = `Missing profiles: ${result.missingHandles.join(', ')}`;
        });
      }
    } catch (error) {
      console.error('Failed to grant access', error);
      this.runInZone(() => {
        this.errorMessage = 'Could not grant access.';
      });
    }
  }

  async toggleReaction(post: SocialPost, reactionType: string): Promise<void> {
    this.runInZone(() => this.resetMessages());

    try {
      const updated = post.viewerReactions.includes(reactionType)
        ? await firstValueFrom(this.socialService.removeReaction(post.id, reactionType))
        : await firstValueFrom(this.socialService.addReaction(post.id, reactionType));
      this.runInZone(() => {
        this.replacePost(updated);
      });
      this.syncMediaForPosts([updated]);
    } catch (error) {
      console.error('Failed to update reaction', error);
      this.runInZone(() => {
        this.errorMessage = 'Could not update the reaction.';
      });
    }
  }

  onComposerFilesSelected(event: Event): void {
    const input = event.target as HTMLInputElement | null;
    const files = Array.from(input?.files ?? []);
    if (!files.length) {
      return;
    }

    this.resetMessages();

    const availableSlots = SocialHubComponent.MAX_FILES_PER_POST - this.composerUploads.length;
    if (availableSlots <= 0) {
      this.errorMessage = `You can attach up to ${SocialHubComponent.MAX_FILES_PER_POST} files per post.`;
      if (input) {
        input.value = '';
      }
      return;
    }

    const acceptedFiles = files.slice(0, availableSlots);
    const rejectedFiles = files.slice(availableSlots);
    const validUploads: ComposerUpload[] = [];

    for (const file of acceptedFiles) {
      const kind = this.resolveComposerMediaKind(file);
      if (!kind) {
        this.errorMessage = 'Only image and video files are supported.';
        continue;
      }
      validUploads.push({
        id: this.generateId(),
        file,
        kind,
        previewUrl: URL.createObjectURL(file)
      });
    }

    if (rejectedFiles.length) {
      this.errorMessage = `You can attach up to ${SocialHubComponent.MAX_FILES_PER_POST} files per post.`;
    }

    this.composerUploads = [...this.composerUploads, ...validUploads];
    if (input) {
      input.value = '';
    }
  }

  removeComposerUpload(uploadId: string): void {
    const upload = this.composerUploads.find((candidate) => candidate.id === uploadId);
    if (!upload) {
      return;
    }

    URL.revokeObjectURL(upload.previewUrl);
    this.composerUploads = this.composerUploads.filter((candidate) => candidate.id !== uploadId);
  }

  mediaUrl(mediaId: string): string | null {
    return this.mediaObjectUrls.get(mediaId) ?? null;
  }

  formatUploadSize(bytes: number): string {
    if (bytes < 1024) {
      return `${bytes} B`;
    }
    if (bytes < 1024 * 1024) {
      return `${Math.round(bytes / 1024)} KB`;
    }
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  get canGrantAccess(): boolean {
    return !!this.me && (this.visibility === 'PRIVATE' || this.me.visibility === 'PRIVATE');
  }

  get canSubmitSelectedAccess(): boolean {
    return this.canGrantAccess && this.selectedGrantHandles.length > 0;
  }

  get canPublishPost(): boolean {
    return (!!this.newPostBody.trim() || this.composerUploads.length > 0) && !this.publishingPost;
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

  trackByMedia(_: number, media: SocialPostMedia): string {
    return media.id;
  }

  trackByComposerUpload(_: number, upload: ComposerUpload): string {
    return upload.id;
  }

  private async refreshViewerOnly(): Promise<void> {
    const viewer = await firstValueFrom(this.socialService.viewer());
    this.runInZone(() => {
      this.applyViewer(viewer);
    });
    this.syncMediaForPosts(viewer.me.recentPosts);
  }

  private startAutoRefresh(): void {
    this.autoRefreshTimer = this.ngZone.runOutsideAngular(() => setInterval(() => {
      if (document.visibilityState === 'hidden') {
        return;
      }

      void this.refresh(false);
    }, SocialHubComponent.AUTO_REFRESH_INTERVAL_MS));

    this.destroyRef.onDestroy(() => {
      if (!this.autoRefreshTimer) {
        return;
      }

      clearInterval(this.autoRefreshTimer);
      this.autoRefreshTimer = null;
    });
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

  private queueMediaLoadsFromCurrentState(): void {
    this.syncMediaForPosts(this.feed);
    this.syncMediaForPosts(this.me?.recentPosts ?? []);
    this.syncMediaForPosts(this.selectedProfile?.recentPosts ?? []);
  }

  private syncMediaForPosts(posts: SocialPost[]): void {
    for (const post of posts) {
      for (const media of post.media) {
        this.ensureMediaObjectUrl(media);
      }
    }
  }

  private ensureMediaObjectUrl(media: SocialPostMedia): void {
    if (this.mediaObjectUrls.has(media.id) || this.loadingMediaIds.has(media.id)) {
      return;
    }

    this.loadingMediaIds.add(media.id);
    void firstValueFrom(this.socialService.mediaBlob(media.id))
      .then((blob) => {
        const objectUrl = URL.createObjectURL(blob);
        this.runInZone(() => {
          this.mediaObjectUrls.set(media.id, objectUrl);
        });
      })
      .catch((error) => {
        console.error('Failed to load media blob', error);
      })
      .finally(() => {
        this.runInZone(() => {
          this.loadingMediaIds.delete(media.id);
        });
      });
  }

  private clearComposerUploads(): void {
    for (const upload of this.composerUploads) {
      URL.revokeObjectURL(upload.previewUrl);
    }
    this.composerUploads = [];
  }

  private revokeRemoteMediaUrls(): void {
    for (const objectUrl of this.mediaObjectUrls.values()) {
      URL.revokeObjectURL(objectUrl);
    }
    this.mediaObjectUrls.clear();
    this.loadingMediaIds.clear();
  }

  private resolveComposerMediaKind(file: File): 'IMAGE' | 'VIDEO' | null {
    if (file.type.startsWith('image/')) {
      return 'IMAGE';
    }
    if (file.type.startsWith('video/')) {
      return 'VIDEO';
    }
    return null;
  }

  private generateId(): string {
    return typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `upload-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  private resetMessages(): void {
    this.statusMessage = '';
    this.errorMessage = '';
  }

  private runInZone<T>(callback: () => T): T {
    const result = NgZone.isInAngularZone() ? callback() : this.ngZone.run(callback);
    this.changeDetectorRef.detectChanges();
    return result;
  }
}
