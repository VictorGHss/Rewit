import React, { useCallback, useEffect, useState } from 'react';
import { placeMediaApi } from '../../core/api/placeMedia';
import { ApiError } from '../../core/api/client';
import { LoadingSpinner } from '../common/LoadingSpinner';
import { ErrorMessage } from '../common/ErrorMessage';
import { Pagination } from '../common/Pagination';
import { formatDate } from '../common/Badge';
import type {
  AdminPlaceMediaReport,
  PlaceMediaDecisionOrigin,
  PlaceMediaReportStatus,
  ProblemDetail,
} from '../../core/types';

const STATUS_LABELS: Record<PlaceMediaReportStatus, string> = {
  PENDING: 'Pendente',
  ACCEPTED: 'Aceita',
  REJECTED: 'Rejeitada',
  AUTO_RESOLVED: 'Resolvida automaticamente',
};

const ORIGIN_LABELS: Record<PlaceMediaDecisionOrigin, string> = {
  MODERATOR: 'Decisão de moderador',
  SYSTEM: 'Resolução automática',
  LEGACY: 'Decisão histórica sem moderador verificável',
};

/** Selo de proveniência: decisão legada nunca é apresentada como decisão humana validada. */
export const DecisionOriginBadge: React.FC<{ report: AdminPlaceMediaReport }> = ({ report }) => {
  const origin = report.legacyDecision ? 'LEGACY' : report.decisionOrigin;
  if (!origin) {
    return <span className="badge badge-warning">Aguardando decisão</span>;
  }
  const badgeClass =
    origin === 'LEGACY' ? 'badge badge-danger' : origin === 'SYSTEM' ? 'badge' : 'badge badge-success';
  return (
    <span className={badgeClass} data-testid={`decision-origin-${report.id}`}>
      {ORIGIN_LABELS[origin]}
    </span>
  );
};

const deciderLabel = (report: AdminPlaceMediaReport): string => {
  if (report.legacyDecision || report.decisionOrigin === 'LEGACY') {
    return 'Moderador não identificável (registro histórico)';
  }
  if (report.decisionOrigin === 'SYSTEM') {
    return 'Sistema';
  }
  return report.decidedByUserId ?? '-';
};

export const PlaceMediaReportsQueue: React.FC = () => {
  const [reports, setReports] = useState<AdminPlaceMediaReport[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [isLast, setIsLast] = useState(true);
  const [status, setStatus] = useState<PlaceMediaReportStatus | ''>('');
  const [decisionOrigin, setDecisionOrigin] = useState<PlaceMediaDecisionOrigin | ''>('');
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [problemDetail, setProblemDetail] = useState<ProblemDetail | undefined>(undefined);

  const load = useCallback(async () => {
    setIsLoading(true);
    setErrorMessage(null);
    setProblemDetail(undefined);
    try {
      const result = await placeMediaApi.listReports({
        page,
        size: 20,
        status: status || undefined,
        decisionOrigin: decisionOrigin || undefined,
      });
      setReports(result.content);
      setTotalPages(result.totalPages);
      setTotalElements(result.totalElements);
      setIsLast(result.isLast);
    } catch (error) {
      setReports([]);
      if (error instanceof ApiError) {
        setErrorMessage(error.message);
        setProblemDetail(error.problem);
      } else {
        setErrorMessage('Não foi possível carregar as denúncias de fotografias.');
      }
    } finally {
      setIsLoading(false);
    }
  }, [page, status, decisionOrigin]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <section aria-labelledby="place-media-reports-title">
      <h1 id="place-media-reports-title">Denúncias de Fotos de Locais</h1>

      <div className="filters" style={{ display: 'flex', gap: '12px', marginBottom: '16px' }}>
        <label>
          Status
          <select
            aria-label="Filtrar por status"
            value={status}
            onChange={(e) => {
              setPage(0);
              setStatus(e.target.value as PlaceMediaReportStatus | '');
            }}
          >
            <option value="">Todos</option>
            {(Object.keys(STATUS_LABELS) as PlaceMediaReportStatus[]).map((value) => (
              <option key={value} value={value}>
                {STATUS_LABELS[value]}
              </option>
            ))}
          </select>
        </label>
        <label>
          Origem da decisão
          <select
            aria-label="Filtrar por origem da decisão"
            value={decisionOrigin}
            onChange={(e) => {
              setPage(0);
              setDecisionOrigin(e.target.value as PlaceMediaDecisionOrigin | '');
            }}
          >
            <option value="">Todas</option>
            {(Object.keys(ORIGIN_LABELS) as PlaceMediaDecisionOrigin[]).map((value) => (
              <option key={value} value={value}>
                {ORIGIN_LABELS[value]}
              </option>
            ))}
          </select>
        </label>
      </div>

      {isLoading && <LoadingSpinner />}
      {!isLoading && errorMessage && <ErrorMessage message={errorMessage} problem={problemDetail} onRetry={() => void load()} />}
      {!isLoading && !errorMessage && reports.length === 0 && <p>Nenhuma denúncia encontrada.</p>}
      {!isLoading && !errorMessage && reports.length > 0 && (
        <table className="data-table">
          <thead>
            <tr>
              <th>Fotografia</th>
              <th>Motivo</th>
              <th>Status</th>
              <th>Origem da decisão</th>
              <th>Decisor</th>
              <th>Decidida em</th>
            </tr>
          </thead>
          <tbody>
            {reports.map((report) => (
              <tr key={report.id}>
                <td>{report.placeMediaId}</td>
                <td>{report.reason}</td>
                <td>{STATUS_LABELS[report.status] ?? report.status}</td>
                <td>
                  <DecisionOriginBadge report={report} />
                </td>
                <td data-testid={`decider-${report.id}`}>{deciderLabel(report)}</td>
                <td>{formatDate(report.decidedAt ?? undefined)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {!isLoading && totalPages > 1 && (
        <Pagination
          pageNumber={page}
          totalPages={totalPages}
          totalElements={totalElements}
          isLast={isLast}
          onPageChange={setPage}
        />
      )}
    </section>
  );
};
