-- ==============================================================================
-- REWIT DATABASE MIGRATION - V6__external_reference_unbounded_id.sql
-- ==============================================================================
-- Objetivo: Alterar a coluna external_id de place_external_references para TEXT.
--
-- Justificativa Técnica:
-- A documentação oficial da Google Maps Platform (Places API New) estabelece que
-- Place IDs não possuem comprimento máximo definido. Para evitar truncamento ou
-- erros de overflow em identificadores longos, a coluna é alterada de VARCHAR(255)
-- para TEXT no PostgreSQL.
-- A constraint única composta uq_place_ext_ref (provider, external_id) e os
-- índices associados permanecem plenamente compatíveis com o tipo TEXT.
-- ==============================================================================

ALTER TABLE place_external_references
    ALTER COLUMN external_id TYPE TEXT;
