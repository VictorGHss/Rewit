/// Entidade representacional do detalhe completo de um Local Físico (Place).
class PlaceDetail {
  final String id;
  final String name;
  final String slug;
  final String category;
  final String? description;
  final String addressText;
  final String? streetNumber;
  final String? neighborhood;
  final String? city;
  final String? state;
  final String? country;
  final double? latitude;
  final double? longitude;
  final int validationRadiusMeters;
  final String origin;
  final bool isVerified;
  final String status;
  final String? photoUrl;
  final String? phone;
  final String? websiteUrl;
  final String? instagramUrl;
  final String? facebookUrl;

  const PlaceDetail({
    required this.id,
    required this.name,
    required this.slug,
    required this.category,
    this.description,
    required this.addressText,
    this.streetNumber,
    this.neighborhood,
    this.city,
    this.state,
    this.country,
    this.latitude,
    this.longitude,
    required this.validationRadiusMeters,
    required this.origin,
    required this.isVerified,
    required this.status,
    this.photoUrl,
    this.phone,
    this.websiteUrl,
    this.instagramUrl,
    this.facebookUrl,
  });

  bool get hasCoordinates => latitude != null && longitude != null;

  PlaceDetail copyWith({
    String? photoUrl,
    String? description,
    String? phone,
    String? websiteUrl,
    String? instagramUrl,
    String? facebookUrl,
  }) {
    return PlaceDetail(
      id: id,
      name: name,
      slug: slug,
      category: category,
      description: description ?? this.description,
      addressText: addressText,
      streetNumber: streetNumber,
      neighborhood: neighborhood,
      city: city,
      state: state,
      country: country,
      latitude: latitude,
      longitude: longitude,
      validationRadiusMeters: validationRadiusMeters,
      origin: origin,
      isVerified: isVerified,
      status: status,
      photoUrl: photoUrl ?? this.photoUrl,
      phone: phone ?? this.phone,
      websiteUrl: websiteUrl ?? this.websiteUrl,
      instagramUrl: instagramUrl ?? this.instagramUrl,
      facebookUrl: facebookUrl ?? this.facebookUrl,
    );
  }

  /// Endereço formatado e legível para exibição ao usuário.
  String get formattedAddress {
    final buffer = StringBuffer(addressText);
    if (streetNumber != null && streetNumber!.trim().isNotEmpty) {
      buffer.write(', ${streetNumber!.trim()}');
    }
    if (neighborhood != null && neighborhood!.trim().isNotEmpty) {
      buffer.write(' - ${neighborhood!.trim()}');
    }
    final cleanCity = city?.trim() ?? '';
    final cleanState = state?.trim() ?? '';
    if (cleanCity.isNotEmpty && cleanState.isNotEmpty) {
      buffer.write(' - $cleanCity, $cleanState');
    } else if (cleanCity.isNotEmpty) {
      buffer.write(' - $cleanCity');
    } else if (cleanState.isNotEmpty) {
      buffer.write(' - $cleanState');
    }
    if (country != null && country!.trim().isNotEmpty && country != 'BR') {
      buffer.write(' (${country!.trim()})');
    }
    return buffer.toString();
  }
}

/// Dados de entrada para cadastro manual de um novo local físico (Place) no catálogo.
class CreatePlaceInput {
  final String name;
  final String addressText;
  final String? category;
  final String? description;
  final String? streetNumber;
  final String? neighborhood;
  final String? city;
  final String? state;
  final String? country;
  final double? latitude;
  final double? longitude;

  const CreatePlaceInput({
    required this.name,
    required this.addressText,
    this.category,
    this.description,
    this.streetNumber,
    this.neighborhood,
    this.city,
    this.state,
    this.country,
    this.latitude,
    this.longitude,
  });
}
