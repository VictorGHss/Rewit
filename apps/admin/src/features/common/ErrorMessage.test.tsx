import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ErrorMessage, normalizeFieldErrors } from './ErrorMessage';
import type { ProblemDetail } from '../../core/types';

describe('ErrorMessage', () => {
  describe('normalizeFieldErrors', () => {
    it('retorna array vazio quando fieldErrors for nulo ou indefinido', () => {
      expect(normalizeFieldErrors(undefined)).toEqual([]);
      expect(normalizeFieldErrors(null)).toEqual([]);
    });

    it('normaliza corretamente fieldErrors fornecido como objeto (formato do backend)', () => {
      const fieldErrorsObj = {
        reasonCode: 'O reasonCode não pode exceder 64 caracteres',
        justification: 'A justificativa deve ter entre 15 e 1000 caracteres',
      };

      const result = normalizeFieldErrors(fieldErrorsObj);

      expect(result).toEqual([
        { field: 'reasonCode', message: 'O reasonCode não pode exceder 64 caracteres' },
        { field: 'justification', message: 'A justificativa deve ter entre 15 e 1000 caracteres' },
      ]);
    });

    it('normaliza e preserva fieldErrors fornecido como array de objetos (formato legado)', () => {
      const fieldErrorsArr = [
        { field: 'email', message: 'E-mail em formato inválido' },
        { field: 'password', message: 'Senha muito curta' },
      ];

      const result = normalizeFieldErrors(fieldErrorsArr);

      expect(result).toEqual(fieldErrorsArr);
    });

    it('filtra entradas inválidas ou não-string sem disparar exceção', () => {
      const mixedObj = {
        validField: 'Mensagem válida',
        invalidField: null as unknown as string,
      };

      const result = normalizeFieldErrors(mixedObj);

      expect(result).toEqual([
        { field: 'validField', message: 'Mensagem válida' },
      ]);
    });
  });

  describe('renderização do componente', () => {
    it('renderiza título e mensagem padrão sem exibir lista de campos quando fieldErrors for ausente', () => {
      const problem: ProblemDetail = {
        title: 'Requisição inválida',
        detail: 'Os dados enviados contêm inconsistências.',
        status: 400,
      };

      render(<ErrorMessage problem={problem} />);

      expect(screen.getByRole('alert')).toBeInTheDocument();
      expect(screen.getByText('Requisição inválida')).toBeInTheDocument();
      expect(screen.getByText('Os dados enviados contêm inconsistências.')).toBeInTheDocument();
      expect(screen.queryByRole('list')).not.toBeInTheDocument();
    });

    it('renderiza fieldErrors no formato de objeto retornado pelo backend sem lançar exceção', () => {
      const problem: ProblemDetail = {
        title: 'Erro de validação',
        code: 'VALIDATION_ERROR',
        status: 400,
        fieldErrors: {
          reasonCode: 'O reasonCode não pode exceder 64 caracteres',
          justification: 'A justificativa deve ter entre 15 e 1000 caracteres',
        },
      };

      render(<ErrorMessage problem={problem} />);

      expect(screen.getByRole('alert')).toBeInTheDocument();
      expect(screen.getByText('Erro de validação')).toBeInTheDocument();
      expect(screen.getByText(/reasonCode:/i)).toBeInTheDocument();
      expect(screen.getByText(/O reasonCode não pode exceder 64 caracteres/i)).toBeInTheDocument();
      expect(screen.getByText(/justification:/i)).toBeInTheDocument();
      expect(screen.getByText(/A justificativa deve ter entre 15 e 1000 caracteres/i)).toBeInTheDocument();
    });

    it('renderiza fieldErrors no formato de array de objetos', () => {
      const problem: ProblemDetail = {
        title: 'Falha de validação',
        status: 400,
        fieldErrors: [
          { field: 'corporateName', message: 'Razão social obrigatória' },
        ],
      };

      render(<ErrorMessage problem={problem} />);

      expect(screen.getByText(/corporateName:/i)).toBeInTheDocument();
      expect(screen.getByText(/Razão social obrigatória/i)).toBeInTheDocument();
    });

    it('permite acionar o callback onRetry', async () => {
      const user = userEvent.setup();
      const onRetry = vi.fn();

      render(<ErrorMessage message="Falha de conexão" onRetry={onRetry} />);

      const retryBtn = screen.getByRole('button', { name: /tentar novamente/i });
      await user.click(retryBtn);

      expect(onRetry).toHaveBeenCalledTimes(1);
    });
  });
});
