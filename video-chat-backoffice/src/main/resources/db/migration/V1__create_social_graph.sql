create table if not exists profiles (
    id uuid primary key,
    subject varchar(255) not null unique,
    email varchar(320),
    display_name varchar(80) not null,
    handle varchar(32) not null unique,
    avatar_url text,
    bio varchar(400) not null default '',
    visibility varchar(16) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table if not exists profile_follows (
    follower_profile_id uuid not null references profiles(id) on delete cascade,
    followed_profile_id uuid not null references profiles(id) on delete cascade,
    created_at timestamptz not null,
    primary key (follower_profile_id, followed_profile_id),
    check (follower_profile_id <> followed_profile_id)
);

create table if not exists profile_access_grants (
    owner_profile_id uuid not null references profiles(id) on delete cascade,
    viewer_profile_id uuid not null references profiles(id) on delete cascade,
    granted_at timestamptz not null,
    primary key (owner_profile_id, viewer_profile_id),
    check (owner_profile_id <> viewer_profile_id)
);

create table if not exists posts (
    id uuid primary key,
    author_profile_id uuid not null references profiles(id) on delete cascade,
    body varchar(2000) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table if not exists post_reactions (
    post_id uuid not null references posts(id) on delete cascade,
    reactor_profile_id uuid not null references profiles(id) on delete cascade,
    reaction_type varchar(32) not null,
    created_at timestamptz not null,
    primary key (post_id, reactor_profile_id, reaction_type)
);

create index if not exists idx_profiles_handle on profiles (handle);
create index if not exists idx_profiles_display_name on profiles (lower(display_name));
create index if not exists idx_profile_follows_followed on profile_follows (followed_profile_id);
create index if not exists idx_profile_access_grants_viewer on profile_access_grants (viewer_profile_id);
create index if not exists idx_posts_author_created on posts (author_profile_id, created_at desc);
create index if not exists idx_posts_created on posts (created_at desc);
create index if not exists idx_post_reactions_post_id on post_reactions (post_id);
