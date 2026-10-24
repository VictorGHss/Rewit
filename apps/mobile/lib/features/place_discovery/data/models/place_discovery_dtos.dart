import '../../domain/entities/external_place_candidate.dart';

/// Leitura defensiva de `PlaceCandidateResponse`; itens sem provedor, id ou nome são descartados pelo chamador.
/// Atribuições chegam como `{provider, providerUri}` (`PlaceAttributionResponse`).
class ExternalPlaceCandidateDto {
  const ExternalPlaceCandidateDto._();

  static ExternalPlaceCandidate? fromJson(Map<String, dynamic> json) {
    final provider = _text(json['provider']);
    final externalId = _text(json['externalId']);
    final displayName = _text(json['displayName']);
    if (provider == null || externalId == null || displayName == null) return null;

    final rawTypes = json['types'];
    final rawAttributions = json['attributions'];
    return ExternalPlaceCandidate(
      provider: provider,
      externalId: externalId,
      displayName: displayName,
      formattedAddress: _text(json['formattedAddress']) ?? '',
      latitude: (json['latitude'] as num?)?.toDouble(),
      longitude: (json['longitude'] as num?)?.toDouble(),
      types: rawTypes is List ? rawTypes.whereType<String>().toList() : const [],
      attributions: rawAttributions is List
          ? rawAttributions
              .whereType<Map<String, dynamic>>()
              .map((a) => _text(a['provider']) == null
                  ? null
                  : ExternalPlaceAttribution(text: _text(a['provider'])!, url: _text(a['providerUri'])))
              .whereType<ExternalPlaceAttribution>()
              .toList()
          : const [],
      city: _text(json['city']),
      state: _text(json['state']),
      country: _text(json['country']),
    );
  }

  static String? _text(Object? value) {
    if (value is! String) return null;
    final trimmed = value.trim();
    return trimmed.isEmpty ? null : trimmed;
  }
}

/// Corpo de `POST /api/v1/places/adopt` montado a partir do candidato; a referência externa garante a deduplicação.
class AdoptPlaceRequestDto {
  const AdoptPlaceRequestDto._();

  static Map<String, dynamic> fromCandidate(ExternalPlaceCandidate candidate) {
    return {
      'name': _limit(candidate.displayName, 255),
      'addressText': _limit(candidate.formattedAddress, 500),
      'city': _limit(candidate.city ?? '', 100),
      'state': _limit(candidate.state ?? '', 50),
      if (candidate.country != null && candidate.country!.length <= 10) 'country': candidate.country,
      'latitude': candidate.latitude,
      'longitude': candidate.longitude,
      'externalReference': {
        'provider': candidate.provider,
        'externalId': candidate.externalId,
      },
    };
  }

  static String _limit(String value, int max) {
    final trimmed = value.trim();
    return trimmed.length <= max ? trimmed : trimmed.substring(0, max);
  }
}
