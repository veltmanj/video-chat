insert into profiles (id, subject, email, display_name, handle, avatar_url, bio, visibility, created_at, updated_at) values
('11111111-1111-1111-1111-111111111111', 'dev:user:01', 'alice@example.test', 'Alice Mercer', 'alice', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Alice', 'Product-minded host who shares release notes and call recaps.', 'PUBLIC', '2026-03-10T08:00:00Z', '2026-03-10T08:00:00Z'),
('22222222-2222-2222-2222-222222222222', 'dev:user:02', 'bella@example.test', 'Bella Stone', 'bella', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Bella', 'Collects UX feedback and posts quick experiment writeups.', 'PUBLIC', '2026-03-10T08:01:00Z', '2026-03-10T08:01:00Z'),
('33333333-3333-3333-3333-333333333333', 'dev:user:03', 'carlo@example.test', 'Carlo Vega', 'carlo', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Carlo', 'Backend engineer sharing scaling notes and incident learnings.', 'PUBLIC', '2026-03-10T08:02:00Z', '2026-03-10T08:02:00Z'),
('44444444-4444-4444-4444-444444444444', 'dev:user:04', 'dina@example.test', 'Dina Brooks', 'dina', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Dina', 'Community operator focused on live events and moderation.', 'PUBLIC', '2026-03-10T08:03:00Z', '2026-03-10T08:03:00Z'),
('55555555-5555-5555-5555-555555555555', 'dev:user:05', 'evan@example.test', 'Evan Reid', 'evan', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Evan', 'Runs demos and documents onboarding issues.', 'PUBLIC', '2026-03-10T08:04:00Z', '2026-03-10T08:04:00Z'),
('66666666-6666-6666-6666-666666666666', 'dev:user:06', 'farah@example.test', 'Farah Noor', 'farah', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Farah', 'Design systems lead posting interaction explorations.', 'PUBLIC', '2026-03-10T08:05:00Z', '2026-03-10T08:05:00Z'),
('77777777-7777-7777-7777-777777777777', 'dev:user:07', 'gianni@example.test', 'Gianni Russo', 'gianni', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Gianni', 'Prefers short technical notes over long status decks.', 'PUBLIC', '2026-03-10T08:06:00Z', '2026-03-10T08:06:00Z'),
('88888888-8888-8888-8888-888888888888', 'dev:user:08', 'hana@example.test', 'Hana Kim', 'hana', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Hana', 'QA lead capturing reproducible edge cases for the team.', 'PUBLIC', '2026-03-10T08:07:00Z', '2026-03-10T08:07:00Z'),
('99999999-9999-9999-9999-999999999999', 'dev:user:09', 'jasper@example.test', 'Jasper Cole', 'jasper', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Jasper', 'Growth experiments, launch checklists, and retention notes.', 'PUBLIC', '2026-03-10T08:08:00Z', '2026-03-10T08:08:00Z'),
('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'dev:user:10', 'kiara@example.test', 'Kiara Flynn', 'kiara', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Kiara', 'Likes async brainstorms and follow-up summaries.', 'PUBLIC', '2026-03-10T08:09:00Z', '2026-03-10T08:09:00Z'),
('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'dev:user:11', 'luna@example.test', 'Luna Harper', 'luna', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Luna', 'Private profile used to test invitation-only access.', 'PRIVATE', '2026-03-10T08:10:00Z', '2026-03-10T08:10:00Z'),
('cccccccc-cccc-cccc-cccc-cccccccccccc', 'dev:user:12', 'marco@example.test', 'Marco Ivers', 'marco', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Marco', 'Private founder diary with sparse but important updates.', 'PRIVATE', '2026-03-10T08:11:00Z', '2026-03-10T08:11:00Z'),
('dddddddd-dddd-dddd-dddd-dddddddddddd', 'dev:user:13', 'nina@example.test', 'Nina Shah', 'nina', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Nina', 'Private recruiting and partnership notes.', 'PRIVATE', '2026-03-10T08:12:00Z', '2026-03-10T08:12:00Z'),
('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', 'dev:user:14', 'omar@example.test', 'Omar Aziz', 'omar', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Omar', 'Private advisor profile for trust and access workflows.', 'PRIVATE', '2026-03-10T08:13:00Z', '2026-03-10T08:13:00Z'),
('ffffffff-ffff-ffff-ffff-ffffffffffff', 'dev:user:15', 'priya@example.test', 'Priya Sen', 'priya', 'https://api.dicebear.com/7.x/thumbs/svg?seed=Priya', 'Private profile with a small invited test circle.', 'PRIVATE', '2026-03-10T08:14:00Z', '2026-03-10T08:14:00Z')
on conflict (id) do nothing;

insert into profile_follows (follower_profile_id, followed_profile_id, created_at) values
('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', '2026-03-10T09:00:00Z'),
('22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', '2026-03-10T09:00:00Z'),
('11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333', '2026-03-10T09:01:00Z'),
('33333333-3333-3333-3333-333333333333', '11111111-1111-1111-1111-111111111111', '2026-03-10T09:01:00Z'),
('22222222-2222-2222-2222-222222222222', '44444444-4444-4444-4444-444444444444', '2026-03-10T09:02:00Z'),
('44444444-4444-4444-4444-444444444444', '22222222-2222-2222-2222-222222222222', '2026-03-10T09:02:00Z'),
('55555555-5555-5555-5555-555555555555', '66666666-6666-6666-6666-666666666666', '2026-03-10T09:03:00Z'),
('66666666-6666-6666-6666-666666666666', '55555555-5555-5555-5555-555555555555', '2026-03-10T09:03:00Z'),
('77777777-7777-7777-7777-777777777777', '88888888-8888-8888-8888-888888888888', '2026-03-10T09:04:00Z'),
('88888888-8888-8888-8888-888888888888', '77777777-7777-7777-7777-777777777777', '2026-03-10T09:04:00Z'),
('99999999-9999-9999-9999-999999999999', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '2026-03-10T09:05:00Z'),
('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '99999999-9999-9999-9999-999999999999', '2026-03-10T09:05:00Z'),
('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'cccccccc-cccc-cccc-cccc-cccccccccccc', '2026-03-10T09:06:00Z'),
('cccccccc-cccc-cccc-cccc-cccccccccccc', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '2026-03-10T09:06:00Z'),
('dddddddd-dddd-dddd-dddd-dddddddddddd', 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', '2026-03-10T09:07:00Z'),
('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', 'dddddddd-dddd-dddd-dddd-dddddddddddd', '2026-03-10T09:07:00Z'),
('11111111-1111-1111-1111-111111111111', '55555555-5555-5555-5555-555555555555', '2026-03-10T09:08:00Z'),
('22222222-2222-2222-2222-222222222222', '66666666-6666-6666-6666-666666666666', '2026-03-10T09:09:00Z'),
('33333333-3333-3333-3333-333333333333', '77777777-7777-7777-7777-777777777777', '2026-03-10T09:10:00Z'),
('44444444-4444-4444-4444-444444444444', '99999999-9999-9999-9999-999999999999', '2026-03-10T09:11:00Z'),
('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'ffffffff-ffff-ffff-ffff-ffffffffffff', '2026-03-10T09:12:00Z'),
('ffffffff-ffff-ffff-ffff-ffffffffffff', '22222222-2222-2222-2222-222222222222', '2026-03-10T09:13:00Z')
on conflict do nothing;

insert into profile_access_grants (owner_profile_id, viewer_profile_id, granted_at) values
('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '11111111-1111-1111-1111-111111111111', '2026-03-10T10:00:00Z'),
('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '22222222-2222-2222-2222-222222222222', '2026-03-10T10:01:00Z'),
('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111', '2026-03-10T10:02:00Z'),
('dddddddd-dddd-dddd-dddd-dddddddddddd', 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', '2026-03-10T10:03:00Z'),
('dddddddd-dddd-dddd-dddd-dddddddddddd', 'ffffffff-ffff-ffff-ffff-ffffffffffff', '2026-03-10T10:04:00Z'),
('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', 'dddddddd-dddd-dddd-dddd-dddddddddddd', '2026-03-10T10:05:00Z'),
('ffffffff-ffff-ffff-ffff-ffffffffffff', '22222222-2222-2222-2222-222222222222', '2026-03-10T10:06:00Z'),
('ffffffff-ffff-ffff-ffff-ffffffffffff', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '2026-03-10T10:07:00Z')
on conflict do nothing;

insert into posts (id, author_profile_id, body, created_at, updated_at) values
('00000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', 'Pinned a short walkthrough for first-time hosts using the social feed.', '2026-03-10T11:00:00Z', '2026-03-10T11:00:00Z'),
('00000000-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222', 'Testing reactions on a public profile with a design feedback prompt.', '2026-03-10T11:05:00Z', '2026-03-10T11:05:00Z'),
('00000000-0000-0000-0000-000000000003', '33333333-3333-3333-3333-333333333333', 'Documented a database failover rehearsal for the team timeline.', '2026-03-10T11:10:00Z', '2026-03-10T11:10:00Z'),
('00000000-0000-0000-0000-000000000004', '44444444-4444-4444-4444-444444444444', 'Inviting moderators to a private retro after tonight''s event.', '2026-03-10T11:15:00Z', '2026-03-10T11:15:00Z'),
('00000000-0000-0000-0000-000000000005', '55555555-5555-5555-5555-555555555555', 'Posted a reproducible onboarding issue from mobile Safari.', '2026-03-10T11:20:00Z', '2026-03-10T11:20:00Z'),
('00000000-0000-0000-0000-000000000006', '66666666-6666-6666-6666-666666666666', 'Shared two alternative card layouts for the social hub.', '2026-03-10T11:25:00Z', '2026-03-10T11:25:00Z'),
('00000000-0000-0000-0000-000000000007', '77777777-7777-7777-7777-777777777777', 'Latency stayed flat after enabling the new persistence layer.', '2026-03-10T11:30:00Z', '2026-03-10T11:30:00Z'),
('00000000-0000-0000-0000-000000000008', '88888888-8888-8888-8888-888888888888', 'Captured three edge cases around private profile search results.', '2026-03-10T11:35:00Z', '2026-03-10T11:35:00Z'),
('00000000-0000-0000-0000-000000000009', '99999999-9999-9999-9999-999999999999', 'Trying a launch checklist post to validate bulk reactions.', '2026-03-10T11:40:00Z', '2026-03-10T11:40:00Z'),
('00000000-0000-0000-0000-000000000010', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Async brainstorm: what should appear on a follow recommendation panel?', '2026-03-10T11:45:00Z', '2026-03-10T11:45:00Z'),
('00000000-0000-0000-0000-000000000011', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'Private note for invited collaborators reviewing the trust flow.', '2026-03-10T11:50:00Z', '2026-03-10T11:50:00Z'),
('00000000-0000-0000-0000-000000000012', 'ffffffff-ffff-ffff-ffff-ffffffffffff', 'Small-circle update to test invited access on a private account.', '2026-03-10T11:55:00Z', '2026-03-10T11:55:00Z')
on conflict (id) do nothing;

insert into post_reactions (post_id, reactor_profile_id, reaction_type, created_at) values
('00000000-0000-0000-0000-000000000001', '22222222-2222-2222-2222-222222222222', 'LIKE', '2026-03-10T12:00:00Z'),
('00000000-0000-0000-0000-000000000001', '33333333-3333-3333-3333-333333333333', 'FIRE', '2026-03-10T12:01:00Z'),
('00000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', 'LIKE', '2026-03-10T12:02:00Z'),
('00000000-0000-0000-0000-000000000002', '66666666-6666-6666-6666-666666666666', 'IDEA', '2026-03-10T12:03:00Z'),
('00000000-0000-0000-0000-000000000003', '77777777-7777-7777-7777-777777777777', 'LIKE', '2026-03-10T12:04:00Z'),
('00000000-0000-0000-0000-000000000004', '22222222-2222-2222-2222-222222222222', 'SUPPORT', '2026-03-10T12:05:00Z'),
('00000000-0000-0000-0000-000000000005', '88888888-8888-8888-8888-888888888888', 'LIKE', '2026-03-10T12:06:00Z'),
('00000000-0000-0000-0000-000000000006', '55555555-5555-5555-5555-555555555555', 'APPLAUD', '2026-03-10T12:07:00Z'),
('00000000-0000-0000-0000-000000000007', '33333333-3333-3333-3333-333333333333', 'FIRE', '2026-03-10T12:08:00Z'),
('00000000-0000-0000-0000-000000000008', '11111111-1111-1111-1111-111111111111', 'LIKE', '2026-03-10T12:09:00Z'),
('00000000-0000-0000-0000-000000000009', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'IDEA', '2026-03-10T12:10:00Z'),
('00000000-0000-0000-0000-000000000010', '99999999-9999-9999-9999-999999999999', 'LIKE', '2026-03-10T12:11:00Z'),
('00000000-0000-0000-0000-000000000011', '11111111-1111-1111-1111-111111111111', 'SUPPORT', '2026-03-10T12:12:00Z'),
('00000000-0000-0000-0000-000000000012', '22222222-2222-2222-2222-222222222222', 'LIKE', '2026-03-10T12:13:00Z'),
('00000000-0000-0000-0000-000000000012', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'SUPPORT', '2026-03-10T12:14:00Z')
on conflict do nothing;
