-- The shop every payment is paid to: one row, credited by every payment (bug B4 when it is read, then written back)
create table merchant (
  merchant_id   text primary key,
  balance_minor bigint not null
);

insert into merchant values ('shop', 0);

-- "payment completed" events saved with their payment, waiting to be published (the fix for bug B8)
create table outbox (
  id        bigserial primary key,
  event_key text not null,
  payload   text not null
);
