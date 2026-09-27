import React from 'react';

export const ModerationQueue: React.FC = () => {
  return (
    <div>
      <div className="header">
        <h1 className="title">Fila de Moderação e Auditoria</h1>
        <p className="subtitle">Análise de denúncias de avaliações, reivindicações de locais e combate a abusos.</p>
      </div>

      <div className="card">
        <p style={{ color: 'var(--text-secondary)' }}>
          Nenhum item pendente de moderação no momento. A base local está limpa e íntegra.
        </p>
      </div>
    </div>
  );
};
