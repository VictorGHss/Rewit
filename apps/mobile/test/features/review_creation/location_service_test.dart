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
  Position? lastKnownPositionToReturn;
  Exception? exceptionOnGetPosition;
  Exception? exceptionOnGetLastKnownPosition;
  bool openAppSettingsResult = true;

  int getCurrentPositionCallCount = 0;
  int getLastKnownPositionCallCount = 0;

  @override
  Future<bool> isLocationServiceEnabled() async => serviceEnabled;

  @override
  Future<LocationPermission> checkPermission() async => checkPermissionResult;

  @override
  Future<LocationPermission> requestPermission() async => requestPermissionResult;

  @override
  Future<Position?> getLastKnownPosition({bool forceLocationManager = false}) async {
    getLastKnownPositionCallCount++;
    if (exceptionOnGetLastKnownPosition != null) {
      throw exceptionOnGetLastKnownPosition!;
    }
    return lastKnownPositionToReturn;
  }

  @override
  Future<Position> getCurrentPosition({LocationSettings? locationSettings}) async {
    getCurrentPositionCallCount++;
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
    final originalPlatform = GeolocatorPlatform.instance;
    late FakeGeolocatorPlatform fakePlatform;
    late GeolocatorLocationService service;

    setUp(() {
      fakePlatform = FakeGeolocatorPlatform();
      GeolocatorPlatform.instance = fakePlatform;
      service = const GeolocatorLocationService();
    });

    tearDown(() {
      GeolocatorPlatform.instance = originalPlatform;
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

    test('prioriza a última posição conhecida em cache quando disponível e válida (evita chamar getCurrentPosition e previne ANR)', () async {
      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.always;
      fakePlatform.lastKnownPositionToReturn = Position(
        latitude: -25.4284,
        longitude: -49.2733,
        timestamp: DateTime.now(),
        accuracy: 25.0,
        altitude: 900.0,
        altitudeAccuracy: 5.0,
        heading: 0.0,
        headingAccuracy: 0.0,
        speed: 0.0,
        speedAccuracy: 0.0,
      );

      final result = await service.getCurrentLocation();

      expect(result, isA<LocationSuccess>());
      final success = result as LocationSuccess;
      expect(success.location.latitude, -25.4284);
      expect(success.location.longitude, -49.2733);
      expect(fakePlatform.getLastKnownPositionCallCount, 1);
      expect(fakePlatform.getCurrentPositionCallCount, 0, reason: 'Não deve acionar getCurrentPosition se o cache for válido');
    });

    test('faz fallback para getCurrentPosition quando a posição em cache for (0,0)', () async {
      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.always;
      fakePlatform.lastKnownPositionToReturn = Position(
        latitude: 0.0,
        longitude: 0.0,
        timestamp: DateTime.now(),
        accuracy: 0.0,
        altitude: 0.0,
        altitudeAccuracy: 0.0,
        heading: 0.0,
        headingAccuracy: 0.0,
        speed: 0.0,
        speedAccuracy: 0.0,
      );
      fakePlatform.positionToReturn = Position(
        latitude: -25.0994,
        longitude: -50.1583,
        timestamp: DateTime.now(),
        accuracy: 10.0,
        altitude: 0.0,
        altitudeAccuracy: 0.0,
        heading: 0.0,
        headingAccuracy: 0.0,
        speed: 0.0,
        speedAccuracy: 0.0,
      );

      final result = await service.getCurrentLocation();

      expect(result, isA<LocationSuccess>());
      final success = result as LocationSuccess;
      expect(success.location.latitude, -25.0994);
      expect(success.location.longitude, -50.1583);
      expect(fakePlatform.getLastKnownPositionCallCount, 1);
      expect(fakePlatform.getCurrentPositionCallCount, 1);
    });

    test('faz fallback para getCurrentPosition quando a leitura de cache lançar exceção', () async {
      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.always;
      fakePlatform.exceptionOnGetLastKnownPosition = Exception('Cache indisponível');
      fakePlatform.positionToReturn = Position(
        latitude: -25.0994,
        longitude: -50.1583,
        timestamp: DateTime.now(),
        accuracy: 10.0,
        altitude: 0.0,
        altitudeAccuracy: 0.0,
        heading: 0.0,
        headingAccuracy: 0.0,
        speed: 0.0,
        speedAccuracy: 0.0,
      );

      final result = await service.getCurrentLocation();

      expect(result, isA<LocationSuccess>());
      expect(fakePlatform.getCurrentPositionCallCount, 1);
    });

    test('DeviceLocation identifica acurácia aproximada respeitando o limiar exato (100m)', () {
      // Abaixo do limiar (< 100.0) -> precisa (não aproximada)
      const belowThreshold = DeviceLocation(latitude: 0, longitude: 0, accuracyMeters: 99.9);
      expect(belowThreshold.isApproximate, isFalse);
      expect(DeviceLocation.isAccuracyApproximate(99.9), isFalse);

      // Exatamente no limiar (100.0) -> precisa (não aproximada, regra é > 100.0)
      const atThreshold = DeviceLocation(latitude: 0, longitude: 0, accuracyMeters: 100.0);
      expect(atThreshold.isApproximate, isFalse);
      expect(DeviceLocation.isAccuracyApproximate(100.0), isFalse);
      expect(DeviceLocation.approximateAccuracyThresholdMeters, 100.0);

      // Acima do limiar (> 100.0) -> aproximada
      const aboveThreshold = DeviceLocation(latitude: 0, longitude: 0, accuracyMeters: 100.1);
      expect(aboveThreshold.isApproximate, isTrue);
      expect(DeviceLocation.isAccuracyApproximate(100.1), isTrue);

      const highInaccurate = DeviceLocation(latitude: 0, longitude: 0, accuracyMeters: 500.0);
      expect(highInaccurate.isApproximate, isTrue);
      expect(DeviceLocation.isAccuracyApproximate(500.0), isTrue);

      // Null safety
      expect(DeviceLocation.isAccuracyApproximate(null), isFalse);
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

    test('retorna error quando a posição obtida ativamente for inválida (0,0)', () async {
      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.always;
      fakePlatform.positionToReturn = Position(
        latitude: 0.0,
        longitude: 0.0,
        timestamp: DateTime.now(),
        accuracy: 0.0,
        altitude: 0.0,
        altitudeAccuracy: 0.0,
        heading: 0.0,
        headingAccuracy: 0.0,
        speed: 0.0,
        speedAccuracy: 0.0,
      );

      final result = await service.getCurrentLocation();

      expect(result, isA<LocationFailure>());
      final failure = result as LocationFailure;
      expect(failure.reason, LocationFailureReason.error);
      expect(failure.message, contains('inválida'));
    });

    test('openAppSettings repassa para o platform handler', () async {
      fakePlatform.openAppSettingsResult = true;
      expect(await service.openAppSettings(), isTrue);

      fakePlatform.openAppSettingsResult = false;
      expect(await service.openAppSettings(), isFalse);
    });
  });
}
