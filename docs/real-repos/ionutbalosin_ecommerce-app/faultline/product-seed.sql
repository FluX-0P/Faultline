-- The product service's migration imports 1000 products with random IDs; give them IDs of a known form instead, so a
-- cart can name them: product 1 is 00000000-0000-0000-0000-000000000001. Runs after every reset, and again is the same.
UPDATE product SET id = ('00000000-0000-0000-0000-' || lpad(product_id::text, 12, '0'))::uuid;
