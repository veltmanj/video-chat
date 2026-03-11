create table if not exists media_assets (
    id uuid primary key,
    owner_profile_id uuid not null references profiles(id) on delete cascade,
    storage_key varchar(512) not null unique,
    original_filename varchar(255) not null,
    mime_type varchar(255) not null,
    media_kind varchar(16) not null,
    file_size bigint not null,
    created_at timestamp with time zone not null
);

create table if not exists post_media_assets (
    post_id uuid not null references posts(id) on delete cascade,
    media_asset_id uuid not null unique references media_assets(id) on delete cascade,
    sort_order integer not null,
    primary key (post_id, media_asset_id)
);

create index if not exists idx_media_assets_owner on media_assets (owner_profile_id, created_at desc);
create index if not exists idx_post_media_assets_post on post_media_assets (post_id, sort_order asc);
