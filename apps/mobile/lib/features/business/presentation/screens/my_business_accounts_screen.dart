import 'package:flutter/material.dart';
import 'package:rewit_mobile/shared/widgets/error_view.dart';
import 'package:rewit_mobile/shared/widgets/loading_indicator.dart';
import '../../domain/entities/business_entities.dart';
import '../../domain/repositories/business_repository.dart';
import '../state/business_accounts_notifier.dart';

class MyBusinessAccountsScreen extends StatefulWidget {
  final BusinessRepository? repository;
  final BusinessAccountsNotifier? notifier;

  const MyBusinessAccountsScreen({
    super.key,
    this.repository,
    this.notifier,
  });

  @override
  State<MyBusinessAccountsScreen> createState() => _MyBusinessAccountsScreenState();
}

class _MyBusinessAccountsScreenState extends State<MyBusinessAccountsScreen> {
  late final BusinessAccountsNotifier _notifier;
  bool _ownsNotifier = false;

  @override
  void initState() {
    super.initState();
    if (widget.notifier != null) {
      _notifier = widget.notifier!;
    } else if (widget.repository != null) {
      _notifier = BusinessAccountsNotifier(repository: widget.repository!);
      _ownsNotifier = true;
    } else {
      throw StateError('BusinessRepository ou BusinessAccountsNotifier deve ser fornecido.');
    }

    _notifier.loadMyAccounts();
  }

  @override
  void dispose() {
    if (_ownsNotifier) {
      _notifier.dispose();
    }
    super.dispose();
  }

