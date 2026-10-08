import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:geolocator/geolocator.dart';
import 'package:rewit_mobile/features/review_creation/data/services/geolocator_location_service.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/device_location.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/location_service.dart';

class FakeGeolocatorPlatform extends GeolocatorPlatform {
  bool serviceEnabled = true;
  LocationPermission checkPermissionResult = LocationPermission.always;
  LocationPermission requestPermissionResult = LocationPermission.always;
  Position? positionToReturn;
  Exception? exceptionOnGetPosition;
  bool openAppSettingsResult = true;

  @override
  Future<bool> isLocationServiceEnabled() async => serviceEnabled;

  @override
  Future<LocationPermission> checkPermission() async => checkPermissionResult;

  @override
  Future<LocationPermission> requestPermission() async => requestPermissionResult;

  @override
  Future<Position> getCurrentPosition({LocationSettings? locationSettings}) async {
    if (exceptionOnGetPosition != null) {
      throw exceptionOnGetPosition!;
    }
    return positionToReturn ??
        Position(
          latitude: -23.5505,
          longitude: -46.6333,
          timestamp: DateTime.now(),
          accuracy: 12.0,
          altitude: 0.0,
          altitudeAccuracy: 0.0,
          heading: 0.0,
          headingAccuracy: 0.0,
          speed: 0.0,
          speedAccuracy: 0.0,
        );
  }

  @override
  Future<bool> openAppSettings() async => openAppSettingsResult;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('GeolocatorLocationService Tests', () {
    late FakeGeolocatorPlatform fakePlatform;
    late GeolocatorLocationService service;

    setUp(() {
      fakePlatform = FakeGeolocatorPlatform();
      GeolocatorPlatform.instance = fakePlatform;
      service = const GeolocatorLocationService();
    });

    test('retorna LocationSuccess com DeviceLocation pontual quando serviço e permissão estão ativos', () async {
      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.whileInUse;
      fakePlatform.positionToReturn = Position(
        latitude: -22.9068,
        longitude: -43.1729,
        timestamp: DateTime.now(),
        accuracy: 15.5,
        altitude: 10.0,
        altitudeAccuracy: 1.0,
        heading: 0.0,
        headingAccuracy: 0.0,
        speed: 0.0,
        speedAccuracy: 0.0,
      );

      final result = await service.getCurrentLocation();

      expect(result, isA<LocationSuccess>());
      final success = result as LocationSuccess;
      expect(success.location.latitude, -22.9068);
      expect(success.location.longitude, -43.1729);
      expect(success.location.accuracyMeters, 15.5);
      expect(success.location.isApproximate, isFalse);
    });

    test('DeviceLocation identifica acurácia aproximada quando > 100m', () {
      const accurate = DeviceLocation(latitude: 0, longitude: 0, accuracyMeters: 25.0);
      const approximate = DeviceLocation(latitude: 0, longitude: 0, accuracyMeters: 150.0);

      expect(accurate.isApproximate, isFalse);
      expect(approximate.isApproximate, isTrue);
    });

    test('retorna serviceDisabled quando o GPS/serviço está desligado no dispositivo', () async {
      fakePlatform.serviceEnabled = false;

      final result = await service.getCurrentLocation();

      expect(result, isA<LocationFailure>());
      final failure = result as LocationFailure;
      expect(failure.reason, LocationFailureReason.serviceDisabled);
      expect(failure.message, contains('desativado'));
    });

    test('solicita permissão se negada inicialmente e retorna permissionDenied se recusada', () async {
      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.denied;
      fakePlatform.requestPermissionResult = LocationPermission.denied;

      final result = await service.getCurrentLocation();

      expect(result, isA<LocationFailure>());
      final failure = result as LocationFailure;
      expect(failure.reason, LocationFailureReason.permissionDenied);
      expect(failure.message, contains('negada'));
    });

    test('solicita permissão se negada inicialmente e obtém sucesso se concedida', () async {
      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.denied;
      fakePlatform.requestPermissionResult = LocationPermission.whileInUse;

      final result = await service.getCurrentLocation();

      expect(result, isA<LocationSuccess>());
    });

    test('retorna permissionDeniedForever se a permissão foi negada permanentemente', () async {
      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.deniedForever;

      final result = await service.getCurrentLocation();

      expect(result, isA<LocationFailure>());
      final failure = result as LocationFailure;
      expect(failure.reason, LocationFailureReason.permissionDeniedForever);
      expect(failure.message, contains('permanentemente negada'));
    });

    test('retorna timeout quando a busca de posição excede o limite', () async {
      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.always;
      fakePlatform.exceptionOnGetPosition = TimeoutException('Tempo esgotado');

      final result = await service.getCurrentLocation();

      expect(result, isA<LocationFailure>());
      final failure = result as LocationFailure;
      expect(failure.reason, LocationFailureReason.timeout);
      expect(failure.message, contains('Não foi possível obter sua localização agora.'));
    });

    test('retorna error genérico quando ocorre falha inesperada', () async {
      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.always;
      fakePlatform.exceptionOnGetPosition = Exception('Erro de sensor');

      final result = await service.getCurrentLocation();

      expect(result, isA<LocationFailure>());
      final failure = result as LocationFailure;
      expect(failure.reason, LocationFailureReason.error);
    });

    test('openAppSettings repassa para o platform handler', () async {
      fakePlatform.openAppSettingsResult = true;
      expect(await service.openAppSettings(), isTrue);

      fakePlatform.openAppSettingsResult = false;
      expect(await service.openAppSettings(), isFalse);
    });
  });
}
