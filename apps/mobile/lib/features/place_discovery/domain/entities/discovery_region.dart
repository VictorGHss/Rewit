/// As 27 siglas oficiais de UF (26 estados e o Distrito Federal). Regiões como "Sul" não são UF.
const List<String> brazilianStateCodes = [
  'AC', 'AL', 'AP', 'AM', 'BA', 'CE', 'DF', 'ES', 'GO', 'MA', 'MT', 'MS', 'MG', 'PA',
  'PB', 'PR', 'PE', 'PI', 'RJ', 'RN', 'RS', 'RO', 'RR', 'SC', 'SP', 'SE', 'TO',
];

bool isBrazilianStateCode(String? value) =>
    value != null && brazilianStateCodes.contains(value.trim().toUpperCase());

/// Região escolhida pelo usuário para a busca externa. A cidade escolhida nunca é trocada pela posição do aparelho.
sealed class DiscoveryRegion {
  const DiscoveryRegion();

  /// Texto exibido junto dos resultados, dizendo onde a busca foi feita.
  String get label;
}

/// Busca restrita à fronteira de uma cidade brasileira; resultados de outras cidades são excluídos pelo backend.
class CityRegion extends DiscoveryRegion {
  final String city;
  final String state;

  const CityRegion({required this.city, required this.state});

  @override
  String get label => 'Região: ${city.trim()} – ${state.toUpperCase()}';
}

/// Busca num raio em torno de coordenadas obtidas uma única vez, com permissão, no momento da busca.
class NearbyRegion extends DiscoveryRegion {
  final double latitude;
  final double longitude;
  final double radiusMeters;

  const NearbyRegion({required this.latitude, required this.longitude, this.radiusMeters = defaultRadiusMeters});

  static const double defaultRadiusMeters = 10000;

  @override
  String get label => 'Região: até ${(radiusMeters / 1000).round()} km da sua localização atual';
}

/// Sem restrição geográfica: o provedor pode devolver locais de qualquer cidade.
class UnrestrictedRegion extends DiscoveryRegion {
  const UnrestrictedRegion();

  @override
  String get label => 'Sem restrição geográfica: os resultados podem ser de qualquer cidade';
}
