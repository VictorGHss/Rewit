import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/theme/rewit_semantic_colors.dart';
import 'package:rewit_mobile/app/theme/rewit_tokens.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/session_refresher.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/reports/domain/entities/report_entities.dart';
import 'package:rewit_mobile/shared/widgets/error_banner.dart';

/// Diálogo acessível para submissão de denúncia contra uma avaliação de outro usuário.
class ReportReviewDialog extends StatefulWidget {
  static const int maxDetailLength = 500;

  final String reviewId;
  final Future<ReviewReportReceipt> Function(
    ReportReason reason,
    String? detail,
  ) onConfirm;
  final VoidCallback? onReviewNotFound;
  final VoidCallback? onSessionExpired;
  final AuthNotifier? authNotifier;

  const ReportReviewDialog({
    super.key,
    required this.reviewId,
    required this.onConfirm,
    this.onReviewNotFound,
    this.onSessionExpired,
    this.authNotifier,
  });

  static Future<ReviewReportReceipt?> show(
    BuildContext context, {
    required String reviewId,
    required Future<ReviewReportReceipt> Function(
      ReportReason reason,
      String? detail,
    ) onConfirm,
    VoidCallback? onReviewNotFound,
    VoidCallback? onSessionExpired,
    AuthNotifier? authNotifier,
  }) {
    return showDialog<ReviewReportReceipt>(
      context: context,
      barrierDismissible: false,
      builder: (context) => ReportReviewDialog(
        reviewId: reviewId,
        onConfirm: onConfirm,
        onReviewNotFound: onReviewNotFound,
        onSessionExpired: onSessionExpired,
        authNotifier: authNotifier,
      ),
    );
  }

  @override
  State<ReportReviewDialog> createState() => _ReportReviewDialogState();
}

class _ReportReviewDialogState extends State<ReportReviewDialog> {
  ReportReason _selectedReason = ReportReason.inappropriateContent;
  final TextEditingController _detailController = TextEditingController();
  bool _isSubmitting = false;
  String? _errorMessage;
  int? _retryAfterSeconds;

  @override
  void dispose() {
    _detailController.dispose();
    super.dispose();
  }

  String _mapApiErrorMessage(ApiException error) {
    final code = error.errorCode?.toUpperCase();

    if (error.statusCode == 400 && code == 'SELF_REPORT_FORBIDDEN') {
      return 'Você não pode denunciar a sua própria avaliação.';
    }

    if (error.statusCode == 401) {
      return 'Sua sessão expirou. Faça login novamente para continuar.';
    }

    if (error.statusCode == 403 || code == 'FORBIDDEN') {
      return 'Não foi possível realizar a denúncia por restrição de acesso a esta avaliação.';
    }

    if (error.statusCode == 404 || code == 'REVIEW_NOT_FOUND') {
      return 'Esta avaliação não está mais disponível.';
    }

    if (error.statusCode == 429 || code == 'RATE_LIMIT_EXCEEDED') {
      if (error.retryAfterSeconds != null) {
        return 'Você atingiu o limite de denúncias em pouco tempo.';
      }
      return 'Você atingiu o limite de denúncias em pouco tempo. Aguarde antes de tentar novamente.';
    }

    if (error.statusCode == 400 ||
        error.statusCode == 422 ||
        code == 'VALIDATION_ERROR' ||
        code == 'MALFORMED_REQUEST') {
      final detailFieldError =
          error.getFieldError('detail') ?? error.getFieldError('reason');
      if (detailFieldError != null && detailFieldError.trim().isNotEmpty) {
        return detailFieldError.trim();
      }
      if (code == 'MALFORMED_REQUEST') {
        return 'Não foi possível validar os dados enviados. Revise o motivo e os detalhes informados.';
      }
      if (error.detail.trim().isNotEmpty) {
        return error.detail.trim();
      }
      return 'Dados inválidos para denúncia. Revise o formulário e tente novamente.';
    }

    if (error.detail.trim().isNotEmpty) {
      return error.detail.trim();
    }
    return 'Não foi possível processar a denúncia no momento.';
  }

