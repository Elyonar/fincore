-- The currency registry cannot be one country.
--
-- V8 seeded NGN alone and said further currencies would arrive "one migration per country pack".
-- That reads as caution and behaves as a fault, because it confuses two different questions:
--
--   * which currencies exist, and how many decimal places each has — a fact about ISO 4217;
--   * which of them an institution deals in — a fact about that institution.
--
-- The second is tenant configuration and lives in Core's `platform.currencies`, editable from
-- Settings. The first is this table, and it is referenced by a foreign key from `accounts`,
-- `entries` and `holds`. With one row in it, the second question had no answers to choose from: a
-- Nigerian bank opening an ordinary domiciliary account in dollars — which is not an exotic act,
-- it is a product almost every bank on the continent sells — got a foreign key violation surfaced
-- as a 500. The institution could name USD in its own settings, price it, and offer it on a form,
-- and the ledger had never heard of it.
--
-- So the registry is seeded properly. `minor_unit_exponent` is the part that must be right rather
-- than complete: it is immutable once a currency is in use (see the trigger on this table) because
-- every stored amount is an integer of minor units and means nothing without it. A missing currency
-- is a refusal somebody reads; a wrong exponent is money off by a factor of ten or a hundred, and
-- nothing reports it.
--
-- The exceptions to two decimal places are a short, stable, well-known list, and every one of them
-- is seeded here so that none can be added later by somebody assuming the default:
--
--   0 places  BIF CLP DJF GNF ISK JPY KMF KRW PYG RWF UGX VND VUV XAF XOF XPF
--   3 places  BHD IQD JOD KWD LYD OMR TND
--   4 places  CLF
--
-- Everything else on this list takes 2. The list itself covers the markets this platform targets,
-- their neighbours, and the currencies a domiciliary book is actually held in.
--
-- ON CONFLICT DO NOTHING, as V8: NGN is already here, and the tests insert their own.
INSERT INTO currencies (code, minor_unit_exponent, display_name) VALUES
    -- Zero decimal places.
    ('BIF', 0, 'Burundian Franc'),
    ('CLP', 0, 'Chilean Peso'),
    ('DJF', 0, 'Djiboutian Franc'),
    ('GNF', 0, 'Guinean Franc'),
    ('ISK', 0, 'Icelandic Krona'),
    ('JPY', 0, 'Japanese Yen'),
    ('KMF', 0, 'Comorian Franc'),
    ('KRW', 0, 'South Korean Won'),
    ('PYG', 0, 'Paraguayan Guarani'),
    ('RWF', 0, 'Rwandan Franc'),
    ('UGX', 0, 'Ugandan Shilling'),
    ('VND', 0, 'Vietnamese Dong'),
    ('VUV', 0, 'Vanuatu Vatu'),
    ('XAF', 0, 'Central African CFA Franc'),
    ('XOF', 0, 'West African CFA Franc'),
    ('XPF', 0, 'CFP Franc'),

    -- Three decimal places.
    ('BHD', 3, 'Bahraini Dinar'),
    ('IQD', 3, 'Iraqi Dinar'),
    ('JOD', 3, 'Jordanian Dinar'),
    ('KWD', 3, 'Kuwaiti Dinar'),
    ('LYD', 3, 'Libyan Dinar'),
    ('OMR', 3, 'Omani Rial'),
    ('TND', 3, 'Tunisian Dinar'),

    -- Four decimal places.
    ('CLF', 4, 'Unidad de Fomento'),

    -- Two decimal places: the markets served, and the currencies held against them.
    ('AED', 2, 'UAE Dirham'),
    ('AOA', 2, 'Angolan Kwanza'),
    ('AUD', 2, 'Australian Dollar'),
    ('BRL', 2, 'Brazilian Real'),
    ('BWP', 2, 'Botswana Pula'),
    ('CAD', 2, 'Canadian Dollar'),
    ('CDF', 2, 'Congolese Franc'),
    ('CHF', 2, 'Swiss Franc'),
    ('CNY', 2, 'Chinese Yuan'),
    ('CVE', 2, 'Cabo Verdean Escudo'),
    ('DKK', 2, 'Danish Krone'),
    ('EGP', 2, 'Egyptian Pound'),
    ('ERN', 2, 'Eritrean Nakfa'),
    ('ETB', 2, 'Ethiopian Birr'),
    ('EUR', 2, 'Euro'),
    ('GBP', 2, 'Pound Sterling'),
    ('GHS', 2, 'Ghanaian Cedi'),
    ('GMD', 2, 'Gambian Dalasi'),
    ('HKD', 2, 'Hong Kong Dollar'),
    ('IDR', 2, 'Indonesian Rupiah'),
    ('ILS', 2, 'Israeli New Shekel'),
    ('INR', 2, 'Indian Rupee'),
    ('KES', 2, 'Kenyan Shilling'),
    ('LRD', 2, 'Liberian Dollar'),
    ('LSL', 2, 'Lesotho Loti'),
    ('MAD', 2, 'Moroccan Dirham'),
    ('MUR', 2, 'Mauritian Rupee'),
    ('MWK', 2, 'Malawian Kwacha'),
    ('MZN', 2, 'Mozambican Metical'),
    ('NAD', 2, 'Namibian Dollar'),
    ('NOK', 2, 'Norwegian Krone'),
    ('NZD', 2, 'New Zealand Dollar'),
    ('PLN', 2, 'Polish Zloty'),
    ('QAR', 2, 'Qatari Riyal'),
    ('SAR', 2, 'Saudi Riyal'),
    ('SCR', 2, 'Seychellois Rupee'),
    ('SEK', 2, 'Swedish Krona'),
    ('SGD', 2, 'Singapore Dollar'),
    ('SLE', 2, 'Sierra Leonean Leone'),
    ('SZL', 2, 'Swazi Lilangeni'),
    ('TRY', 2, 'Turkish Lira'),
    ('TZS', 2, 'Tanzanian Shilling'),
    ('USD', 2, 'US Dollar'),
    ('ZAR', 2, 'South African Rand'),
    ('ZMW', 2, 'Zambian Kwacha')
ON CONFLICT (code) DO NOTHING;
