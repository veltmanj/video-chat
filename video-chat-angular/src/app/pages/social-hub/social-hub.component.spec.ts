import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { type MockedObject, vi } from 'vitest';
import { SocialFeedResponse, SocialProfileSummary, SocialViewerResponse } from '../../core/models/social.models';
import { SocialService } from '../../core/services/social.service';
import { SocialHubComponent } from './social-hub.component';

describe('SocialHubComponent', () => {
  let fixture: ComponentFixture<SocialHubComponent>;
  let component: SocialHubComponent;
  let socialService: MockedObject<SocialService>;

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

    await TestBed.configureTestingModule({
      imports: [SocialHubComponent],
      providers: [{ provide: SocialService, useValue: socialService }]
    }).compileComponents();

    fixture = TestBed.createComponent(SocialHubComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
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
});
