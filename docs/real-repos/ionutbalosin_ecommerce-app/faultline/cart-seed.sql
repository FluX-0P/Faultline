-- A cart of two products for each user a run checks out. Faultline fills in the run's seed, so a request whose userId is
-- 00000000-0000-0000-${seq:4}-${seed:12} finds its cart: requests 0 to 3999 of the run. Checkout reads the cart (and
-- the cart service empties it once the order is made), so it is loaded again after every reset.
INSERT INTO cart_item (user_id, product_id, discount, quantity, dat_ins, usr_ins, stat)
SELECT ('00000000-0000-0000-' || lpad(n::text, 4, '0') || '-${seed:12}')::uuid,
       ('00000000-0000-0000-0000-' || lpad(((n + p) % 1000 + 1)::text, 12, '0'))::uuid,
       10, 1 + n % 3, CURRENT_TIMESTAMP, 'faultline', 'A'
FROM generate_series(0, 3999) AS n, (VALUES (0), (500)) AS products(p)
ON CONFLICT (user_id, product_id) DO NOTHING;
