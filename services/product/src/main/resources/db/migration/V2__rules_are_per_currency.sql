-- A priced rule is a rule *in a currency*, and the uniqueness must say so.
--
-- Core 2.2.0 made pricing currency-aware: `JdbcProductDecisions` filters limits by the
-- transaction's currency, and reads "rules for this operation, none in this currency" as
-- CURRENCY_MISMATCH rather than as free. Both rule tables have carried `currency NOT NULL`
-- since the baseline. The uniqueness never learned it.
--
-- So the constraints contradicted the code that reads them:
--
--   one_limit_per_tier_channel_type  UNIQUE (product_version_id, kyc_tier, channel, limit_type)
--   one_fee_rule_per_operation       UNIQUE (tenant_id, product_version_id, operation)
--
-- A product version could hold a PER_TXN limit for NGN *or* one for USD, never both — while the
-- evaluator refuses any operation with no limit rule in the transaction's currency
-- (OPERATION_NOT_PERMITTED, deny by default). A multi-currency product was therefore not merely
-- unpriced in its second currency: every transaction in it was refused, and no authoring call
-- could fix that, because storing the second rule violated the constraint. The fee side was the
-- same shape one step further on — CURRENCY_MISMATCH was detectable and unfixable, since the
-- version could hold only one TRANSFER rule.
--
-- Widening the key is the whole change. Nothing narrows: every row legal before is legal now, and
-- a single-currency product is unaffected. `DailyLimitAndFeeConfigTest`'s two cross-currency cases
-- are what fail without it — named in the 2.2.0 entry as the proof of the fee reading, and unable
-- to seed their own fixture until the constraint admits a second currency.

--
-- Name: limit_rules one_limit_per_tier_channel_type; Type: CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.limit_rules
    DROP CONSTRAINT one_limit_per_tier_channel_type;

-- `tenant_id` joins the key here, which it was missing while the fee side already carried it.
-- `product_version_id` is tenant-unique (product_versions_tenant_id_id_key) so this adds no
-- restriction — it makes the two rule tables state the same scope the same way.
ALTER TABLE ONLY product.limit_rules
    ADD CONSTRAINT one_limit_per_tier_channel_type
        UNIQUE (tenant_id, product_version_id, kyc_tier, channel, limit_type, currency);

--
-- Name: fee_rules one_fee_rule_per_operation; Type: CONSTRAINT; Schema: product; Owner: -
--

ALTER TABLE ONLY product.fee_rules
    DROP CONSTRAINT one_fee_rule_per_operation;

ALTER TABLE ONLY product.fee_rules
    ADD CONSTRAINT one_fee_rule_per_operation
        UNIQUE (tenant_id, product_version_id, operation, currency);
