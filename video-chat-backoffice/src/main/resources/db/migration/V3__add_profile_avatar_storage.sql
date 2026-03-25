alter table profiles
    add column if not exists avatar_storage_key varchar(512);

alter table profiles
    add column if not exists avatar_content_type varchar(255);
