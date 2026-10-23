import 'dart:async';
import 'package:flutter/material.dart';
import '../../../../shared/widgets/error_view.dart';
import '../../../../shared/widgets/loading_indicator.dart';
import '../../../search/domain/entities/search_entities.dart';
import '../../../search/domain/repositories/search_repository.dart';
import '../../domain/entities/place_commercial_entities.dart';
import '../../domain/repositories/place_commercial_repository.dart';
import '../state/place_offers_management_notifier.dart';

/// Argumentos para inicialização da tela de gestão de ofertas comerciais de um estabelecimento.
class PlaceOffersManagementArgs {
  final String placeId;
  final String businessAccountId;
  final String placeName;
  final bool isApproved;

  const PlaceOffersManagementArgs({
    required this.placeId,
    required this.businessAccountId,
    required this.placeName,
    this.isApproved = true,
  });
}

/// Tela de gestão das ofertas comerciais oficiais de produtos de um estabelecimento (F2.3).
class PlaceOffersManagementScreen extends StatefulWidget {
  final PlaceOffersManagementArgs args;
  final PlaceCommercialRepository? commercialRepository;
  final SearchRepository? searchRepository;
  final PlaceOffersManagementNotifier? notifier;

  const PlaceOffersManagementScreen({
    super.key,
    required this.args,
    this.commercialRepository,
    this.searchRepository,
    this.notifier,
  });

  @override
  State<PlaceOffersManagementScreen> createState() => _PlaceOffersManagementScreenState();
}

class _PlaceOffersManagementScreenState extends State<PlaceOffersManagementScreen> {
  late final PlaceOffersManagementNotifier _notifier;
  final ScrollController _scrollController = ScrollController();
  bool _ownsNotifier = false;

  @override
  void initState() {
    super.initState();
    if (widget.notifier != null) {
      _notifier = widget.notifier!;
    } else if (widget.commercialRepository != null) {
      _notifier = PlaceOffersManagementNotifier(
        repository: widget.commercialRepository!,
        searchRepository: widget.searchRepository,
      );
      _ownsNotifier = true;
    } else {
      throw StateError('PlaceCommercialRepository ou PlaceOffersManagementNotifier deve ser fornecido.');
    }

    _scrollController.addListener(_onScroll);

    if (widget.args.isApproved) {
      _notifier.loadOffers(widget.args.placeId);
    }
  }

  void _onScroll() {
    if (_scrollController.hasClients &&
        _scrollController.position.pixels >= _scrollController.position.maxScrollExtent - 200) {
      _notifier.loadMoreOffers(widget.args.placeId);
    }
  }

  @override
  void dispose() {
    _scrollController.dispose();
    if (_ownsNotifier) {
      _notifier.dispose();
    }
    super.dispose();
  }

  Future<void> _openOfferForm({PlaceProductOffer? offer}) async {
    _notifier.clearSearch();
    final result = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (modalContext) => _OfferFormSheet(
        placeId: widget.args.placeId,
        businessAccountId: widget.args.businessAccountId,
        notifier: _notifier,
        existingOffer: offer,
      ),
    );
    _notifier.clearSearch();

    if (!mounted || result != true) return;

    if (_scrollController.hasClients) {
      _scrollController.jumpTo(0);
    }

