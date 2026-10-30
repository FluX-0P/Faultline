-- Used by the B1 fix (demo.fixes.dedupe=true): one row per payment already processed
create table processed_events (
  payment_id text primary key
);
