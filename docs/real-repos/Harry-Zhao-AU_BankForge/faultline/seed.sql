-- Test accounts for Faultline's transfers: ids ...0010 to ...0019, each with a large balance. Faultline loads this after
-- every reset; on conflict it puts the balance back, so each run starts from the same balances.
insert into accounts (id, bsb, account_number, account_name, balance) values
  ('00000000-0000-0000-0000-000000000010', '062-000', '10000010', 'Faultline test 10', 1000000000.0000),
  ('00000000-0000-0000-0000-000000000011', '062-000', '10000011', 'Faultline test 11', 1000000000.0000),
  ('00000000-0000-0000-0000-000000000012', '062-000', '10000012', 'Faultline test 12', 1000000000.0000),
  ('00000000-0000-0000-0000-000000000013', '062-000', '10000013', 'Faultline test 13', 1000000000.0000),
  ('00000000-0000-0000-0000-000000000014', '062-000', '10000014', 'Faultline test 14', 1000000000.0000),
  ('00000000-0000-0000-0000-000000000015', '062-000', '10000015', 'Faultline test 15', 1000000000.0000),
  ('00000000-0000-0000-0000-000000000016', '062-000', '10000016', 'Faultline test 16', 1000000000.0000),
  ('00000000-0000-0000-0000-000000000017', '062-000', '10000017', 'Faultline test 17', 1000000000.0000),
  ('00000000-0000-0000-0000-000000000018', '062-000', '10000018', 'Faultline test 18', 1000000000.0000),
  ('00000000-0000-0000-0000-000000000019', '062-000', '10000019', 'Faultline test 19', 1000000000.0000)
on conflict (id) do update set balance = excluded.balance;
