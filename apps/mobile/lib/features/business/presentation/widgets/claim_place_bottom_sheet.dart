import 'package:flutter/material.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import '../../domain/entities/business_entities.dart';
import '../../domain/repositories/business_repository.dart';

class ClaimPlaceBottomSheet extends StatefulWidget {
  final String placeId;
  final String placeName;
  final String city;
  final String state;
  final BusinessRepository repository;
  final VoidCallback? onClaimSubmitted;

  const ClaimPlaceBottomSheet({
    super.key,
    required this.placeId,
    required this.placeName,
    required this.city,
    required this.state,
    required this.repository,
    this.onClaimSubmitted,
  });

  static Future<bool?> show(
    BuildContext context, {
    required String placeId,
    required String placeName,
    required String city,
    required String state,
    required BusinessRepository repository,
  }) {
    return showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (ctx) => ClaimPlaceBottomSheet(
        placeId: placeId,
        placeName: placeName,
        city: city,
        state: state,
        repository: repository,
      ),
    );
  }

  @override
  State<ClaimPlaceBottomSheet> createState() => _ClaimPlaceBottomSheetState();
}

class _ClaimPlaceBottomSheetState extends State<ClaimPlaceBottomSheet> {
  final _formKey = GlobalKey<FormState>();
  final _evidenceController = TextEditingController();

  List<BusinessAccount> _accounts = [];
  String? _selectedAccountId;
  bool _isLoadingAccounts = true;
  bool _isSubmitting = false;
  String? _errorMessage;

  @override
  void initState() {
    super.initState();
    _loadAccounts();
    _evidenceController.addListener(() {
      setState(() {});
    });
  }

  @override
  void dispose() {
    _evidenceController.dispose();
    super.dispose();
  }

