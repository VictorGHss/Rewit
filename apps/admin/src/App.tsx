import React, { useState } from 'react';
import { Dashboard } from './features/dashboard/Dashboard';
import { ModerationQueue } from './features/moderation/ModerationQueue';

export const App: React.FC = () => {
  const [activeTab, setActiveTab] = useState<'dashboard' | 'moderation'>('dashboard');

  return (
    <div className="admin-layout">
      <aside className="sidebar">
        <div className="brand">
          <span>📍</span>
          <span>Rewit Admin</span>
        </div>
        <ul className="nav-links">
          <li
            className={`nav-item ${activeTab === 'dashboard' ? 'active' : ''}`}
            onClick={() => setActiveTab('dashboard')}
          >
            Dashboard
          </li>
          <li
            className={`nav-item ${activeTab === 'moderation' ? 'active' : ''}`}
            onClick={() => setActiveTab('moderation')}
          >
            Fila de Moderação
          </li>
          <li className="nav-item" style={{ opacity: 0.5, cursor: 'not-allowed' }}>
            Locais (Fase 2)
          </li>
          <li className="nav-item" style={{ opacity: 0.5, cursor: 'not-allowed' }}>
            Catálogo Global (Fase 2)
          </li>
          <li className="nav-item" style={{ opacity: 0.5, cursor: 'not-allowed' }}>
            Empresas & Contas (Fase 3)
          </li>
        </ul>
      </aside>

      <main className="content">
        {activeTab === 'dashboard' ? <Dashboard /> : <ModerationQueue />}
      </main>
    </div>
  );
};
export default App;
