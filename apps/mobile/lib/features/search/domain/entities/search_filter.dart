/// Filtros visuais aplicáveis sobre os resultados da Busca Global no catálogo.
enum SearchFilter {
  all,
  places,
  products;

  String get displayName {
    switch (this) {
      case SearchFilter.all:
        return 'Todos';
      case SearchFilter.places:
        return 'Locais';
      case SearchFilter.products:
        return 'Produtos';
    }
  }
}