  String _alreadyReportedMessage(ReportStatus status) {
    switch (status) {
      case ReportStatus.pending:
        return 'Você já denunciou esta avaliação anteriormente. Sua denúncia aguarda análise.';
      case ReportStatus.accepted:
        return 'Você já denunciou esta avaliação anteriormente e a denúncia já foi processada pela moderação.';
      case ReportStatus.rejected:
      case ReportStatus.unknown:
        return 'Você já registrou uma denúncia anterior para esta avaliação.';
    }
  }

  Future<void> _handleUnauthorizedSessionCheck(
    int? sessionCheckBefore, {
    required bool isAccountDisabled,
  }) async {
    final authNotifier = widget.authNotifier;
    if (authNotifier != null) {
      final check = sessionCheckBefore == null
          ? null
          : authNotifier.sessionCheckSince(sessionCheckBefore);
      if (check != null) {
        final outcome = await check;
        if (!mounted) return;
        if (isAccountDisabled) {
          // Aguarda o desempilhamento global do RewitApp sem pop local, sem banner local e sem reabilitar o formulário.
          widget.onSessionExpired?.call();
          return;
        }
        switch (outcome) {
          case SessionRefreshOutcome.rejected:
            // Aguarda o desempilhamento global do RewitApp sem pop local nem liberar o formulário.
            widget.onSessionExpired?.call();
            return;
          case SessionRefreshOutcome.refreshed:
            setState(() {
              _isSubmitting = false;
              _errorMessage =
                  'Sua sessão foi renovada. Tente enviar a denúncia novamente.';
              _retryAfterSeconds = null;
            });
            return;
          case SessionRefreshOutcome.unavailable:
            setState(() {
              _isSubmitting = false;
              _errorMessage =
                  'Não foi possível verificar sua sessão no momento. Verifique sua conexão e tente novamente.';
              _retryAfterSeconds = null;
            });
            return;
        }
      }

      if (isAccountDisabled || authNotifier.state is! Authenticated) {
        widget.onSessionExpired?.call();
        return;
      }
    }

    if (isAccountDisabled) {
      // ACCOUNT_DISABLED nunca executa Navigator.pop() local nem reabilita o formulário.
      widget.onSessionExpired?.call();
      return;
    }

    // Fallback para instâncias sem AuthNotifier conectado ao redirecionamento global.
    if (ModalRoute.of(context)?.canPop ?? false) {
      Navigator.of(context).pop();
    }
    widget.onSessionExpired?.call();
  }

