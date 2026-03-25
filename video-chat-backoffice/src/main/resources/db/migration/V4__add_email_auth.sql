create table if not exists email_accounts (
    id uuid primary key,
    email varchar(320) not null unique,
    display_name varchar(80) not null,
    verified_at timestamp,
    created_at timestamp not null,
    updated_at timestamp not null
);

create table if not exists email_auth_challenges (
    id uuid primary key,
    email_account_id uuid not null references email_accounts(id) on delete cascade,
    purpose varchar(32) not null,
    token_hash varchar(128) not null unique,
    expires_at timestamp not null,
    created_at timestamp not null,
    consumed_at timestamp
);
