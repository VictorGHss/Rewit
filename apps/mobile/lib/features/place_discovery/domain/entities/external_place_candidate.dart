/// Atribuição exigida pela fonte de um candidato externo (ex.: "Powered by Geoapify", "© OpenStreetMap contributors").
class ExternalPlaceAttribution {
  final String text;
  final String? url;

  const ExternalPlaceAttribution({required this.text, this.url});

  @override
  bool operator ==(Object other) =>
      identical(this, other) || other is ExternalPlaceAttribution && text == other.text && url == other.url;

  @override
  int get hashCode => Object.hash(text, url);
}

/// Candidato transitório devolvido pela descoberta externa (`GET /api/v1/places/discovery/search`).
///
/// Não é um local do catálogo Rewit: só vira alvo de avaliação depois de adotado pelo fluxo oficial
/// (`POST /api/v1/places/adopt`). Não traz nota, horário nem avaliações do provedor.
class ExternalPlaceCandidate {
  final String provider;
  final String externalId;
  final String displayName;
  final String formattedAddress;
  final double? latitude;
  final double? longitude;
  final List<String> types;
  final List<ExternalPlaceAttribution> attributions;
  final String? city;
  final String? state;
  final String? country;

  const ExternalPlaceCandidate({
    required this.provider,
    required this.externalId,
    required this.displayName,
    required this.formattedAddress,
    this.latitude,
    this.longitude,
    this.types = const [],
    this.attributions = const [],
    this.city,
    this.state,
    this.country,
  });

  /// A adoção exige endereço, cidade, UF e coordenadas; sem eles o local precisa de cadastro manual.
  bool get isAdoptable =>
      formattedAddress.trim().isNotEmpty &&
      (city?.trim().isNotEmpty ?? false) &&
      (state?.trim().isNotEmpty ?? false) &&
      latitude != null &&
      longitude != null;

  /// Nome legível da fonte, para identificar o resultado externo na interface.
  String get providerLabel => switch (provider.toUpperCase()) {
        'GEOAPIFY' => 'Geoapify / OpenStreetMap',
        'GOOGLE' => 'Google',
        _ => provider,
      };
}
