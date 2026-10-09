import { describe, it, expect } from 'vitest';

describe('Ambiente de testes do painel administrativo', () => {
  it('executa asserções do Vitest e jest-dom em ambiente jsdom', () => {
    const div = document.createElement('div');
    div.textContent = 'Rewit Admin';
    document.body.appendChild(div);

    expect(div).toBeInTheDocument();
    expect(div).toHaveTextContent('Rewit Admin');
  });
});