  void _showCreateAccountDialog() {
    final formKey = GlobalKey<FormState>();
    final corporateNameController = TextEditingController();
    final taxIdController = TextEditingController();

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (sheetContext) {
        return Padding(
          padding: EdgeInsets.only(
            left: 20,
            right: 20,
            top: 24,
            bottom: MediaQuery.of(sheetContext).viewInsets.bottom + 24,
          ),
          child: Form(
            key: formKey,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        'Cadastrar Empresa',
                        style: Theme.of(sheetContext).textTheme.titleLarge?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                      ),
                      IconButton(
                        icon: const Icon(Icons.close),
                        onPressed: () => Navigator.of(sheetContext).pop(),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Cadastre a conta comercial com a qual você poderá reivindicar locais físicos.',
                    style: TextStyle(
                      color: Theme.of(sheetContext).colorScheme.onSurface.withAlpha(160),
                      fontSize: 13,
                    ),
                  ),
                  const SizedBox(height: 20),
                  TextFormField(
                    key: const Key('corporate_name_input'),
                    controller: corporateNameController,
                    decoration: const InputDecoration(
                      labelText: 'Razão Social *',
                      hintText: 'Ex: Padaria Central Ltda',
                      border: OutlineInputBorder(),
                    ),
                    validator: (value) {
                      final trimmed = value?.trim() ?? '';
                      if (trimmed.length < 2 || trimmed.length > 255) {
                        return 'A razão social deve ter entre 2 e 255 caracteres.';
                      }
                      return null;
                    },
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    key: const Key('tax_id_input'),
                    controller: taxIdController,
                    decoration: const InputDecoration(
                      labelText: 'Documento Fiscal (CNPJ / Tax ID) *',
                      hintText: 'Ex: 12.345.678/0001-90',
                      border: OutlineInputBorder(),
                    ),
                    validator: (value) {
                      final clean = (value ?? '').replaceAll(RegExp(r'[^a-zA-Z0-9]'), '');
                      if (clean.length < 8 || clean.length > 32) {
                        return 'O documento fiscal deve ter entre 8 e 32 dígitos/caracteres.';
                      }
                      return null;
                    },
                  ),
                  const SizedBox(height: 24),
                  ListenableBuilder(
                    listenable: _notifier,
                    builder: (btnContext, _) {
                      return ElevatedButton(
                        key: const Key('submit_create_account_button'),
                        onPressed: _notifier.isCreatingAccount
                            ? null
                            : () async {
                                if (formKey.currentState?.validate() != true) return;

                                final success = await _notifier.createAccount(
                                  corporateName: corporateNameController.text,
                                  taxId: taxIdController.text,
                                );

                                if (!mounted) return;
                                if (success) {
                                  if (sheetContext.mounted) {
                                    Navigator.of(sheetContext).pop();
                                  }
                                  ScaffoldMessenger.of(context).showSnackBar(
                                    SnackBar(
                                      content: Text(_notifier.successMessage ?? 'Conta criada com sucesso!'),
                                      backgroundColor: Colors.green,
                                    ),
                                  );
                                } else if (_notifier.errorMessage != null) {
                                  ScaffoldMessenger.of(context).showSnackBar(
                                    SnackBar(
                                      content: Text(_notifier.errorMessage!),
                                      backgroundColor: Colors.red,
                                    ),
                                  );
                                }
                              },
                        style: ElevatedButton.styleFrom(
                          padding: const EdgeInsets.symmetric(vertical: 14),
                        ),
                        child: _notifier.isCreatingAccount
                            ? const SizedBox(
                                height: 20,
                                width: 20,
                                child: CircularProgressIndicator(strokeWidth: 2),
                              )
                            : const Text('Cadastrar Conta Comercial'),
                      );
                    },
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildStatusChip(String status) {
    Color color;
    String label;

    switch (status) {
      case 'APPROVED':
        color = Colors.green;
        label = 'Verificada';
        break;
      case 'REJECTED':
        color = Colors.red;
        label = 'Rejeitada';
        break;
      case 'PENDING':
      default:
        color = Colors.orange;
        label = 'Em Análise';
        break;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withAlpha(30),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withAlpha(120)),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 12,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }

  Widget _buildClaimStatusBadge(String status) {
    Color color;
    String label;

    switch (status) {
      case 'APPROVED':
        color = Colors.green;
        label = 'Aprovada';
        break;
      case 'REJECTED':
        color = Colors.red;
        label = 'Rejeitada';
        break;
      case 'PENDING':
      default:
        color = Colors.orange;
        label = 'Pendente';
        break;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withAlpha(25),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: color,
          fontSize: 11,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }

  Widget _buildAccountCard(BuildContext context, BusinessAccount account) {
    final theme = Theme.of(context);
    final claims = _notifier.claimsByAccount[account.id] ?? [];

    return Card(
      elevation: 0,
      margin: const EdgeInsets.only(bottom: 16),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(80)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        account.corporateName,
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Documento: ${account.taxId}',
                        style: TextStyle(
                          color: theme.colorScheme.onSurface.withAlpha(160),
                          fontSize: 13,
                        ),
                      ),
                    ],
                  ),
                ),
                _buildStatusChip(account.verificationStatus),
              ],
            ),
            const SizedBox(height: 12),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: theme.colorScheme.surfaceContainerHighest.withAlpha(70),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.workspace_premium_outlined, size: 16, color: theme.colorScheme.primary),
                  const SizedBox(width: 6),
                  Text(
                    'Plano: ${account.planTier}',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: theme.colorScheme.onSurface.withAlpha(200),
                    ),
                  ),
                ],
              ),
            ),
            const Divider(height: 24),
            Text(
              'Solicitações de Reivindicação (${claims.length})',
              style: theme.textTheme.titleSmall?.copyWith(
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 8),
            if (claims.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8.0),
                child: Text(
                  'Nenhum local reivindicado por esta conta ainda.',
                  style: TextStyle(
                    color: theme.colorScheme.onSurface.withAlpha(140),
                    fontSize: 13,
                  ),
                ),
              )
            else
              ListView.separated(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                itemCount: claims.length,
                separatorBuilder: (_, __) => const Divider(height: 16),
                itemBuilder: (context, index) {
                  final claim = claims[index];
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Expanded(
                            child: Text(
                              claim.placeName,
                              style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14),
                            ),
                          ),
                          _buildClaimStatusBadge(claim.status),
                        ],
                      ),
                      const SizedBox(height: 2),
                      Text(
                        '${claim.city} / ${claim.state}',
                        style: TextStyle(
                          color: theme.colorScheme.onSurface.withAlpha(140),
                          fontSize: 12,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Evidências: ${claim.evidenceDescription}',
                        style: TextStyle(
                          color: theme.colorScheme.onSurface.withAlpha(170),
                          fontSize: 12,
                        ),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                      if (claim.decisionReason != null && claim.decisionReason!.isNotEmpty) ...[
                        const SizedBox(height: 4),
                        Container(
                          padding: const EdgeInsets.all(8),
                          decoration: BoxDecoration(
                            color: theme.colorScheme.surfaceContainerHighest.withAlpha(50),
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            'Decisão: ${claim.decisionReason}',
                            style: TextStyle(
                              fontSize: 12,
                              color: claim.isApproved ? Colors.green.shade800 : Colors.red.shade800,
                            ),
                          ),
                        ),
                      ],
                    ],
                  );
                },
              ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Minhas Empresas'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Atualizar',
            onPressed: () => _notifier.loadMyAccounts(),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        key: const Key('add_business_account_fab'),
        onPressed: _showCreateAccountDialog,
        icon: const Icon(Icons.add_business),
        label: const Text('Nova Empresa'),
      ),
      body: ListenableBuilder(
        listenable: _notifier,
        builder: (context, _) {
          if (_notifier.isLoading) {
            return const LoadingIndicator(message: 'Carregando contas comerciais...');
          }

          if (_notifier.errorMessage != null && _notifier.accounts.isEmpty) {
            return ErrorView(
              title: 'Erro ao carregar empresas',
              message: _notifier.errorMessage!,
              onRetry: () => _notifier.loadMyAccounts(),
            );
          }

          if (_notifier.accounts.isEmpty) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.all(32.0),
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(
                      Icons.storefront_outlined,
                      size: 64,
                      color: Theme.of(context).colorScheme.primary.withAlpha(120),
                    ),
                    const SizedBox(height: 16),
                    Text(
                      'Nenhuma empresa cadastrada',
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Cadastre sua empresa com razão social e documento fiscal para solicitar a gestão de estabelecimentos físicos na rede Rewit.',
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onSurface.withAlpha(160),
                        fontSize: 14,
                      ),
                    ),
                    const SizedBox(height: 24),
                    ElevatedButton.icon(
                      key: const Key('empty_add_business_button'),
                      onPressed: _showCreateAccountDialog,
                      icon: const Icon(Icons.add_business),
                      label: const Text('Cadastrar Empresa'),
                    ),
                  ],
                ),
              ),
            );
          }

          return RefreshIndicator(
            onRefresh: () => _notifier.loadMyAccounts(),
            child: ListView.builder(
              padding: const EdgeInsets.all(16),
              itemCount: _notifier.accounts.length,
              itemBuilder: (context, index) {
                return _buildAccountCard(context, _notifier.accounts[index]);
              },
            ),
          );
        },
      ),
    );
  }
}
