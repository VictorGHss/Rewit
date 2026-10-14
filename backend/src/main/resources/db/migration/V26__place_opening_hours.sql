-- ==============================================================================
-- REWIT DATABASE MIGRATION - V26
-- ==============================================================================
-- F2.1: Gestão de horários de funcionamento de estabelecimentos reivindicados
-- Tabelas: place_opening_hours_schedules, place_opening_hours_intervals
-- ==============================================================================
-- O modelo semanal recorrente opera com:
-- 1. Tabela de configuração mestre (place_opening_hours_schedules):
--    - Registra a presença de horários configurados para o local;
--    - Associa a conta comercial que realizou a configuração;
--    - Registra auditoria do usuário administrador que realizou a atualização;
--    - Acompanha o ciclo de vida do local (CASCADE com places).
--
-- 2. Tabela de intervalos semanais (place_opening_hours_intervals):
--    - Mapeia cada intervalo recorrente [open_time, close_time) de um dia da semana (1..7);
--    - Suporta múltiplos intervalos no mesmo dia (ex: almoço e jantar);
--    - Dias sem intervalos correspondem a dias fechados quando o schedule existe;
--    - Garante validações relacionais estritas de dia (1 a 7) e ordem cronológica (open < close).

-- 1. TABELA MESTRE DE CONFIGURAÇÃO DE HORÁRIOS
CREATE TABLE place_opening_hours_schedules (
    place_id UUID PRIMARY KEY REFERENCES places(id) ON DELETE CASCADE,
    business_account_id UUID NOT NULL REFERENCES business_accounts(id) ON DELETE RESTRICT,
    updated_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- 2. TABELA DE INTERVALOS DE FUNCIONAMENTO SEMANAL
CREATE TABLE place_opening_hours_intervals (
    id UUID PRIMARY KEY,
    place_id UUID NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    day_of_week SMALLINT NOT NULL,
    open_time TIME NOT NULL,
    close_time TIME NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_place_opening_hours_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT chk_place_opening_hours_time CHECK (open_time < close_time),
    CONSTRAINT fk_place_opening_hours_schedule FOREIGN KEY (place_id)
        REFERENCES place_opening_hours_schedules(place_id) ON DELETE CASCADE
);

-- Índices para otimização de leitura e integridade
CREATE INDEX idx_place_opening_hours_intervals_place_day
    ON place_opening_hours_intervals (place_id, day_of_week, open_time);

CREATE INDEX idx_place_opening_hours_schedules_business
    ON place_opening_hours_schedules (business_account_id);
