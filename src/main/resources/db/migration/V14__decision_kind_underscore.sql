-- ============================================================================
--  V14  Fix decision.kind's check constraint for the bulk kinds
--
--  V12's constraint spelled the bulk kinds with a hyphen ('bulk-sell',
--  'bulk-buy'), matching DecisionKind's wire() value - but DecisionEntity's
--  own JPA-mapped Kind enum (`sell, buy, bulk_sell, bulk_buy`) can only use
--  valid Java identifiers, so @Enumerated(EnumType.STRING) has always written
--  the underscored form. Nothing had ever recorded a bulk decision through
--  DecisionRecorder until sell/buy/bulk were retargeted onto it at the wave-2
--  merge, so the mismatch never surfaced - every attempt failed the check
--  constraint at commit. Realign the constraint to the underscored values
--  Hibernate actually writes, rather than changing the entity: DecisionKind's
--  public wire format (JSON, HTTP) is untouched either way.
-- ============================================================================

ALTER TABLE decision DROP CONSTRAINT decision_kind_ck;
ALTER TABLE decision ADD CONSTRAINT decision_kind_ck CHECK (kind IN ('sell', 'buy', 'bulk_sell', 'bulk_buy'));