    if (_notifier.successMessage != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_notifier.successMessage!),
          backgroundColor: Colors.green,
        ),
      );
      _notifier.clearMessages();
    }
  }

  Future<void> _confirmDeleteOffer(PlaceProductOffer offer) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Retirar da vitrine?'),
        content: Text(
          'O produto "${offer.product.name}" deixará de aparecer na vitrine oficial do seu estabelecimento.',
        ),
        actions: [
          TextButton(
            key: const Key('cancel_delete_offer_button'),
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('Cancelar'),
          ),
          ElevatedButton(
            key: const Key('confirm_delete_offer_button'),
            style: ElevatedButton.styleFrom(
              backgroundColor: Theme.of(dialogContext).colorScheme.error,
              foregroundColor: Theme.of(dialogContext).colorScheme.onError,
            ),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Retirar'),
          ),
        ],
      ),
    );

    if (!mounted || confirmed != true) return;

    final scaffoldMessenger = ScaffoldMessenger.of(context);
    final success = await _notifier.deleteOffer(
      placeId: widget.args.placeId,
      productId: offer.product.id,
      businessAccountId: widget.args.businessAccountId,
    );

    if (!mounted) return;

    if (success) {
      if (_scrollController.hasClients) {
        _scrollController.jumpTo(0);
      }
      scaffoldMessenger.showSnackBar(
        SnackBar(
          content: Text(_notifier.successMessage ?? 'Oferta retirada da vitrine.'),
          backgroundColor: Colors.green,
        ),
      );
      _notifier.clearMessages();
    } else if (_notifier.errorMessage != null) {
      scaffoldMessenger.showSnackBar(
        SnackBar(
          content: Text(_notifier.errorMessage!),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
      _notifier.clearMessages();
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    if (!widget.args.isApproved) {
      return Scaffold(
        appBar: AppBar(
          title: const Text('Ofertas comerciais'),
        ),
        body: _buildUnauthorizedView(context, theme),
      );
    }

    return AnimatedBuilder(
      animation: _notifier,
      builder: (context, _) {
        return Scaffold(
          appBar: AppBar(
            title: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Ofertas comerciais'),
                Text(
                  widget.args.placeName,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurface.withAlpha(160),
                  ),
                ),
              ],
            ),
          ),
          floatingActionButton: FloatingActionButton.extended(
            key: const Key('add_offer_button'),
            onPressed: _notifier.isSaving ? null : () => _openOfferForm(),
            icon: const Icon(Icons.add),
            label: const Text('Nova oferta'),
          ),
          body: _buildBody(context, theme),
        );
      },
    );
  }

  Widget _buildUnauthorizedView(BuildContext context, ThemeData theme) {
    return Center(
      key: const Key('offers_unauthorized_view'),
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.lock_outline, size: 64, color: theme.colorScheme.error),
            const SizedBox(height: 16),
            Text(
              'Acesso Restrito',
              style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(
              'Apenas estabelecimentos com reivindicação aprovada podem gerenciar ofertas comerciais.',
              textAlign: TextAlign.center,
              style: TextStyle(color: theme.colorScheme.onSurface.withAlpha(160)),
            ),
            const SizedBox(height: 24),
            ElevatedButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Voltar'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildBody(BuildContext context, ThemeData theme) {
    if (_notifier.isLoading && _notifier.offers.isEmpty) {
      return const LoadingIndicator(
        key: Key('offers_loading_indicator'),
        message: 'Carregando ofertas comerciais...',
      );
    }

    if (_notifier.offers.isEmpty && _notifier.errorMessage != null) {
      return ErrorView(
        key: const Key('offers_error_view'),
        title: 'Não foi possível carregar as ofertas',
        message: _notifier.errorMessage!,
        onRetry: () => _notifier.loadOffers(widget.args.placeId, refresh: true),
      );
    }

    if (_notifier.isEmpty) {
      return _buildEmptyView(theme);
    }

    return RefreshIndicator(
      onRefresh: () => _notifier.loadOffers(widget.args.placeId, refresh: true),
      child: ListView.separated(
        controller: _scrollController,
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 88),
        itemCount: _notifier.offers.length + (_notifier.isLoadingMore ? 1 : 0),
        separatorBuilder: (_, __) => const SizedBox(height: 12),
        itemBuilder: (context, index) {
          if (index == _notifier.offers.length) {
            return const Padding(
              padding: EdgeInsets.symmetric(vertical: 16.0),
              child: Center(child: CircularProgressIndicator()),
            );
          }

          final offer = _notifier.offers[index];
          return _buildOfferCard(offer, theme);
        },
      ),
    );
  }

  Widget _buildEmptyView(ThemeData theme) {
    return Center(
      key: const Key('offers_empty_view'),
      child: Padding(
        padding: const EdgeInsets.all(32.0),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.local_offer_outlined, size: 72, color: theme.colorScheme.primary.withAlpha(180)),
            const SizedBox(height: 16),
            Text(
              'Nenhuma oferta na vitrine',
              style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(
              'Publique ofertas dos produtos disponíveis no seu estabelecimento para que a comunidade possa encontrá-los facilmente.',
              textAlign: TextAlign.center,
              style: TextStyle(color: theme.colorScheme.onSurface.withAlpha(160)),
            ),
            const SizedBox(height: 24),
            ElevatedButton.icon(
              key: const Key('add_first_offer_button'),
              onPressed: () => _openOfferForm(),
              icon: const Icon(Icons.add),
              label: const Text('Adicionar primeira oferta'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildOfferCard(PlaceProductOffer offer, ThemeData theme) {
    final isDeletingThis = _notifier.isDeleting && _notifier.deletingProductId == offer.product.id;

    return Card(
      key: Key('offer_card_${offer.product.id}'),
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: theme.colorScheme.outlineVariant.withAlpha(90)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        offer.product.name,
                        key: Key('offer_product_name_${offer.product.id}'),
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      if (offer.product.brand != null || offer.product.category.isNotEmpty) ...[
                        const SizedBox(height: 4),
                        Text(
                          [
                            if (offer.product.brand != null && offer.product.brand!.isNotEmpty)
                              offer.product.brand!,
                            if (offer.product.category.isNotEmpty)
                              offer.product.category,
                          ].join(' • '),
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurface.withAlpha(160),
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                _buildAvailabilityChip(offer.isAvailable, theme, offer.product.id),
              ],
            ),
            const Divider(height: 24),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Preço praticado',
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: theme.colorScheme.onSurface.withAlpha(140),
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      offer.formattedPrice,
                      key: Key('offer_price_${offer.product.id}'),
                      style: theme.textTheme.titleSmall?.copyWith(
                        fontWeight: FontWeight.bold,
                        color: offer.price != null && offer.price! > 0
                            ? theme.colorScheme.primary
                            : theme.colorScheme.onSurface,
                      ),
                    ),
                  ],
                ),
                Row(
                  children: [
                    IconButton(
                      key: Key('edit_offer_button_${offer.product.id}'),
                      tooltip: 'Editar oferta',
                      icon: const Icon(Icons.edit_outlined),
                      onPressed: isDeletingThis ? null : () => _openOfferForm(offer: offer),
                    ),
                    isDeletingThis
                        ? const SizedBox(
                            width: 24,
                            height: 24,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : IconButton(
                            key: Key('delete_offer_button_${offer.product.id}'),
                            tooltip: 'Retirar da vitrine',
                            icon: Icon(Icons.delete_outline, color: theme.colorScheme.error),
                            onPressed: () => _confirmDeleteOffer(offer),
                          ),
                  ],
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAvailabilityChip(bool isAvailable, ThemeData theme, String productId) {
    final color = isAvailable ? Colors.green : Colors.grey;
    final label = isAvailable ? 'Disponível' : 'Sem estoque';

    return Container(
      key: Key('offer_availability_$productId'),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withAlpha(25),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withAlpha(100)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 8,
            height: 8,
            decoration: BoxDecoration(color: color, shape: BoxShape.circle),
          ),
          const SizedBox(width: 6),
          Text(
            label,
            style: theme.textTheme.labelSmall?.copyWith(
              color: color,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}

enum _PriceMode { custom, free, undisclosed }

/// Sheet modal para adição ou edição de uma oferta de produto na vitrine.
class _OfferFormSheet extends StatefulWidget {
  final String placeId;
  final String businessAccountId;
  final PlaceOffersManagementNotifier notifier;
  final PlaceProductOffer? existingOffer;

  const _OfferFormSheet({
    required this.placeId,
    required this.businessAccountId,
    required this.notifier,
    this.existingOffer,
  });

  @override
  State<_OfferFormSheet> createState() => _OfferFormSheetState();
}

class _OfferFormSheetState extends State<_OfferFormSheet> {
  final TextEditingController _searchController = TextEditingController();
  final TextEditingController _priceController = TextEditingController();
  Timer? _debounceTimer;

  OfferedProductSummary? _selectedProduct;
  String _availability = 'AVAILABLE';
  _PriceMode _priceMode = _PriceMode.custom;
  String? _priceError;
  bool _isPreExistingInVitrine = false;

  @override
  void initState() {
    super.initState();
    if (widget.existingOffer != null) {
      _selectedProduct = widget.existingOffer!.product;
      _availability = widget.existingOffer!.availability;
      _setupPriceMode(widget.existingOffer!.price);
    }
  }

  void _setupPriceMode(double? price) {
    if (price == null) {
      _priceMode = _PriceMode.undisclosed;
      _priceController.clear();
    } else if (price == 0.0) {
      _priceMode = _PriceMode.free;
      _priceController.clear();
    } else {
      _priceMode = _PriceMode.custom;
      _priceController.text = price.toStringAsFixed(2).replaceAll('.', ',');
    }
  }

  @override
  void dispose() {
    _debounceTimer?.cancel();
    _searchController.dispose();
    _priceController.dispose();
    super.dispose();
  }

  void _onSearchChanged(String query) {
    _debounceTimer?.cancel();
    if (query.trim().length < 2) {
      widget.notifier.clearSearch();
      return;
    }
    _debounceTimer = Timer(const Duration(milliseconds: 300), () {
      widget.notifier.searchProducts(query);
    });
  }

  void _selectSearchResult(SearchResultItem item) {
    final existing = widget.notifier.findExistingOffer(item.id);
    setState(() {
      _selectedProduct = OfferedProductSummary(
        id: item.id,
        name: item.name,
        category: item.category ?? 'Produto',
      );
      if (existing != null) {
        _isPreExistingInVitrine = true;
        _availability = existing.availability;
        _setupPriceMode(existing.price);
      } else {
        _isPreExistingInVitrine = false;
        _availability = 'AVAILABLE';
        _priceMode = _PriceMode.custom;
        _priceController.clear();
      }
    });
    widget.notifier.clearSearch();
  }

  Future<void> _handleSave() async {
    if (_selectedProduct == null) return;

    double? finalPrice;
    if (_priceMode == _PriceMode.free) {
      finalPrice = 0.0;
    } else if (_priceMode == _PriceMode.undisclosed) {
      finalPrice = null;
    } else {
      final text = _priceController.text.trim();
      if (text.isEmpty) {
        setState(() => _priceError = 'Informe o valor do produto.');
        return;
      }
      final normalized = text.replaceAll(',', '.');
      final numericRegex = RegExp(r'^\d+(\.\d+)?$');
      if (!numericRegex.hasMatch(normalized)) {
        setState(() => _priceError = 'Informe um valor numérico válido.');
        return;
      }
      final parts = normalized.split('.');
      if (parts.length == 2 && parts[1].length > 2) {
        setState(() => _priceError = 'O preço não pode ter mais de duas casas decimais.');
        return;
      }
      final parsed = double.tryParse(normalized);
      if (parsed == null || parsed < 0) {
        setState(() => _priceError = 'Informe um valor válido.');
        return;
      }
      if (parsed > 9999999999.99) {
        setState(() => _priceError = 'O preço excede o limite permitido.');
        return;
      }
      finalPrice = parsed;
    }

    setState(() => _priceError = null);

    final success = await widget.notifier.upsertOffer(
      placeId: widget.placeId,
      productId: _selectedProduct!.id,
      businessAccountId: widget.businessAccountId,
      availability: _availability,
      price: finalPrice,
    );

    if (!mounted) return;

    if (success) {
      Navigator.of(context).pop(true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isEditing = widget.existingOffer != null;
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;

    return AnimatedBuilder(
      animation: widget.notifier,
      builder: (context, _) {
        return Padding(
          padding: EdgeInsets.only(
            left: 20,
            right: 20,
            top: 20,
            bottom: bottomInset + 20,
          ),
          child: SingleChildScrollView(
            key: const Key('offers_form_sheet'),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      isEditing ? 'Editar oferta' : 'Publicar oferta',
                      style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
                    ),
                    IconButton(
                      icon: const Icon(Icons.close),
                      onPressed: () => Navigator.of(context).pop(false),
                    ),
                  ],
                ),
                const SizedBox(height: 16),

                // Se nenhum produto foi selecionado, exibe busca no catálogo global
                if (_selectedProduct == null) ...[
                  Text(
                    'Selecione um produto do catálogo:',
                    style: theme.textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    key: const Key('product_search_input'),
                    controller: _searchController,
                    onChanged: _onSearchChanged,
                    decoration: InputDecoration(
                      hintText: 'Buscar por nome do produto...',
                      prefixIcon: const Icon(Icons.search),
                      suffixIcon: _searchController.text.isNotEmpty
                          ? IconButton(
                              key: const Key('clear_search_button'),
                              icon: const Icon(Icons.clear),
                              onPressed: () {
                                _searchController.clear();
                                widget.notifier.clearSearch();
                              },
                            )
                          : null,
                      border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                    ),
                  ),
                  const SizedBox(height: 12),
                  _buildSearchResultsSection(theme),
                ] else ...[
                  _buildSelectedProductCard(theme, isEditing),
                  const SizedBox(height: 20),
                  _buildAvailabilitySection(theme),
                  const SizedBox(height: 20),
                  _buildPriceSection(theme),
                  const SizedBox(height: 28),
                  ElevatedButton(
                    key: const Key('save_offer_button'),
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 14),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                    ),
                    onPressed: widget.notifier.isSaving ? null : _handleSave,
                    child: widget.notifier.isSaving
                        ? const SizedBox(
                            width: 22,
                            height: 22,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : Text(
                            isEditing || _isPreExistingInVitrine
                                ? 'Atualizar oferta'
                                : 'Publicar na vitrine',
                            style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
                          ),
                  ),
                ],
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildSearchResultsSection(ThemeData theme) {
    if (widget.notifier.isSearching) {
      return const Padding(
        padding: EdgeInsets.all(24.0),
        child: Center(child: CircularProgressIndicator()),
      );
    }

    if (widget.notifier.searchErrorMessage != null) {
      return Padding(
        padding: const EdgeInsets.all(16.0),
        child: Text(
          widget.notifier.searchErrorMessage!,
          style: TextStyle(color: theme.colorScheme.error),
          textAlign: TextAlign.center,
        ),
      );
    }

    if (widget.notifier.searchResults.isEmpty && _searchController.text.trim().length >= 2) {
      return Padding(
        padding: const EdgeInsets.all(16.0),
        child: Text(
          'Nenhum produto ativo encontrado no catálogo para "${_searchController.text.trim()}".',
          style: TextStyle(color: theme.colorScheme.onSurface.withAlpha(160)),
          textAlign: TextAlign.center,
        ),
      );
    }

    if (widget.notifier.searchResults.isEmpty) {
      return const SizedBox.shrink();
    }

    return Container(
      key: const Key('product_search_results'),
      constraints: const BoxConstraints(maxHeight: 280),
      decoration: BoxDecoration(
        border: Border.all(color: theme.colorScheme.outlineVariant.withAlpha(80)),
        borderRadius: BorderRadius.circular(12),
      ),
      child: ListView.separated(
        shrinkWrap: true,
        itemCount: widget.notifier.searchResults.length,
        separatorBuilder: (_, __) => const Divider(height: 1),
        itemBuilder: (context, index) {
          final item = widget.notifier.searchResults[index];
          final alreadyOffered = widget.notifier.findExistingOffer(item.id) != null;

          return ListTile(
            key: Key('product_search_item_${item.id}'),
            leading: CircleAvatar(
              backgroundColor: theme.colorScheme.primaryContainer,
              child: Icon(Icons.shopping_bag_outlined, color: theme.colorScheme.onPrimaryContainer),
            ),
            title: Text(item.name, style: const TextStyle(fontWeight: FontWeight.w600)),
            subtitle: Text(item.category ?? 'Produto'),
            trailing: alreadyOffered
                ? Container(
                    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                    decoration: BoxDecoration(
                      color: Colors.amber.withAlpha(40),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(color: Colors.amber.withAlpha(120)),
                    ),
                    child: const Text(
                      'Na vitrine',
                      style: TextStyle(fontSize: 11, color: Colors.amber, fontWeight: FontWeight.bold),
                    ),
                  )
                : const Icon(Icons.arrow_forward_ios, size: 14),
            onTap: () => _selectSearchResult(item),
          );
        },
      ),
    );
  }

  Widget _buildSelectedProductCard(ThemeData theme, bool isEditing) {
    return Container(
      key: const Key('selected_product_card'),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest.withAlpha(60),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: theme.colorScheme.outlineVariant.withAlpha(90)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              CircleAvatar(
                backgroundColor: theme.colorScheme.primaryContainer,
                child: Icon(Icons.shopping_bag, color: theme.colorScheme.onPrimaryContainer),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _selectedProduct!.name,
                      style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.bold),
                    ),
                    if (_selectedProduct!.category.isNotEmpty || _selectedProduct!.brand != null) ...[
                      const SizedBox(height: 2),
                      Text(
                        [
                          if (_selectedProduct!.brand != null && _selectedProduct!.brand!.isNotEmpty)
                            _selectedProduct!.brand!,
                          if (_selectedProduct!.category.isNotEmpty)
                            _selectedProduct!.category,
                        ].join(' • '),
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurface.withAlpha(150),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
              if (!isEditing)
                TextButton(
                  key: const Key('change_selected_product_button'),
                  onPressed: () {
                    setState(() {
                      _selectedProduct = null;
                      _isPreExistingInVitrine = false;
                    });
                  },
                  child: const Text('Trocar'),
                ),
            ],
          ),
          if (_isPreExistingInVitrine) ...[
            const SizedBox(height: 10),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              decoration: BoxDecoration(
                color: Colors.amber.withAlpha(25),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.amber.withAlpha(100)),
              ),
              child: const Row(
                children: [
                  Icon(Icons.info_outline, size: 16, color: Colors.amber),
                  SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      'Este produto já possui oferta cadastrada. A operação atualizará os termos existentes.',
                      style: TextStyle(fontSize: 12, color: Colors.amber, fontWeight: FontWeight.w600),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildAvailabilitySection(ThemeData theme) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Disponibilidade no local',
          style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 8),
        SegmentedButton<String>(
          key: const Key('offer_availability_selector'),
          segments: const [
            ButtonSegment(
              value: 'AVAILABLE',
              label: Text('Disponível'),
              icon: Icon(Icons.check_circle_outline),
            ),
            ButtonSegment(
              value: 'OUT_OF_STOCK',
              label: Text('Esgotado'),
              icon: Icon(Icons.cancel_outlined),
            ),
          ],
          selected: {_availability},
          onSelectionChanged: (selected) {
            setState(() => _availability = selected.first);
          },
        ),
      ],
    );
  }

  Widget _buildPriceSection(ThemeData theme) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Preço na vitrine',
          style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          children: [
            ChoiceChip(
              key: const Key('price_mode_custom'),
              label: const Text('Informar valor'),
              selected: _priceMode == _PriceMode.custom,
              onSelected: (selected) {
                if (selected) setState(() => _priceMode = _PriceMode.custom);
              },
            ),
            ChoiceChip(
              key: const Key('price_mode_free'),
              label: const Text('Gratuito'),
              selected: _priceMode == _PriceMode.free,
              onSelected: (selected) {
                if (selected) {
                  setState(() {
                    _priceMode = _PriceMode.free;
                    _priceError = null;
                  });
                }
              },
            ),
            ChoiceChip(
              key: const Key('price_mode_undisclosed'),
              label: const Text('Não informado'),
              selected: _priceMode == _PriceMode.undisclosed,
              onSelected: (selected) {
                if (selected) {
                  setState(() {
                    _priceMode = _PriceMode.undisclosed;
                    _priceError = null;
                  });
                }
              },
            ),
          ],
        ),
        if (_priceMode == _PriceMode.custom) ...[
          const SizedBox(height: 12),
          TextField(
            key: const Key('offer_price_input'),
            controller: _priceController,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(
              prefixText: 'R\$ ',
              labelText: 'Valor em reais',
              hintText: '0,00',
              errorText: _priceError,
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
              contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
            ),
            onChanged: (_) {
              if (_priceError != null) setState(() => _priceError = null);
            },
          ),
        ],
      ],
    );
  }
}
