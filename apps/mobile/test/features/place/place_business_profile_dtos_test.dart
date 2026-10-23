import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/features/place/data/models/place_dtos.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';

void main() {
  group('Place Business Profile DTOs & Models Tests (F2.4)', () {
    test('PlaceDto mapeia contatos públicos e perfil comercial completo', () {
      final json = {
        'id': 'place-uuid-1',
        'name': 'Restaurante Sabor & Arte',
        'slug': 'restaurante-sabor-arte',
        'category': 'ALIMENTACAO',
        'description': 'Restaurante acolhedor de comida caseira com ingredientes orgânicos.',
        'addressText': 'Av. Paulista, 1000',
        'streetNumber': '1000',
        'neighborhood': 'Bela Vista',
        'city': 'São Paulo',
        'state': 'SP',
        'country': 'BR',
        'latitude': -23.5615,
        'longitude': -46.6559,
        'validationRadiusMeters': 60,
        'origin': 'CLAIMED',
        'isVerified': true,
        'status': 'ACTIVE',
        'photoUrl': 'https://storage.rewit.com/media/capa.jpg',
        'phone': '+55 (11) 98765-4321',
        'websiteUrl': 'https://saborearte.com.br',
        'instagramUrl': 'https://instagram.com/saborearte',
        'facebookUrl': 'https://facebook.com/saborearteoficial',
      };

      final dto = PlaceDto.fromJson(json);
      final entity = dto.toEntity();

      expect(entity.id, 'place-uuid-1');
      expect(entity.name, 'Restaurante Sabor & Arte');
      expect(entity.description, 'Restaurante acolhedor de comida caseira com ingredientes orgânicos.');
      expect(entity.phone, '+55 (11) 98765-4321');
      expect(entity.websiteUrl, 'https://saborearte.com.br');
      expect(entity.instagramUrl, 'https://instagram.com/saborearte');
      expect(entity.facebookUrl, 'https://facebook.com/saborearteoficial');

      final serialized = dto.toJson();
      expect(serialized['phone'], '+55 (11) 98765-4321');
      expect(serialized['websiteUrl'], 'https://saborearte.com.br');
      expect(serialized['instagramUrl'], 'https://instagram.com/saborearte');
      expect(serialized['facebookUrl'], 'https://facebook.com/saborearteoficial');
    });

    test('PlaceDto preserva compatibilidade com resposta legada sem campos de perfil/contato', () {
      final legacyJson = {
        'id': 'place-uuid-2',
        'name': 'Padaria Pão Quente',
        'slug': 'padaria-pao-quente',
        'category': 'ALIMENTACAO',
        'addressText': 'Rua das Flores, 50',
        'city': 'Curitiba',
        'state': 'PR',
        'country': 'BR',
        'validationRadiusMeters': 50,
        'origin': 'USER',
        'isVerified': false,
        'status': 'ACTIVE',
        'phone': '',
        'websiteUrl': '   ',
        'instagramUrl': null,
      };

      final dto = PlaceDto.fromJson(legacyJson);
      final entity = dto.toEntity();

      expect(entity.phone, isNull);
      expect(entity.websiteUrl, isNull);
      expect(entity.instagramUrl, isNull);
      expect(entity.facebookUrl, isNull);

      final serialized = dto.toJson();
      expect(serialized.containsKey('phone'), isFalse);
      expect(serialized.containsKey('websiteUrl'), isFalse);
      expect(serialized.containsKey('instagramUrl'), isFalse);
      expect(serialized.containsKey('facebookUrl'), isFalse);
    });

    test('PlaceDetail copyWith atualiza perfil comercial preservando outros campos', () {
      const original = PlaceDetail(
        id: 'place-1',
        name: 'Local Original',
        slug: 'local-original',
        category: 'GERAL',
        description: 'Descrição Antiga',
        addressText: 'Rua Um, 1',
        validationRadiusMeters: 50,
        origin: 'CLAIMED',
        isVerified: true,
        status: 'ACTIVE',
        phone: '11999998888',
        websiteUrl: 'https://site.antigo.com',
      );

      final updated = original.copyWith(
        description: 'Descrição Nova',
        phone: '11911112222',
        instagramUrl: 'https://instagram.com/novo',
      );

      expect(updated.id, original.id);
      expect(updated.name, original.name);
      expect(updated.description, 'Descrição Nova');
      expect(updated.phone, '11911112222');
      expect(updated.websiteUrl, 'https://site.antigo.com');
      expect(updated.instagramUrl, 'https://instagram.com/novo');
      expect(updated.facebookUrl, isNull);
    });

    test('UpdatePlaceBusinessProfileRequestDto converte vazios/blanks em null mantendo integridade', () {
      const dto = UpdatePlaceBusinessProfileRequestDto(
        businessAccountId: 'acc-uuid-1',
        description: '  Espaço para eventos e celebrações.  ',
        phone: '   ',
        websiteUrl: 'https://espaco.com.br',
        instagramUrl: '',
        facebookUrl: null,
      );

      final json = dto.toJson();

      expect(json['businessAccountId'], 'acc-uuid-1');
      expect(json['description'], 'Espaço para eventos e celebrações.');
      expect(json['phone'], isNull);
      expect(json['websiteUrl'], 'https://espaco.com.br');
      expect(json['instagramUrl'], isNull);
      expect(json['facebookUrl'], isNull);
    });
  });
}
