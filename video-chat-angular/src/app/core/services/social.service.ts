import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  GrantAccessResult,
  SocialFeedResponse,
  SocialPostMedia,
  SocialPost,
  SocialProfile,
  SocialProfileSummary,
  SocialViewerResponse
} from '../models/social.models';

@Injectable({ providedIn: 'root' })
export class SocialService {
  private readonly baseUrl = '/social-api/social/v1';

  constructor(private http: HttpClient) { }

  viewer(): Observable<SocialViewerResponse> {
    return this.http.get<SocialViewerResponse>(`${this.baseUrl}/me`);
  }

  updateProfile(payload: { displayName: string; bio: string; visibility: 'PUBLIC' | 'PRIVATE' }): Observable<SocialProfile> {
    return this.http.put<SocialProfile>(`${this.baseUrl}/me`, payload);
  }

  feed(): Observable<SocialFeedResponse> {
    return this.http.get<SocialFeedResponse>(`${this.baseUrl}/feed`);
  }

  searchProfiles(query: string, scope: 'all' | 'mutual' = 'all'): Observable<SocialProfileSummary[]> {
    const params = new HttpParams().set('q', query).set('scope', scope);
    return this.http.get<SocialProfileSummary[]>(`${this.baseUrl}/profiles/search`, { params });
  }

  profile(handle: string): Observable<SocialProfile> {
    return this.http.get<SocialProfile>(`${this.baseUrl}/profiles/${handle}`);
  }

  follow(handle: string): Observable<SocialProfileSummary> {
    return this.http.post<SocialProfileSummary>(`${this.baseUrl}/profiles/${handle}/follow`, {});
  }

  unfollow(handle: string): Observable<SocialProfileSummary> {
    return this.http.delete<SocialProfileSummary>(`${this.baseUrl}/profiles/${handle}/follow`);
  }

  grantAccess(ownerHandle: string, viewerHandles: string[]): Observable<GrantAccessResult> {
    return this.http.post<GrantAccessResult>(`${this.baseUrl}/profiles/${ownerHandle}/access-grants`, { viewerHandles });
  }

  createPost(body: string, mediaIds: string[] = []): Observable<SocialPost> {
    return this.http.post<SocialPost>(`${this.baseUrl}/posts`, { body, mediaIds });
  }

  uploadMedia(file: File): Observable<SocialPostMedia> {
    const formData = new FormData();
    formData.append('file', file, file.name);
    return this.http.post<SocialPostMedia>(`${this.baseUrl}/media/uploads`, formData);
  }

  mediaBlob(mediaId: string): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/media/${mediaId}/content`, { responseType: 'blob' });
  }

  addReaction(postId: string, reactionType: string): Observable<SocialPost> {
    return this.http.post<SocialPost>(`${this.baseUrl}/posts/${postId}/reactions`, { reactionType });
  }

  removeReaction(postId: string, reactionType: string): Observable<SocialPost> {
    return this.http.delete<SocialPost>(`${this.baseUrl}/posts/${postId}/reactions/${encodeURIComponent(reactionType)}`);
  }
}
