import { CommonModule } from '@angular/common';
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SocialProfile, SocialProfileSummary } from '../../core/models/social.models';

@Component({
  selector: 'app-social-profile-column',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './social-profile-column.component.html',
  styleUrl: './social-profile-column.component.scss'
})
export class SocialProfileColumnComponent {
  @Input() profile: SocialProfile | null = null;
  @Input() accessGrants: SocialProfileSummary[] = [];
  @Input() avatarUrl: string | null = null;
  @Input() profileInitials = 'PR';
  @Input() avatarUploading = false;
  @Input() displayName = '';
  @Input() bio = '';
  @Input() visibility: 'PUBLIC' | 'PRIVATE' = 'PUBLIC';

  @Output() displayNameChange = new EventEmitter<string>();
  @Output() bioChange = new EventEmitter<string>();
  @Output() visibilityChange = new EventEmitter<'PUBLIC' | 'PRIVATE'>();
  @Output() avatarFileSelected = new EventEmitter<Event>();
  @Output() clearAvatar = new EventEmitter<void>();
  @Output() saveProfile = new EventEmitter<void>();

  trackByHandle(_: number, profile: SocialProfileSummary): string {
    return profile.handle;
  }
}
