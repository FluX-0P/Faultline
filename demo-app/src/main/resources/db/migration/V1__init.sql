create table accounts (
  account_id    text primary key,
  balance_minor bigint not null
);

create table payments (
  id           bigserial primary key,
  payment_id   text not null,          -- deliberately NOT unique, so duplicates can happen (bug B1)
  account_id   text not null references accounts(account_id),
  amount_minor bigint not null,
  created_at   timestamptz not null default now()
);

insert into accounts
select 'acct-' || g, 1000000 from generate_series(1, 100) g;
