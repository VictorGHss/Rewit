import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/features/place/data/models/place_commercial_dtos.dart';

void main() {
  group('Place Commercial DTOs & Entities Tests', () {
    test('desserializa horários de funcionamento configurados e formata dias', () {
      final json = {
        'placeId': 'place-uuid-1',
        'configured': true,
        'days': [
          {
            'dayOfWeek': 1,
            'dayName': 'MONDAY',
            'closed': false,
            'intervals': [
              {'openTime': '08:00', 'closeTime': '12:00'},
              {'openTime': '13:30', 'closeTime': '18:00'},
            ],
          },
          {
            'dayOfWeek': 7,
            'dayName': 'SUNDAY',
            'closed': true,
            'intervals': <Map<String, dynamic>>[],
          },
        ],
      };

      final dto = PlaceOpeningHoursDto.fromJson(json);
      final entity = dto.toEntity();

      expect(entity.placeId, 'place-uuid-1');
      expect(entity.configured, isTrue);
      expect(entity.days.length, 2);

      final monday = entity.days[0];
      expect(monday.dayOfWeek, 1);
      expect(monday.dayName, 'MONDAY');
      expect(monday.localizedDayName, 'Segunda-feira');
      expect(monday.closed, isFalse);
      expect(monday.intervals.length, 2);
      expect(monday.formattedSchedule, '08:00 às 12:00, 13:30 às 18:00');

      final sunday = entity.days[1];
      expect(sunday.dayOfWeek, 7);
      expect(sunday.localizedDayName, 'Domingo');
      expect(sunday.closed, isTrue);
      expect(sunday.formattedSchedule, 'Fechado');
    });

    test('desserializa horários de funcionamento não configurados', () {
      final json = {
        'placeId': 'place-uuid-2',
        'configured': false,
        'days': <Map<String, dynamic>>[],
      };

      final entity = PlaceOpeningHoursDto.fromJson(json).toEntity();
      expect(entity.placeId, 'place-uuid-2');
      expect(entity.configured, isFalse);
      expect(entity.days, isEmpty);
    });

    test('desserializa comodidades configuradas com itens', () {
      final json = {
        'placeId': 'place-uuid-1',
        'configured': true,
        'amenities': [
          {'code': 'WIFI', 'label': 'Wi-Fi para clientes'},
          {'code': 'PARKING', 'label': 'Estacionamento próprio'},
        ],
      };

      final entity = PlaceAmenitiesDto.fromJson(json).toEntity();
      expect(entity.placeId, 'place-uuid-1');
      expect(entity.configured, isTrue);
      expect(entity.hasDeclaredNone, isFalse);
      expect(entity.amenities.length, 2);
      expect(entity.amenities[0].code, 'WIFI');
      expect(entity.amenities[0].label, 'Wi-Fi para clientes');
    });

    test('desserializa comodidades com declaração explícita de nenhuma', () {
      final json = {
        'placeId': 'place-uuid-1',
        'configured': true,
        'amenities': <Map<String, dynamic>>[],
      };

      final entity = PlaceAmenitiesDto.fromJson(json).toEntity();
      expect(entity.configured, isTrue);
      expect(entity.amenities, isEmpty);
      expect(entity.hasDeclaredNone, isTrue);
    });

    test('desserializa comodidades não configuradas', () {
      final json = {
        'placeId': 'place-uuid-1',
        'configured': false,
        'amenities': <Map<String, dynamic>>[],
      };

      final entity = PlaceAmenitiesDto.fromJson(json).toEntity();
      expect(entity.configured, isFalse);
      expect(entity.hasDeclaredNone, isFalse);
    });

    test('desserializa página de ofertas comerciais oficiais e formata preços e disponibilidade', () {
      final json = {
        'content': [
          {
            'product': {
              'id': 'prod-1',
              'name': 'Café Expresso',
              'brand': 'Marca X',
              'model': 'Grão Especial',
              'category': 'BEBIDA',
              'imageUrl': null,
            },
            'availability': 'AVAILABLE',
            'price': 8.50,
            'currency': 'BRL',
          },
          {
            'product': {
              'id': 'prod-2',
              'name': 'Água da Casa',
              'brand': null,
              'model': null,
              'category': 'BEBIDA',
              'imageUrl': null,
            },
            'availability': 'AVAILABLE',
            'price': 0.0,
            'currency': 'BRL',
          },
          {
            'product': {
              'id': 'prod-3',
              'name': 'Pão de Queijo',
              'brand': 'Padaria',
              'category': 'PADARIA',
            },
            'availability': 'OUT_OF_STOCK',
            'price': null,
            'currency': 'BRL',
          },
          {
            'product': {
              'id': 'prod-4',
              'name': 'Bolo Festivo Grande',
              'category': 'CONFEITARIA',
            },
            'availability': 'AVAILABLE',
            'price': 1250.75,
            'currency': 'BRL',
          },
        ],
        'pageNumber': 0,
        'pageSize': 50,
        'totalElements': 4,
        'totalPages': 1,
        'isLast': true,
      };

      final page = PlaceOffersPageDto.fromJson(json).toEntity();
      expect(page.content.length, 4);
      expect(page.pageNumber, 0);
      expect(page.pageSize, 50);
      expect(page.totalElements, 4);
      expect(page.isLast, isTrue);

      // 1. Café Expresso (preço positivo)
      final cafe = page.content[0];
      expect(cafe.product.id, 'prod-1');
      expect(cafe.product.displayName, 'Café Expresso (Marca X Grão Especial)');
      expect(cafe.isAvailable, isTrue);
      expect(cafe.localizedAvailability, 'Disponível');
      expect(cafe.formattedPrice, 'R\$ 8,50');

      // 2. Água da Casa (gratuito)
      final agua = page.content[1];
      expect(agua.product.displayName, 'Água da Casa');
      expect(agua.formattedPrice, 'Gratuito');

      // 3. Pão de Queijo (sem estoque e sem preço)
      final pao = page.content[2];
      expect(pao.isOutOfStock, isTrue);
      expect(pao.localizedAvailability, 'Sem estoque');
      expect(pao.formattedPrice, 'Preço não informado');

      // 4. Bolo Festivo (formatação com separador de milhar)
      final bolo = page.content[3];
      expect(bolo.formattedPrice, 'R\$ 1.250,75');
    });
  });
}
