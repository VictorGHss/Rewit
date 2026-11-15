import 'package:flutter/material.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import '../../domain/entities/create_product_input.dart';
import '../../domain/entities/product_detail.dart';
import '../../domain/repositories/product_repository.dart';

/// Formulário em bottom sheet para cadastrar um produto global inexistente no catálogo.
///
/// Fecha devolvendo o [ProductDetail] criado; fechar sem cadastrar devolve `null`. Não altera o formulário da
/// avaliação: quem abre decide o que fazer com o produto criado.
class ManualProductRegistrationSheet extends StatefulWidget {
  final ProductRepository productRepository;
  final String initialName;

  const ManualProductRegistrationSheet({
    super.key,
    required this.productRepository,
    this.initialName = '',
  });

  static Future<ProductDetail?> show(
    BuildContext context, {
    required ProductRepository productRepository,
    String initialName = '',
  }) {
    return showModalBottomSheet<ProductDetail>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (_) => ManualProductRegistrationSheet(
        productRepository: productRepository,
        initialName: initialName,
      ),
    );
  }

  @override
  State<ManualProductRegistrationSheet> createState() => _ManualProductRegistrationSheetState();
}

class _ManualProductRegistrationSheetState extends State<ManualProductRegistrationSheet> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameController;
  final _brandController = TextEditingController();
  final _modelController = TextEditingController();
  final _categoryController = TextEditingController();
  final _descriptionController = TextEditingController();

  bool _isSubmitting = false;
  String? _errorMessage;
  Map<String, String> _fieldErrors = const {};

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(text: widget.initialName.trim());
  }

  @override
  void dispose() {
    _nameController.dispose();
    _brandController.dispose();
    _modelController.dispose();
    _categoryController.dispose();
    _descriptionController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_isSubmitting) return;
    setState(() {
      _errorMessage = null;
      _fieldErrors = const {};
    });
    if (!(_formKey.currentState?.validate() ?? false)) return;

    setState(() => _isSubmitting = true);
    try {
      final product = await widget.productRepository.createProduct(CreateProductInput(
        name: _nameController.text,
        brand: _brandController.text,
        model: _modelController.text,
        category: _categoryController.text,
        description: _descriptionController.text,
      ));
      if (!mounted) return;
      Navigator.of(context).pop(product);
      return;
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _fieldErrors = e.allFieldErrors;
        _errorMessage = e.retryAfterSeconds != null
            ? '${e.detail} Aguarde ${e.retryAfterSeconds} segundos antes de tentar novamente.'
            : e.isConflict
                ? 'Não foi possível cadastrar o produto com esses dados. Revise os campos e tente novamente.'
                : e.detail;
      });
    } on NetworkException catch (e) {
      if (!mounted) return;
      setState(() => _errorMessage = e.message);
    } catch (_) {
      if (!mounted) return;
      setState(() => _errorMessage = 'Não foi possível cadastrar o produto no momento. Tente novamente.');
    }
    if (mounted) setState(() => _isSubmitting = false);
  }

  String? _required(String? value) {
    if (value == null || value.trim().isEmpty) return 'Informe o nome do produto.';
    return _maxLength(value, CreateProductInput.maxNameLength);
  }

  String? _maxLength(String? value, int max) {
    if (value != null && value.trim().length > max) return 'Use no máximo $max caracteres.';
    return null;
  }

  Widget _field({
    required Key key,
    required TextEditingController controller,
    required String label,
    required String serverField,
    required int maxLength,
    String? hint,
    String? Function(String?)? validator,
    int maxLines = 1,
    TextInputAction action = TextInputAction.next,
  }) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: TextFormField(
        key: key,
        controller: controller,
        enabled: !_isSubmitting,
        maxLength: maxLength,
        maxLines: maxLines,
        textInputAction: action,
        decoration: InputDecoration(
          labelText: label,
          hintText: hint,
          errorText: _fieldErrors[serverField],
          isDense: true,
          border: const OutlineInputBorder(),
        ),
        validator: validator ?? (value) => _maxLength(value, maxLength),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('Cadastrar produto', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold)),
              const SizedBox(height: 4),
              Text(
                'O produto entra no catálogo global e já fica selecionado nesta avaliação. '
                'Antes de cadastrar, confira se ele não aparece na busca.',
                style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurface.withAlpha(170)),
              ),
              const SizedBox(height: 16),
              _field(
                key: const Key('manual_product_name_field'),
                controller: _nameController,
                label: 'Nome do produto *',
                serverField: 'name',
                maxLength: CreateProductInput.maxNameLength,
                hint: 'ex: Café torrado 500 g',
                validator: _required,
              ),
              _field(
                key: const Key('manual_product_brand_field'),
                controller: _brandController,
                label: 'Marca',
                serverField: 'brand',
                maxLength: CreateProductInput.maxBrandLength,
              ),
              _field(
                key: const Key('manual_product_model_field'),
                controller: _modelController,
                label: 'Modelo ou variação',
                serverField: 'model',
                maxLength: CreateProductInput.maxModelLength,
              ),
              _field(
                key: const Key('manual_product_category_field'),
                controller: _categoryController,
                label: 'Categoria',
                serverField: 'category',
                maxLength: CreateProductInput.maxCategoryLength,
                hint: 'ex: Bebidas',
              ),
              _field(
                key: const Key('manual_product_description_field'),
                controller: _descriptionController,
                label: 'Descrição',
                serverField: 'description',
                maxLength: CreateProductInput.maxDescriptionLength,
                maxLines: 3,
                action: TextInputAction.newline,
              ),
              if (_errorMessage != null) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  margin: const EdgeInsets.only(bottom: 12),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.errorContainer.withAlpha(120),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    _errorMessage!,
                    key: const Key('manual_product_error_text'),
                    style: TextStyle(color: theme.colorScheme.onErrorContainer, fontSize: 13),
                  ),
                ),
              ],
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: _isSubmitting ? null : () => Navigator.of(context).pop(),
                      child: const Text('Cancelar'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      key: const Key('manual_product_submit_button'),
                      onPressed: _isSubmitting ? null : _submit,
                      child: _isSubmitting
                          ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Text('Cadastrar'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
