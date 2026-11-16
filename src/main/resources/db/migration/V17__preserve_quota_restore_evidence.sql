-- A restored closed ledger row must preserve its settlement and original timestamp.
-- Only a newly granted single-credit row needs OPEN -> PROVEN normalization.
CREATE
OR REPLACE FUNCTION normalize_budget_settlement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF
NEW.closed AND NEW.settlement='OPEN' THEN
  NEW.settlement:='PROVEN';NEW.settled_at
:=coalesce(NEW.settled_at,clock_timestamp());
END IF;
RETURN NEW;
END $$;
ALTER TABLE api_budgets
    ADD CONSTRAINT api_budgets_settlement_consistent CHECK (
        (closed AND settlement IN ('PROVEN', 'ASSUMED') AND settled_at IS NOT NULL)
            OR (NOT closed AND settlement = 'OPEN' AND settled_at IS NULL)
        );
