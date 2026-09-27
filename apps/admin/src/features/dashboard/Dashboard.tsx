import React from 'react';

export const Dashboard: React.FC = () => {
  return (
    <div>
      <div className="header">
        <h1 className="title">Painel Geral de Controle</h1>
        <p className="subtitle">Visão consolidada da infraestrutura e atividades da rede Rewit.</p>
      </div>

      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-label">Locais Catalogados</div>
          <div className="stat-value">0</div>
        </div>
        <div className="stat-card">
          <div className="stat-label">Avaliações Multi-Alvo</div>
          <div className="stat-value">0</div>
        </div>
        <div className="stat-card">
          <div className="stat-label">Check-ins Verificados</div>
          <div className="stat-value">0</div>
        </div>
        <div className="stat-card">
          <div className="stat-label">Fila de Moderação</div>
          <div className="stat-value">0</div>
        </div>
      </div>

      <div className="card">
        <h3>Status dos Subsistemas</h3>
        <p style={{ marginTop: '8px', color: 'var(--text-secondary)' }}>
          Banco Principal: <span className="badge badge-success">PostgreSQL 16 + PostGIS Ativo</span>
        </p>
        <p style={{ marginTop: '8px', color: 'var(--text-secondary)' }}>
          Cache & Sessões: <span className="badge badge-success">Redis 7 Operacional</span>
        </p>
        <p style={{ marginTop: '8px', color: 'var(--text-secondary)' }}>
          Object Storage: <span className="badge badge-success">MinIO S3 Pronto</span>
        </p>
      </div>
    </div>
  );
};