  Future<void> _submit() async {
    if (_isSubmitting) return;

    final trimmedDetail = _detailController.text.trim();
    if (trimmedDetail.length > ReportReviewDialog.maxDetailLength) {
      setState(() {
        _errorMessage =
            'O detalhe da denúncia não pode exceder 500 caracteres.';
        _retryAfterSeconds = null;
      });
      return;
    }

    setState(() {
      _isSubmitting = true;
      _errorMessage = null;
      _retryAfterSeconds = null;
    });

    final sessionCheckBefore = widget.authNotifier?.sessionCheckSequence;

    try {
      final receipt = await widget.onConfirm(
        _selectedReason,
        trimmedDetail.isNotEmpty ? trimmedDetail : null,
      );

      if (!mounted) return;

      final semantic = RewitSemanticColors.of(context);
      final messenger = ScaffoldMessenger.of(context);
      Navigator.of(context).pop(receipt);

      if (receipt.isNewlyCreated) {
        messenger.showSnackBar(
          SnackBar(
            content: Text(
              'Denúncia enviada para análise. Obrigado por ajudar a manter a comunidade segura.',
              style: TextStyle(color: semantic.success.onContainer),
            ),
            backgroundColor: semantic.success.container,
          ),
        );
      } else {
        messenger.showSnackBar(
          SnackBar(
            content: Text(
              _alreadyReportedMessage(receipt.reportStatus),
              style: TextStyle(color: semantic.info.onContainer),
            ),
            backgroundColor: semantic.info.container,
          ),
        );
      }
    } on ApiException catch (e) {
      if (!mounted) return;
      final code = e.errorCode?.toUpperCase();
      if (e.statusCode == 401) {
        await _handleUnauthorizedSessionCheck(
          sessionCheckBefore,
          isAccountDisabled: code == 'ACCOUNT_DISABLED',
        );
        return;
      }

      final isNotFound =
          e.statusCode == 404 || code == 'REVIEW_NOT_FOUND';
      if (isNotFound) {
        if (ModalRoute.of(context)?.canPop ?? false) {
          Navigator.of(context).pop();
        }
        widget.onReviewNotFound?.call();
        return;
      }

      setState(() {
        _isSubmitting = false;
        _errorMessage = _mapApiErrorMessage(e);
        _retryAfterSeconds =
            (e.statusCode == 429 || code == 'RATE_LIMIT_EXCEEDED')
                ? e.retryAfterSeconds
                : null;
      });
    } on NetworkException {
      if (!mounted) return;
      setState(() {
        _isSubmitting = false;
        _errorMessage =
            'Sem conexão com o servidor. Verifique sua internet e tente novamente.';
        _retryAfterSeconds = null;
      });
    } on FormatException {
      if (!mounted) return;
      setState(() {
        _isSubmitting = false;
        _errorMessage =
            'Resposta inesperada do servidor ao registrar a denúncia.';
        _retryAfterSeconds = null;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _isSubmitting = false;
        _errorMessage =
            'Não foi possível concluir a denúncia no momento. Tente novamente.';
        _retryAfterSeconds = null;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;

    return PopScope(
      canPop: !_isSubmitting,
      child: AlertDialog(
        title: const Text('Denunciar avaliação'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Selecione o motivo que melhor descreve o problema encontrado nesta avaliação:',
                style: TextStyle(
                  fontSize: 13,
                  color: scheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: RewitSpacing.md),
              if (_errorMessage != null) ...[
                ErrorBanner(
                  key: const Key('report_review_error_banner'),
                  message: _errorMessage!,
                  retryAfterSeconds: _retryAfterSeconds,
                ),
                const SizedBox(height: RewitSpacing.md),
              ],
              RadioGroup<ReportReason>(
                groupValue: _selectedReason,
                onChanged: (value) {
                  if (!_isSubmitting && value != null) {
                    setState(() => _selectedReason = value);
                  }
                },
                child: Column(
                  children: ReportReason.values.map((reason) {
                    return RadioListTile<ReportReason>(
                      key: Key('report_reason_${reason.backendValue}'),
                      value: reason,
                      enabled: !_isSubmitting,
                      title: Text(
                        reason.label,
                        style: const TextStyle(fontSize: 13),
                      ),
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                    );
                  }).toList(),
                ),
              ),
              const SizedBox(height: RewitSpacing.md),
              TextField(
                key: const Key('report_review_detail_input'),
                controller: _detailController,
                enabled: !_isSubmitting,
                maxLength: ReportReviewDialog.maxDetailLength,
                maxLines: 3,
                minLines: 2,
                decoration: const InputDecoration(
                  labelText: 'Detalhes adicionais (opcional)',
                  hintText: 'Explique brevemente o motivo da denúncia...',
                  border: OutlineInputBorder(),
                  isDense: true,
                ),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            key: const Key('report_review_cancel_button'),
            onPressed: _isSubmitting ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            key: const Key('report_review_submit_button'),
            onPressed: _isSubmitting ? null : _submit,
            child: _isSubmitting
                ? SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: scheme.onSurfaceVariant,
                    ),
                  )
                : const Text('Enviar denúncia'),
          ),
        ],
      ),
    );
  }
}
