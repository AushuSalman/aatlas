-- ============================================================================
--  V41 - what the pricing model learned for itself
--
--  Beside the tenant's hand-set overrides (settings), the model keeps the settings it
--  re-fitted from the tenant's own decisions and their measured outcomes (learned), with a
--  plain-words reason and the evidence behind each (learned_notes). The chain runs on
--  defaults <- learned <- settings, so a hand-set value always wins, and a side's learned
--  values count only while that side's "retune from your results" toggle is on.
--
--  A tenant that never saved may still have learned settings: the row is created by the
--  tuner with settings '{}'.
-- ============================================================================

ALTER TABLE pricing_model_settings
    ADD COLUMN learned       jsonb        NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN learned_notes jsonb        NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN learned_at    timestamptz;

ALTER TABLE pricing_model_settings
    ADD CONSTRAINT pricing_model_settings_learned_ck CHECK (jsonb_typeof(learned) = 'object'),
    ADD CONSTRAINT pricing_model_settings_learned_notes_ck CHECK (jsonb_typeof(learned_notes) = 'object');

COMMENT ON COLUMN pricing_model_settings.learned IS
    'Settings the model re-fitted from this tenant''s decisions and outcomes, keyed like settings; applied under the hand-set overrides while auto-tune is on.';
COMMENT ON COLUMN pricing_model_settings.learned_notes IS
    'Per learned key: {"reason": "...", "evidence": n, "from": default-or-previous, "to": value} for the settings screen.';
COMMENT ON COLUMN pricing_model_settings.learned_at IS
    'When the tuner last ran for this tenant; null until it has.';
