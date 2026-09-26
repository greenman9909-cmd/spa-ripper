-- Applied to WebLoom production project: user-scoped RLS and JWT-backed storage.
-- Kept as a repo migration so local/dev environments match production.

alter table public.profiles
  add column if not exists display_name text,
  add column if not exists settings jsonb not null default '{"capture_mode":"standard","export_format":"zip","project_naming":"hostname"}'::jsonb;

drop policy if exists "profiles own row" on public.profiles;
drop policy if exists "profiles select own" on public.profiles;
drop policy if exists "profiles update own" on public.profiles;
create policy "profiles select own" on public.profiles for select to authenticated using (auth.uid() = id);
create policy "profiles update own" on public.profiles for update to authenticated using (auth.uid() = id) with check (auth.uid() = id);
revoke update on public.profiles from authenticated;
grant select on public.profiles to authenticated;
grant update(display_name, settings) on public.profiles to authenticated;

drop policy if exists "projects own rows" on public.projects;
drop policy if exists "projects select own" on public.projects;
drop policy if exists "projects insert own" on public.projects;
drop policy if exists "projects update own" on public.projects;
drop policy if exists "projects delete own" on public.projects;
create policy "projects select own" on public.projects for select to authenticated using (auth.uid() = user_id);
create policy "projects insert own" on public.projects for insert to authenticated with check (auth.uid() = user_id);
create policy "projects update own" on public.projects for update to authenticated using (auth.uid() = user_id) with check (auth.uid() = user_id);
create policy "projects delete own" on public.projects for delete to authenticated using (auth.uid() = user_id);
grant select, insert, update, delete on public.projects to authenticated;

drop policy if exists "usage own rows" on public.usage_events;
drop policy if exists "usage select own" on public.usage_events;
create policy "usage select own" on public.usage_events for select to authenticated using (auth.uid() = user_id);
grant select on public.usage_events to authenticated;

create or replace function public.consume_capture_entitlement(p_trial_key text, p_network_key text default null)
returns jsonb language plpgsql security definer set search_path = public as $$
declare
  v_user uuid := auth.uid();
  p public.profiles%rowtype;
  existing_user uuid;
  recent_network_claims integer;
begin
  if v_user is null then raise exception 'authentication_required'; end if;
  select * into p from public.profiles where id = v_user for update;
  if not found then raise exception 'profile_not_found'; end if;

  if p.role = 'owner' then
    return jsonb_build_object('allowed', true, 'reason', 'owner', 'plan', 'owner');
  end if;
  if p.plan = 'pro' and coalesce(p.subscription_status,'') in ('active','trialing') then
    return jsonb_build_object('allowed', true, 'reason', 'pro', 'plan', 'pro');
  end if;
  if p.free_capture_used then
    return jsonb_build_object('allowed', false, 'reason', 'upgrade_required', 'plan', 'free');
  end if;
  if p_trial_key is null or length(p_trial_key) < 24 then
    return jsonb_build_object('allowed', false, 'reason', 'trial_key_required', 'plan', 'free');
  end if;

  select user_id into existing_user from public.free_trial_claims where trial_key = p_trial_key;
  if existing_user is not null and existing_user <> v_user then
    return jsonb_build_object('allowed', false, 'reason', 'free_device_used', 'plan', 'free');
  end if;

  if p_network_key is not null and length(p_network_key) >= 24 then
    select count(*) into recent_network_claims
      from public.free_trial_claims
      where network_key = p_network_key
        and claimed_at > now() - interval '24 hours'
        and user_id <> v_user;
    if recent_network_claims >= 3 then
      return jsonb_build_object('allowed', false, 'reason', 'free_network_rate_limited', 'plan', 'free');
    end if;
  end if;

  insert into public.free_trial_claims (trial_key, user_id, network_key)
  values (p_trial_key, v_user, p_network_key)
  on conflict (trial_key) do nothing;

  update public.profiles set free_capture_used = true, updated_at = now() where id = v_user;
  insert into public.usage_events (user_id, kind, metadata)
  values (v_user, 'free_capture_claimed', jsonb_build_object('trial_key', p_trial_key, 'network_key', p_network_key));
  return jsonb_build_object('allowed', true, 'reason', 'free', 'plan', 'free');
end;
$$;
revoke all on function public.consume_capture_entitlement(text,text) from public;
grant execute on function public.consume_capture_entitlement(text,text) to authenticated;

create or replace function public.restore_free_capture(p_trial_key text)
returns void language plpgsql security definer set search_path = public as $$
declare v_user uuid := auth.uid();
begin
  if v_user is null then raise exception 'authentication_required'; end if;
  delete from public.free_trial_claims where trial_key = p_trial_key and user_id = v_user;
  update public.profiles set free_capture_used = false, updated_at = now()
  where id = v_user and role = 'user' and plan = 'free';
end;
$$;
revoke all on function public.restore_free_capture(text) from public;
grant execute on function public.restore_free_capture(text) to authenticated;

drop policy if exists "webloom storage read own" on storage.objects;
drop policy if exists "webloom storage insert own" on storage.objects;
drop policy if exists "webloom storage update own" on storage.objects;
drop policy if exists "webloom storage delete own" on storage.objects;

create policy "webloom storage read own" on storage.objects for select to authenticated
using (bucket_id='webloom-projects' and (storage.foldername(name))[1]='projects' and (storage.foldername(name))[2]=auth.uid()::text);
create policy "webloom storage insert own" on storage.objects for insert to authenticated
with check (bucket_id='webloom-projects' and (storage.foldername(name))[1]='projects' and (storage.foldername(name))[2]=auth.uid()::text);
create policy "webloom storage update own" on storage.objects for update to authenticated
using (bucket_id='webloom-projects' and (storage.foldername(name))[1]='projects' and (storage.foldername(name))[2]=auth.uid()::text)
with check (bucket_id='webloom-projects' and (storage.foldername(name))[1]='projects' and (storage.foldername(name))[2]=auth.uid()::text);
create policy "webloom storage delete own" on storage.objects for delete to authenticated
using (bucket_id='webloom-projects' and (storage.foldername(name))[1]='projects' and (storage.foldername(name))[2]=auth.uid()::text);
