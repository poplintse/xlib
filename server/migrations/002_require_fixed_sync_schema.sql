do $$
begin
  if not exists (
    select 1
    from information_schema.columns
    where table_schema = current_schema()
      and table_name = 'users'
      and column_name = 'email_normalized'
  ) or exists (
    select 1
    from information_schema.tables
    where table_schema = current_schema()
      and table_name in ('sessions', 'used_refresh_tokens')
  ) then
    raise exception using
      message = 'legacy password/session schema detected',
      hint = 'back up the database and perform an explicit legacy data migration before starting this release';
  end if;
end
$$;
