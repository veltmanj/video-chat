import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { type MockedObject } from 'vitest';
import { SocialFeedResponse, SocialPost, SocialPostMedia, SocialProfileSummary, SocialViewerResponse } from '../../core/models/social.models';
import { SocialService } from '../../core/services/social.service';
import { SocialHubComponent } from './social-hub.component';

describe('SocialHubComponent', () => {
  let fixture: ComponentFixture<SocialHubComponent>;
  let component: SocialHubComponent;
  let socialService: MockedObject<SocialService>;

  const createObjectUrl = vi.fn(() => 'blob:preview');
  const revokeObjectUrl = vi.fn();

  const initialViewer: SocialViewerResponse = {
    me: {
      handle: 'alice',
      displayName: 'Alice Example',
      bio: 'Private updates only',
      avatarUrl: null,
      visibility: 'PRIVATE',
      canView: true,
      following: false,
      followsViewer: false,
      mutualConnection: false,
      accessGranted: false,
      followerCount: 2,
      followingCount: 1,
      recentPosts: []
    },
    accessGrants: []
  };

  const updatedViewer: SocialViewerResponse = {
    ...initialViewer,
    accessGrants: [
      {
        handle: 'bob',
        displayName: 'Bob Example',
        avatarUrl: null,
        visibility: 'PUBLIC',
        canView: true,
        following: false,
        followsViewer: false,
        mutualConnection: false,
        accessGranted: true
      },
      {
        handle: 'carol',
        displayName: 'Carol Example',
        avatarUrl: null,
        visibility: 'PUBLIC',
        canView: true,
        following: false,
        followsViewer: false,
        mutualConnection: false,
        accessGranted: true
      }
    ]
  };

  const feedResponse: SocialFeedResponse = {
    me: initialViewer.me,
    posts: []
  };

  beforeEach(async () => {
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectUrl, configurable: true });
    Object.defineProperty(URL, 'revokeObjectURL', { value: revokeObjectUrl, configurable: true });

    socialService = {
      viewer: vi.fn(),
      updateProfile: vi.fn(),
      feed: vi.fn(),
      searchProfiles: vi.fn(),
      profile: vi.fn(),
      follow: vi.fn(),
      unfollow: vi.fn(),
      grantAccess: vi.fn(),
      createPost: vi.fn(),
      uploadMedia: vi.fn(),
      mediaBlob: vi.fn(),
      addReaction: vi.fn(),
      removeReaction: vi.fn()
    } as unknown as MockedObject<SocialService>;

    socialService.viewer.mockReturnValueOnce(of(initialViewer)).mockReturnValueOnce(of(updatedViewer));
    socialService.feed.mockReturnValue(of(feedResponse));
    socialService.searchProfiles.mockReturnValue(of([
      createSummary('bob', 'Bob Example'),
      createSummary('carol', 'Carol Example')
    ]));
    socialService.grantAccess.mockReturnValue(of({
      grantedHandles: ['bob', 'carol'],
      missingHandles: []
    }));
    socialService.mediaBlob.mockReturnValue(of(new Blob(['media'])));

    await TestBed.configureTestingModule({
      imports: [SocialHubComponent],
      providers: [{ provide: SocialService, useValue: socialService }]
    }).compileComponents();

    fixture = TestBed.createComponent(SocialHubComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
  }, 20000);

  afterEach(() => {
    fixture.destroy();
    vi.clearAllMocks();
  });

  it('loads the viewer profile and feed on init', () => {
    expect(socialService.viewer).toHaveBeenCalledTimes(1);
    expect(socialService.feed).toHaveBeenCalledTimes(1);
    expect(component.me?.handle).toBe('alice');
    expect(component.visibility).toBe('PRIVATE');
    expect(component.feed).toEqual([]);
  });

  it('grants access to multiple selected profiles in one request', async () => {
    await component.performSearch();

    component.toggleGrantSelection(component.searchResults[0]);
    component.toggleGrantSelection(component.searchResults[1]);

    expect(component.selectedGrantHandles).toEqual(['bob', 'carol']);
    expect(component.canSubmitSelectedAccess).toBe(true);

    await component.grantSelectedAccess();

    expect(socialService.grantAccess).toHaveBeenCalledWith('alice', ['bob', 'carol']);
    expect(component.selectedGrantHandles).toEqual([]);
    expect(component.accessGrants).toHaveLength(2);
    expect(component.searchResults.every((profile) => profile.accessGranted)).toBe(true);
    expect(component.statusMessage).toContain('@bob');
    expect(component.statusMessage).toContain('@carol');
  });

  it('refreshes the viewer after following another profile', async () => {
    socialService.follow.mockReturnValue(of({
      ...createSummary('bob', 'Bob Example'),
      following: true
    }));

    await component.performSearch();
    await component.toggleFollow(component.searchResults[0]);

    expect(socialService.follow).toHaveBeenCalledWith('bob');
    expect(socialService.viewer).toHaveBeenCalledTimes(2);
    expect(component.statusMessage).toBe('Following @bob.');
  });

  it('uploads selected media before publishing a post', async () => {
    const uploadedMedia = createMedia('media-1');
    const createdPost = createPost('post-1', 'New post with media', [uploadedMedia]);
    const file = new File(['image'], 'photo.png', { type: 'image/png' });

    socialService.uploadMedia.mockReturnValue(of(uploadedMedia));
    socialService.createPost.mockReturnValue(of(createdPost));
    component.newPostBody = 'New post with media';

    component.onComposerFilesSelected({
      target: {
        files: [file],
        value: ''
      }
    } as unknown as Event);

    await component.publishPost();

    expect(socialService.uploadMedia).toHaveBeenCalledTimes(1);
    expect(socialService.createPost).toHaveBeenCalledWith('New post with media', ['media-1']);
    expect(component.feed[0]).toEqual(createdPost);
    expect(component.composerUploads).toEqual([]);
    expect(component.statusMessage).toBe('Post published.');
  });

  function createSummary(handle: string, displayName: string): SocialProfileSummary {
    return {
      handle,
      displayName,
      avatarUrl: null,
      visibility: 'PUBLIC',
      canView: true,
      following: false,
      followsViewer: false,
      mutualConnection: false,
      accessGranted: false
    };
  }

  function createMedia(id: string): SocialPostMedia {
    return {
      id,
      kind: 'IMAGE',
      mimeType: 'image/png',
      fileName: 'photo.png',
      fileSize: 128
    };
  }

  function createPost(id: string, body: string, media: SocialPostMedia[]): SocialPost {
    return {
      id,
      authorHandle: 'alice',
      authorDisplayName: 'Alice Example',
      authorAvatarUrl: null,
      body,
      createdAt: new Date().toISOString(),
      media,
      reactionCounts: {},
      viewerReactions: []
    };
  }
});
