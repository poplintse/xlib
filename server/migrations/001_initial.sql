create table users (
    id bigint generated always as identity,
    public_id uuid not null,
    email_normalized text not null,
    token_hash bytea not null,
    token_ciphertext bytea not null,
    status text not null default 'active',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint users_pkey primary key (id),
    constraint users_public_id_key unique (public_id),
    constraint users_email_normalized_key unique (email_normalized),
    constraint users_token_hash_key unique (token_hash),
    constraint users_status_check check (status in ('active', 'disabled', 'deleting')),
    constraint users_email_length_check check (char_length(email_normalized) between 3 and 254),
    constraint users_token_hash_length_check check (octet_length(token_hash) = 32),
    constraint users_token_ciphertext_length_check check (octet_length(token_ciphertext) > 29)
);

create table devices (
    id bigint generated always as identity,
    user_id bigint not null,
    device_uid uuid not null,
    device_name text not null,
    platform text not null,
    app_version text not null,
    last_seen_at timestamptz not null default now(),
    revoked_at timestamptz,
    created_at timestamptz not null default now(),
    constraint devices_pkey primary key (id),
    constraint devices_user_id_fkey foreign key (user_id) references users (id) on delete cascade,
    constraint devices_user_uid_key unique (user_id, device_uid),
    constraint devices_id_uid_key unique (id, device_uid),
    constraint devices_id_user_key unique (id, user_id),
    constraint devices_id_user_uid_key unique (id, user_id, device_uid),
    constraint devices_name_length_check check (char_length(device_name) between 1 and 80),
    constraint devices_app_version_length_check check (char_length(app_version) between 1 and 40),
    constraint devices_platform_check check (platform in ('ios', 'android'))
);

create index devices_user_id_idx on devices (user_id);

create table reading_progress (
    id bigint generated always as identity,
    user_id bigint not null,
    book_hash bytea not null,
    file_size bigint not null,
    offset_bytes bigint not null,
    read_at timestamptz not null,
    device_id bigint not null,
    device_uid_order uuid not null,
    version bigint not null default 1,
    received_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint reading_progress_pkey primary key (id),
    constraint reading_progress_user_id_fkey foreign key (user_id) references users (id) on delete cascade,
    constraint reading_progress_device_fkey foreign key (device_id, user_id, device_uid_order) references devices (id, user_id, device_uid),
    constraint reading_progress_book_key unique (user_id, book_hash, file_size),
    constraint reading_progress_hash_length_check check (octet_length(book_hash) = 32),
    constraint reading_progress_file_size_check check (file_size > 0 and file_size <= 9007199254740991),
    constraint reading_progress_offset_check check (offset_bytes >= 0 and offset_bytes <= file_size),
    constraint reading_progress_read_at_check check (
      read_at >= timestamptz '1970-01-01 00:00:00+00'
      and read_at <= timestamptz '9999-12-31 23:59:59.999+00'
    ),
    constraint reading_progress_version_check check (version > 0)
);

create index reading_progress_user_id_idx on reading_progress (user_id);
create index reading_progress_device_id_idx on reading_progress (device_id);
