ALTER TABLE pgtx.items ADD CONSTRAINT items_name_short CHECK (length(name) <= 40);
