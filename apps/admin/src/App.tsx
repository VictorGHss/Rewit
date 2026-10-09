import React, { useState, useEffect, useCallback } from 'react';
import { authApi } from './core/api/auth';
import { setAuthFailureCallback } from './core/api/client';
import { tokenStorage } from './core/storage/tokenStorage';
import { LoginScreen } from './features/auth/LoginScreen';
import { Dashboard } from './features/dashboard/Dashboard';
import { ReviewReportsQueue } from './features/reviews/ReviewReportsQueue';
import { DiscussionReportsQueue } from './features/discussions/DiscussionReportsQueue';
import { PlaceClaimsQueue } from './features/claims/PlaceClaimsQueue';
import type { AuthUser } from './core/types';

export type NavigationTab = 'dashboard' | 'reviews' | 'discussions' | 'claims';

export const App: React.FC = () => {
  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(() => authApi.isAuthenticated());
  const [activeTab, setActiveTab] = useState<NavigationTab>('dashboard');
  const [currentUser, setCurrentUser] = useState<AuthUser | null>(() => tokenStorage.getUser());
  const [isLoggingOut, setIsLoggingOut] = useState(false);

  useEffect(() => {
    // Registra callback do cliente HTTP para sessão expirada / falha de refresh
    setAuthFailureCallback(() => {
      setIsAuthenticated(false);
      setCurrentUser(null);
    });
  }, []);

  const handleLoginSuccess = useCallback(() => {
    setCurrentUser(tokenStorage.getUser());
    setIsAuthenticated(true);
    setActiveTab('dashboard');
  }, []);

  const handleLogout = useCallback(async () => {
    setIsLoggingOut(true);
    try {
      await authApi.logout();
    } finally {
      setIsAuthenticated(false);
      setCurrentUser(null);
      setIsLoggingOut(false);
    }
  }, []);

  if (!isAuthenticated) {
    return <LoginScreen onLoginSuccess={handleLoginSuccess} />;
  }

  return (
    <div className="admin-layout">
      <aside className="sidebar">
        <div>
          <div className="brand">
            <span>🛡️</span>
            <span>Rewit Admin</span>
          </div>

          <nav aria-label="Navegação do Painel">
            <ul className="nav-links">
              <li>
                <button
                  type="button"
                  className={`nav-item ${activeTab === 'dashboard' ? 'active' : ''}`}
                  onClick={() => setActiveTab('dashboard')}
                >
                  <span>📊</span>
                  <span>Dashboard</span>
                </button>
              </li>
              <li>
                <button
                  type="button"
                  className={`nav-item ${activeTab === 'reviews' ? 'active' : ''}`}
                  onClick={() => setActiveTab('reviews')}
                >
                  <span>📝</span>
                  <span>Denúncias de Avaliações</span>
                </button>
              </li>
              <li>
                <button
                  type="button"
                  className={`nav-item ${activeTab === 'discussions' ? 'active' : ''}`}
                  onClick={() => setActiveTab('discussions')}
                >
                  <span>💬</span>
                  <span>Denúncias de Discussões</span>
                </button>
              </li>
              <li>
                <button
                  type="button"
                  className={`nav-item ${activeTab === 'claims' ? 'active' : ''}`}
                  onClick={() => setActiveTab('claims')}
                >
                  <span>🏢</span>
                  <span>Reivindicações de Locais</span>
                </button>
              </li>
            </ul>
          </nav>
        </div>

        {/* Informações do Administrador & Logout */}
        <div className="user-card">
          <div style={{ display: 'flex', flexDirection: 'column' }}>
            <span style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--text-primary)' }}>
              {currentUser?.displayName || 'Administrador'}
            </span>
            <span style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', wordBreak: 'break-all' }}>
              {currentUser?.email || 'admin@rewit.com'}
            </span>
          </div>
          <button
            type="button"
            className="btn btn-secondary"
            style={{ fontSize: '0.8rem', padding: '6px 10px', width: '100%' }}
            onClick={handleLogout}
            disabled={isLoggingOut}
          >
            {isLoggingOut ? 'Saindo...' : '🚪 Sair do Painel'}
          </button>
        </div>
      </aside>

      <main className="content" role="main">
        {activeTab === 'dashboard' && (
          <Dashboard
            onNavigateToReviews={() => setActiveTab('reviews')}
            onNavigateToDiscussions={() => setActiveTab('discussions')}
          />
        )}
        {activeTab === 'reviews' && <ReviewReportsQueue />}
        {activeTab === 'discussions' && <DiscussionReportsQueue />}
        {activeTab === 'claims' && <PlaceClaimsQueue />}
      </main>
    </div>
  );
};

export default App;
