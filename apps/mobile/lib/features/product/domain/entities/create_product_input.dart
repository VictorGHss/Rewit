/// Dados para cadastrar um produto global no catálogo (`POST /api/v1/products`).
///
/// Os limites espelham as colunas de `products` no backend: só o nome é validado pela API, então o app recusa
/// antes do envio o que o banco não aceitaria (marca, modelo e categoria acima do tamanho).
class CreateProductInput {
  static const int maxNameLength = 255;
  static const int maxBrandLength = 128;
  static const int maxModelLength = 128;
  static const int maxCategoryLength = 64;
  static const int maxDescriptionLength = 2000;

  final String name;
  final String? brand;
  final String? model;
  final String? category;
  final String? description;

  const CreateProductInput({
    required this.name,
    this.brand,
    this.model,
    this.category,
    this.description,
  });

  /// Corpo da requisição: campos opcionais vazios não são enviados.
  Map<String, dynamic> toJson() {
    String? clean(String? value) {
      final trimmed = value?.trim();
      return (trimmed == null || trimmed.isEmpty) ? null : trimmed;
    }

    return {
      'name': name.trim(),
      if (clean(brand) != null) 'brand': clean(brand),
      if (clean(model) != null) 'model': clean(model),
      if (clean(category) != null) 'category': clean(category),
      if (clean(description) != null) 'description': clean(description),
    };
  }
}
