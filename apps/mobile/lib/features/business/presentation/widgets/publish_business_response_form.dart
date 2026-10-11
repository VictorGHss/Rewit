import 'package:flutter/material.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import '../../domain/entities/business_response_entities.dart';

/// Formulário para publicação de resposta oficial por empresa autorizada (C11).
class PublishBusinessResponseForm extends StatefulWidget {
  final BusinessResponseEligibility eligibility;
  final Future<void> Function({
    required String businessAccountId,
    required String placeId,
    required String content,
  }) onSubmit;
  final VoidCallback? onCancel;
  final TextEditingController? controller;

  const PublishBusinessResponseForm({
    super.key,
    required this.eligibility,
    required this.onSubmit,
    this.onCancel,
    this.controller,
  });

  @override
  State<PublishBusinessResponseForm> createState() =>
      _PublishBusinessResponseFormState();
}

class _PublishBusinessResponseFormState
    extends State<PublishBusinessResponseForm> {
  TextEditingController? _internalController;
  TextEditingController get _controller =>
      widget.controller ?? _internalController!;
  EligibleBusinessAccount? _selectedAccount;
  EligiblePlace? _selectedPlace;
  bool _isSubmitting = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    if (widget.controller == null) {
      _internalController = TextEditingController();
    }
    _initializeSelections();
  }

  @override
  void didUpdateWidget(PublishBusinessResponseForm oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.eligibility != widget.eligibility) {
      _initializeSelections();
    }
  }

  void _initializeSelections() {
    final validAccounts = widget.eligibility.accounts
        .where((a) => a.canPublish && a.places.isNotEmpty)
        .toList();

    if (validAccounts.isNotEmpty) {
      // Se a conta selecionada anteriormente ainda for válida, mantém
      if (_selectedAccount != null &&
          validAccounts.any((a) => a.businessAccountId == _selectedAccount!.businessAccountId)) {
        _selectedAccount = validAccounts.firstWhere(
            (a) => a.businessAccountId == _selectedAccount!.businessAccountId);
      } else {
        _selectedAccount = validAccounts.first;
      }

      if (_selectedAccount!.places.isNotEmpty) {
        if (_selectedPlace != null &&
            _selectedAccount!.places.any((p) => p.placeId == _selectedPlace!.placeId)) {
          _selectedPlace = _selectedAccount!.places
              .firstWhere((p) => p.placeId == _selectedPlace!.placeId);
        } else {
          _selectedPlace = _selectedAccount!.places.first;
        }
      } else {
        _selectedPlace = null;
      }
    } else {
      _selectedAccount = null;
      _selectedPlace = null;
    }
  }

  @override
  void dispose() {
    _internalController?.dispose();
    super.dispose();
  }

  Future<void> _handleSubmit() async {
    FocusScope.of(context).unfocus();

    final content = _controller.text.trim();
    if (content.isEmpty) {
      setState(() {
        _errorMessage = 'O conteúdo da resposta é obrigatório.';
      });
      return;
    }

    if (content.length > 2000) {
      setState(() {
        _errorMessage = 'A resposta não pode ultrapassar 2000 caracteres.';
      });
      return;
    }

    if (_selectedAccount == null || _selectedPlace == null) {
      setState(() {
        _errorMessage = 'Selecione a empresa e o local que esta resposta representa.';
      });
      return;
    }

    setState(() {
      _isSubmitting = true;
      _errorMessage = null;
    });

    try {
      await widget.onSubmit(
        businessAccountId: _selectedAccount!.businessAccountId,
        placeId: _selectedPlace!.placeId,
        content: content,
      );

      if (mounted) {
        _controller.clear();
        setState(() {
          _isSubmitting = false;
        });
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _isSubmitting = false;
          if (e.isRateLimited) {
            _errorMessage =
                'Limite de publicações atingido temporariamente (10 requisições/min). Tente novamente em breve.';
          } else {
            _errorMessage = e.detail;
          }
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _isSubmitting = false;
          _errorMessage = 'Falha ao publicar resposta oficial. Verifique sua conexão.';
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final validAccounts = widget.eligibility.accounts
        .where((a) => a.canPublish && a.places.isNotEmpty)
        .toList();

    if (validAccounts.isEmpty || _selectedAccount == null || _selectedPlace == null) {
      return const SizedBox.shrink();
    }

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 8),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: theme.colorScheme.surface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(
          color: theme.colorScheme.primary.withAlpha(100),
          width: 1.2,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Título do Formulário
          Row(
            children: [
              Icon(Icons.rate_review_outlined, size: 18, color: theme.colorScheme.primary),
              const SizedBox(width: 8),
              Text(
                'Responder como Empresa',
                style: theme.textTheme.titleSmall?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: theme.colorScheme.primary,
                ),
              ),
              const Spacer(),
              if (widget.onCancel != null)
                IconButton(
                  icon: const Icon(Icons.close, size: 18),
                  tooltip: 'Cancelar',
                  onPressed: _isSubmitting ? null : widget.onCancel,
                ),
            ],
          ),
          const SizedBox(height: 10),

          // Seletor de Conta Comercial (se houver mais de uma)
          if (validAccounts.length > 1) ...[
            Text(
              'Conta Comercial',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: theme.colorScheme.onSurface.withAlpha(180),
              ),
            ),
            const SizedBox(height: 4),
            DropdownButtonFormField<String>(
              key: const Key('business_account_dropdown'),
              initialValue: _selectedAccount!.businessAccountId,
              isExpanded: true,
              decoration: const InputDecoration(
                isDense: true,
                contentPadding: EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                border: OutlineInputBorder(),
              ),
              items: validAccounts.map((account) {
                return DropdownMenuItem<String>(
                  value: account.businessAccountId,
                  child: Text(
                    account.corporateName,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 13),
                  ),
                );
              }).toList(),
              onChanged: _isSubmitting
                  ? null
                  : (val) {
                      if (val != null) {
                        setState(() {
                          _selectedAccount = validAccounts.firstWhere(
                              (a) => a.businessAccountId == val);
                          _selectedPlace = _selectedAccount!.places.first;
                        });
                      }
                    },
            ),
            const SizedBox(height: 10),
          ] else ...[
            Text(
              'Empresa: ${_selectedAccount!.corporateName}',
              style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 6),
          ],

          // Seletor de Local Elegível (quando a empresa possui múltiplos locais elegíveis)
          if (_selectedAccount!.places.length > 1) ...[
            Text(
              'Local Representado',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w600,
                color: theme.colorScheme.onSurface.withAlpha(180),
              ),
            ),
            const SizedBox(height: 4),
            DropdownButtonFormField<String>(
              key: const Key('eligible_place_dropdown'),
              initialValue: _selectedPlace!.placeId,
              isExpanded: true,
              decoration: const InputDecoration(
                isDense: true,
                contentPadding: EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                border: OutlineInputBorder(),
              ),
              items: _selectedAccount!.places.map((place) {
                return DropdownMenuItem<String>(
                  value: place.placeId,
                  child: Text(
                    place.placeName,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 13),
                  ),
                );
              }).toList(),
              onChanged: _isSubmitting
                  ? null
                  : (val) {
                      if (val != null) {
                        setState(() {
                          _selectedPlace = _selectedAccount!.places.firstWhere(
                              (p) => p.placeId == val);
                        });
                      }
                    },
            ),
            const SizedBox(height: 10),
          ] else ...[
            Text(
              'Local: ${_selectedPlace!.placeName}',
              style: TextStyle(
                fontSize: 12,
                color: theme.colorScheme.onSurface.withAlpha(180),
              ),
            ),
            const SizedBox(height: 10),
          ],

          // Mensagem de Erro se houver
          if (_errorMessage != null) ...[
            Container(
              margin: const EdgeInsets.only(bottom: 8),
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: Colors.red.shade50,
                borderRadius: BorderRadius.circular(6),
                border: Border.all(color: Colors.red.shade200),
              ),
              child: Row(
                children: [
                  Icon(Icons.error_outline, size: 16, color: Colors.red.shade700),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      _errorMessage!,
                      key: const Key('publish_business_response_error'),
                      style: TextStyle(color: Colors.red.shade800, fontSize: 12),
                    ),
                  ),
                ],
              ),
            ),
          ],

          // Campo de Texto da Resposta Oficial
          TextField(
            key: const Key('business_response_content_field'),
            controller: _controller,
            enabled: !_isSubmitting,
            maxLength: 2000,
            maxLines: 4,
            minLines: 2,
            decoration: const InputDecoration(
              hintText: 'Escreva a resposta oficial da empresa (máximo 2000 caracteres)...',
              isDense: true,
              contentPadding: EdgeInsets.all(10),
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 10),

          // Botão de Envio
          Align(
            alignment: Alignment.centerRight,
            child: FilledButton.icon(
              key: const Key('submit_business_response_button'),
              onPressed: _isSubmitting ? null : _handleSubmit,
              icon: _isSubmitting
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    )
                  : const Icon(Icons.send, size: 16),
              label: Text(_isSubmitting ? 'Publicando...' : 'Publicar Resposta Oficial'),
            ),
          ),
        ],
      ),
    );
  }
}