  Future<void> _loadAccounts() async {
    setState(() {
      _isLoadingAccounts = true;
      _errorMessage = null;
    });

    try {
      final accounts = await widget.repository.getMyBusinessAccounts();
      if (mounted) {
        setState(() {
          _accounts = accounts;
          _selectedAccountId = accounts.isNotEmpty ? accounts.first.id : null;
          _isLoadingAccounts = false;
        });
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          _errorMessage = e.detail;
          _isLoadingAccounts = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _errorMessage = 'Não foi possível carregar suas contas comerciais.';
          _isLoadingAccounts = false;
        });
      }
    }
  }

  Future<void> _submitClaim() async {
    if (_selectedAccountId == null) return;
    if (_formKey.currentState?.validate() != true) return;

    setState(() {
      _isSubmitting = true;
      _errorMessage = null;
    });

    try {
      await widget.repository.requestPlaceClaim(
        businessAccountId: _selectedAccountId!,
        placeId: widget.placeId,
        evidenceDescription: _evidenceController.text.trim(),
      );

      if (mounted) {
        widget.onClaimSubmitted?.call();
        Navigator.of(context).pop(true);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Solicitação enviada com sucesso! Ela foi registrada como pendente e será avaliada pela moderação.',
            ),
            backgroundColor: Colors.green,
            duration: Duration(seconds: 4),
          ),
        );
      }
    } on ApiException catch (e) {
      if (mounted) {
        setState(() {
          if (e.errorCode == 'PLACE_ALREADY_CLAIMED') {
            _errorMessage = 'Este local já foi reivindicado e está vinculado a outra empresa.';
          } else if (e.errorCode == 'PLACE_CLAIM_ALREADY_PENDING') {
            _errorMessage = 'Já existe uma solicitação de reivindicação em análise para este local.';
          } else if (e.errorCode == 'BUSINESS_ACCOUNT_REJECTED') {
            _errorMessage = 'Esta conta comercial está rejeitada e não pode reivindicar locais.';
          } else {
            _errorMessage = e.detail;
          }
          _isSubmitting = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _errorMessage = 'Falha ao enviar solicitação de reivindicação. Tente novamente.';
          _isSubmitting = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final textLength = _evidenceController.text.trim().length;
    final isEvidenceValid = textLength >= 20 && textLength <= 1000;

    return Padding(
      padding: EdgeInsets.only(
        left: 20,
        right: 20,
        top: 24,
        bottom: MediaQuery.of(context).viewInsets.bottom + 24,
      ),
      child: Form(
        key: _formKey,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Reivindicar Local',
                          style: theme.textTheme.titleLarge?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          '${widget.placeName} (${widget.city}/${widget.state})',
                          style: TextStyle(
                            color: theme.colorScheme.onSurface.withAlpha(160),
                            fontSize: 13,
                          ),
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close),
                    onPressed: _isSubmitting ? null : () => Navigator.of(context).pop(),
                  ),
                ],
              ),
              const SizedBox(height: 16),

              if (_errorMessage != null) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: Colors.red.withAlpha(25),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: Colors.red.withAlpha(100)),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.error_outline, color: Colors.red, size: 20),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          _errorMessage!,
                          style: const TextStyle(color: Colors.red, fontSize: 13),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),
              ],

              if (_isLoadingAccounts) ...[
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 24.0),
                  child: Center(child: CircularProgressIndicator()),
                ),
              ] else if (_accounts.isEmpty) ...[
                Container(
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.surfaceContainerHighest.withAlpha(50),
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: theme.colorScheme.outlineVariant.withAlpha(80)),
                  ),
                  child: Column(
                    children: [
                      Icon(Icons.storefront_outlined, size: 40, color: theme.colorScheme.primary),
                      const SizedBox(height: 8),
                      const Text(
                        'Você ainda não possui contas comerciais.',
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Para reivindicar este local, primeiro cadastre os dados da sua empresa.',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: theme.colorScheme.onSurface.withAlpha(160),
                          fontSize: 13,
                        ),
                      ),
                      const SizedBox(height: 12),
                      OutlinedButton.icon(
                        key: const Key('go_to_business_accounts_button'),
                        onPressed: () {
                          Navigator.of(context).pop();
                          Navigator.of(context).pushNamed(AppRouter.businessAccounts);
                        },
                        icon: const Icon(Icons.add_business),
                        label: const Text('Cadastrar Empresa'),
                      ),
                    ],
                  ),
                ),
              ] else ...[
                // Seletor de Conta Comercial
                Text(
                  'Selecione a Empresa Solicitante:',
                  style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 8),
                DropdownButtonFormField<String>(
                  key: const Key('claim_account_dropdown'),
                  initialValue: _selectedAccountId,
                  decoration: const InputDecoration(
                    border: OutlineInputBorder(),
                    contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 12),
                  ),
                  items: _accounts.map((account) {
                    return DropdownMenuItem<String>(
                      value: account.id,
                      child: Text(
                        '${account.corporateName} (${account.taxId})',
                        overflow: TextOverflow.ellipsis,
                      ),
                    );
                  }).toList(),
                  onChanged: _isSubmitting
                      ? null
                      : (value) {
                          setState(() {
                            _selectedAccountId = value;
                          });
                        },
                ),
                const SizedBox(height: 16),

                // Campo de Evidências
                Text(
                  'Evidências de Representação (20 a 1000 caracteres):',
                  style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 4),
                Text(
                  'Descreva alvarás, comprovantes de endereço, inscrição municipal ou dados societários que comprovem que você representa este estabelecimento.',
                  style: TextStyle(
                    color: theme.colorScheme.onSurface.withAlpha(150),
                    fontSize: 12,
                  ),
                ),
                const SizedBox(height: 8),
                TextFormField(
                  key: const Key('evidence_description_input'),
                  controller: _evidenceController,
                  maxLines: 4,
                  maxLength: 1000,
                  decoration: InputDecoration(
                    hintText: 'Explique detalhadamente seu vínculo com o estabelecimento...',
                    border: const OutlineInputBorder(),
                    counterText: '$textLength / 1000 (mínimo 20)',
                    counterStyle: TextStyle(
                      color: textLength > 0 && !isEvidenceValid ? Colors.red : null,
                    ),
                  ),
                  validator: (value) {
                    final trimmed = value?.trim() ?? '';
                    if (trimmed.length < 20) {
                      return 'A descrição deve ter pelo menos 20 caracteres.';
                    }
                    if (trimmed.length > 1000) {
                      return 'A descrição não pode exceder 1000 caracteres.';
                    }
                    return null;
                  },
                ),
                const SizedBox(height: 16),

                ElevatedButton(
                  key: const Key('submit_claim_button'),
                  onPressed: (_isSubmitting || !isEvidenceValid || _selectedAccountId == null)
                      ? null
                      : _submitClaim,
                  style: ElevatedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 14),
                  ),
                  child: _isSubmitting
                      ? const SizedBox(
                          height: 20,
                          width: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Text('Enviar Solicitação de Reivindicação'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
