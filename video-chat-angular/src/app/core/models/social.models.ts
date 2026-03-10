export type ProfileVisibility = 'PUBLIC' | 'PRIVATE';

export interface SocialPost {
  id: string;
  authorHandle: string;
  authorDisplayName: string;
  authorAvatarUrl: string | null;
  body: string;
  createdAt: string;
  reactionCounts: Record<string, number>;
  viewerReactions: string[];
}

export interface SocialProfileSummary {
  handle: string;
  displayName: string;
  avatarUrl: string | null;
  visibility: ProfileVisibility;
  canView: boolean;
  following: boolean;
  followsViewer: boolean;
  mutualConnection: boolean;
  accessGranted: boolean;
}

export interface SocialProfile extends SocialProfileSummary {
  bio: string;
  followerCount: number;
  followingCount: number;
  recentPosts: SocialPost[];
}

export interface SocialViewerResponse {
  me: SocialProfile;
  accessGrants: SocialProfileSummary[];
}

export interface SocialFeedResponse {
  me: SocialProfile;
  posts: SocialPost[];
}

export interface GrantAccessResult {
  grantedHandles: string[];
  missingHandles: string[];
}
