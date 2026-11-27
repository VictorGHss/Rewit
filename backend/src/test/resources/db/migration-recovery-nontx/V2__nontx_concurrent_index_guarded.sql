DROP INDEX CONCURRENTLY IF EXISTS idx_recovery_nontx_sessions_ref;

CREATE INDEX CONCURRENTLY idx_recovery_nontx_sessions_ref
    ON public.recovery_nontx_sessions (ref_id)
    WHERE ref_id IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.recovery_nontx_gate
        WHERE id = 1 AND allow_v2 = TRUE
    ) THEN
        RAISE EXCEPTION 'Falha simulada após CREATE INDEX CONCURRENTLY em migration não transacional V2';
    END IF;
END $$;
