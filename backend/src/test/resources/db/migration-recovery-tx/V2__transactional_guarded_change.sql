CREATE TABLE public.recovery_tx_v2_artifact (
    id SERIAL PRIMARY KEY,
    status TEXT NOT NULL
);

INSERT INTO public.recovery_tx_v2_artifact (status) VALUES ('committed');

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.recovery_tx_gate
        WHERE id = 1 AND allow_v2 = TRUE
    ) THEN
        RAISE EXCEPTION 'Falha transitória simulada em migration transacional V2';
    END IF;
END $$;
