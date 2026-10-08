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
  final String city;
  final String state;
  final String? country;
  final double latitude;
  final double longitude;
  final int validationRadiusMeters;
  final String origin;
  final bool isVerified;
  final String status;

  const PlaceDetail({
    required this.id,
    required this.name,
    required this.slug,
    required this.category,
    this.description,
    required this.addressText,
    this.streetNumber,
    this.neighborhood,
    required this.city,
    required this.state,
    this.country,
    required this.latitude,
    required this.longitude,
    required this.validationRadiusMeters,
    required this.origin,
    required this.isVerified,
    required this.status,
  });

  /// Endereço formatado e legível para exibição ao usuário.
  String get formattedAddress {
    final buffer = StringBuffer(addressText);
    if (streetNumber != null && streetNumber!.trim().isNotEmpty) {
      buffer.write(', $streetNumber');
    }
    if (neighborhood != null && neighborhood!.trim().isNotEmpty) {
      buffer.write(' - $neighborhood');
    }
    buffer.write(' - $city, $state');
    if (country != null && country!.trim().isNotEmpty && country != 'BR') {
      buffer.write(' ($country)');
    }
    return buffer.toString();
  }
}
