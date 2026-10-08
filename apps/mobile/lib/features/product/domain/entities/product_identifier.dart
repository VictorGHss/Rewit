/// Identificador público comercial de um produto (ex: EAN, UPC, GTIN, ISBN).
class ProductIdentifier {
  final String identifierType;
  final String identifierValue;

  const ProductIdentifier({
    required this.identifierType,
    required this.identifierValue,
  });

  /// Tipos públicos comerciais padronizados aceitos na exibição do app.
  static const Set<String> publicTypes = {'EAN', 'UPC', 'GTIN', 'ISBN'};

  bool get isPublic => publicTypes.contains(identifierType.toUpperCase());
}

