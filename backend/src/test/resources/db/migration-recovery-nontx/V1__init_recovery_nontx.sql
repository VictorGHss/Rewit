CREATE TABLE public.recovery_nontx_gate (
    id INTEGER PRIMARY KEY,
    allow_v2 BOOLEAN NOT NULL
);

INSERT INTO public.recovery_nontx_gate (id, allow_v2) VALUES (1, FALSE);

CREATE TABLE public.recovery_nontx_sessions (
    id SERIAL PRIMARY KEY,
    ref_id UUID
);

CREATE OR REPLACE FUNCTION public.rewit_assert_runtime_privileges()
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    -- Stub exclusivo da suíte de teste de recuperação não transacional (fora de V1–V46).
    RETURN;
END;
$$;
