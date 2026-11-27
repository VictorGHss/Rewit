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
  LocationAccuracyStatus locationAccuracyResult = LocationAccuracyStatus.precise;
  bool throwOnGetLocationAccuracy = false;
  Position? positionToReturn;
  Position? lastKnownPositionToReturn;
  Exception? exceptionOnGetPosition;
  Exception? exceptionOnGetLastKnownPosition;
  bool openAppSettingsResult = true;
  bool openLocationSettingsResult = true;

  int getCurrentPositionCallCount = 0;
  int getLastKnownPositionCallCount = 0;
  int requestPermissionCallCount = 0;
  int openAppSettingsCallCount = 0;
  int openLocationSettingsCallCount = 0;
  final List<LocationSettings?> capturedLocationSettings = [];
  Future<Position> Function(int callIndex, LocationSettings? settings)? onGetCurrentPosition;

  @override
  Future<bool> isLocationServiceEnabled() async => serviceEnabled;

  @override
  Future<LocationPermission> checkPermission() async => checkPermissionResult;

  @override
  Future<LocationPermission> requestPermission() async {
    requestPermissionCallCount++;
    return requestPermissionResult;
  }

  @override
  Future<LocationAccuracyStatus> getLocationAccuracy() async {
    if (throwOnGetLocationAccuracy) {
      throw UnimplementedError('getLocationAccuracy unavailable');
    }
    return locationAccuracyResult;
  }

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
    capturedLocationSettings.add(locationSettings);
    if (onGetCurrentPosition != null) {
      return onGetCurrentPosition!(getCurrentPositionCallCount, locationSettings);
    }
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
  Future<bool> openAppSettings() async {
    openAppSettingsCallCount++;
    return openAppSettingsResult;
  }

  @override
  Future<bool> openLocationSettings() async {
    openLocationSettingsCallCount++;
    return openLocationSettingsResult;
  }
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
      service = GeolocatorLocationService();
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

    test('cache recente: aceita posição com timestamp recente (< 5 min) sem chamar obtenção ativa', () async {
      final fixedNow = DateTime(2026, 10, 26, 12, 0, 0);
      final customService = GeolocatorLocationService(clock: () => fixedNow);

      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.always;
      fakePlatform.lastKnownPositionToReturn = Position(
        latitude: -25.4284,
        longitude: -49.2733,
        timestamp: fixedNow.subtract(const Duration(minutes: 2)), // 2 minutos atrás (dentro dos 5 min)
        accuracy: 20.0,
        altitude: 900.0,
        altitudeAccuracy: 5.0,
        heading: 0.0,
        headingAccuracy: 0.0,
        speed: 0.0,
        speedAccuracy: 0.0,
      );

      final result = await customService.getCurrentLocation();

      expect(result, isA<LocationSuccess>());
      final success = result as LocationSuccess;
      expect(success.location.latitude, -25.4284);
      expect(success.location.longitude, -49.2733);
      expect(fakePlatform.getLastKnownPositionCallCount, 1);
      expect(fakePlatform.getCurrentPositionCallCount, 0, reason: 'Não deve chamar getCurrentPosition para cache recente');
    });

    test('cache expirado: rejeita posição com mais de 5 minutos e faz fallback para getCurrentPosition', () async {
      final fixedNow = DateTime(2026, 10, 26, 12, 0, 0);
      final customService = GeolocatorLocationService(clock: () => fixedNow);

      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.always;
      // Posição em cache antiga (6 minutos atrás -> expirada)
      fakePlatform.lastKnownPositionToReturn = Position(
        latitude: -25.4284,
        longitude: -49.2733,
        timestamp: fixedNow.subtract(const Duration(minutes: 6)),
        accuracy: 20.0,
        altitude: 900.0,
        altitudeAccuracy: 5.0,
        heading: 0.0,
        headingAccuracy: 0.0,
        speed: 0.0,
        speedAccuracy: 0.0,
      );
      // Posição ativa atual que deve ser obtida via fallback
      fakePlatform.positionToReturn = Position(
        latitude: -25.0994,
        longitude: -50.1583,
        timestamp: fixedNow,
        accuracy: 10.0,
        altitude: 0.0,
        altitudeAccuracy: 0.0,
        heading: 0.0,
        headingAccuracy: 0.0,
        speed: 0.0,
        speedAccuracy: 0.0,
      );

      final result = await customService.getCurrentLocation();

      expect(result, isA<LocationSuccess>());
      final success = result as LocationSuccess;
      expect(success.location.latitude, -25.0994, reason: 'Deve retornar a posição da busca ativa');
      expect(success.location.longitude, -50.1583);
      expect(fakePlatform.getLastKnownPositionCallCount, 1);
      expect(fakePlatform.getCurrentPositionCallCount, 1, reason: 'Deve recorrer à obtenção ativa quando o cache expira');
    });

    test('timestamp incoerente no futuro: rejeita posição no futuro além do skew permitido e faz fallback', () async {
      final fixedNow = DateTime(2026, 10, 26, 12, 0, 0);
      final customService = GeolocatorLocationService(clock: () => fixedNow);

      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.always;
      // Timestamp incoerente 15 minutos no futuro (além da tolerância de 1 min de skew)
      fakePlatform.lastKnownPositionToReturn = Position(
        latitude: -25.4284,
        longitude: -49.2733,
        timestamp: fixedNow.add(const Duration(minutes: 15)),
        accuracy: 20.0,
        altitude: 900.0,
        altitudeAccuracy: 5.0,
        heading: 0.0,
        headingAccuracy: 0.0,
        speed: 0.0,
        speedAccuracy: 0.0,
      );
      fakePlatform.positionToReturn = Position(
        latitude: -25.0994,
        longitude: -50.1583,
        timestamp: fixedNow,
        accuracy: 10.0,
        altitude: 0.0,
        altitudeAccuracy: 0.0,
        heading: 0.0,
        headingAccuracy: 0.0,
        speed: 0.0,
        speedAccuracy: 0.0,
      );

      final result = await customService.getCurrentLocation();

      expect(result, isA<LocationSuccess>());
      final success = result as LocationSuccess;
      expect(success.location.latitude, -25.0994);
      expect(fakePlatform.getCurrentPositionCallCount, 1, reason: 'Deve acionar busca ativa quando timestamp for incoerente');
    });

    test('timestamp incoerente no passado remoto: rejeita posição com ano < 2020 (ex: epoch 1970) e faz fallback', () async {
      final fixedNow = DateTime(2026, 10, 26, 12, 0, 0);
      final customService = GeolocatorLocationService(clock: () => fixedNow);

      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.always;
      fakePlatform.lastKnownPositionToReturn = Position(
        latitude: -25.4284,
        longitude: -49.2733,
        timestamp: DateTime.fromMillisecondsSinceEpoch(0), // 1970-01-01
        accuracy: 20.0,
        altitude: 900.0,
        altitudeAccuracy: 5.0,
        heading: 0.0,
        headingAccuracy: 0.0,
        speed: 0.0,
        speedAccuracy: 0.0,
      );
      fakePlatform.positionToReturn = Position(
        latitude: -25.0994,
        longitude: -50.1583,
        timestamp: fixedNow,
        accuracy: 10.0,
        altitude: 0.0,
        altitudeAccuracy: 0.0,
        heading: 0.0,
        headingAccuracy: 0.0,
        speed: 0.0,
        speedAccuracy: 0.0,
      );

      final result = await customService.getCurrentLocation();

      expect(result, isA<LocationSuccess>());
      expect(fakePlatform.getCurrentPositionCallCount, 1);
    });

    test('ausência de cache: quando getLastKnownPosition for nulo, recorre à obtenção ativa via getCurrentPosition', () async {
      fakePlatform.serviceEnabled = true;
      fakePlatform.checkPermissionResult = LocationPermission.always;
      fakePlatform.lastKnownPositionToReturn = null; // Sem cache no sistema operacional
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
      expect(fakePlatform.getLastKnownPositionCallCount, 1);
      expect(fakePlatform.getCurrentPositionCallCount, 1, reason: 'Deve acionar getCurrentPosition quando não há cache');
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
      expect(fakePlatform.openAppSettingsCallCount, 2);
    });

    test('openLocationSettings repassa para o platform handler e trata exceções', () async {
      fakePlatform.openLocationSettingsResult = true;
      expect(await service.openLocationSettings(), isTrue);

      fakePlatform.openLocationSettingsResult = false;
      expect(await service.openLocationSettings(), isFalse);
      expect(fakePlatform.openLocationSettingsCallCount, 2);
    });

    group('Política separada: Descoberta vs Verificação de Presença (Check-in)', () {
      Position makePos({
        double lat = -25.4384,
        double lng = -49.2849,
        double accuracy = 15.0,
        DateTime? timestamp,
        bool isMocked = false,
      }) =>
          Position(
            latitude: lat,
            longitude: lng,
            timestamp: timestamp ?? DateTime.now(),
            accuracy: accuracy,
            altitude: 0.0,
            altitudeAccuracy: 0.0,
            heading: 0.0,
            headingAccuracy: 0.0,
            speed: 0.0,
            speedAccuracy: 0.0,
            isMocked: isMocked,
          );

      test('descoberta usa precisão média, aceita cache recente e limita timeout em 5s', () async {
        final fixedNow = DateTime(2026, 11, 11, 12, 0, 0);
        final customService = GeolocatorLocationService(clock: () => fixedNow);
        fakePlatform.positionToReturn = makePos(timestamp: fixedNow, accuracy: 40.0);

        final result = await customService.getCurrentLocation(
          purpose: LocationPurpose.discovery,
          timeout: const Duration(seconds: 12),
        );

        expect(result, isA<LocationSuccess>());
        expect(fakePlatform.getLastKnownPositionCallCount, 1);
        expect(fakePlatform.getCurrentPositionCallCount, 1);
        final settings = fakePlatform.capturedLocationSettings.single!;
        expect(settings.accuracy, LocationAccuracy.medium);
        expect(settings.timeLimit, const Duration(seconds: 5));
      });

      test('check-in ignora localização em cache mesmo quando recente', () async {
        final fixedNow = DateTime(2026, 11, 11, 12, 0, 0);
        final customService = GeolocatorLocationService(clock: () => fixedNow);
        fakePlatform.lastKnownPositionToReturn = makePos(
          lat: -25.0000,
          lng: -49.0000,
          accuracy: 8.0,
          timestamp: fixedNow.subtract(const Duration(seconds: 5)),
        );
        fakePlatform.positionToReturn = makePos(
          lat: -25.4384,
          lng: -49.2849,
          accuracy: 11.5,
          timestamp: fixedNow,
        );

        final result = await customService.getCurrentLocation(
          purpose: LocationPurpose.presenceVerification,
        );

        expect(result, isA<LocationSuccess>());
        final success = result as LocationSuccess;
        expect(fakePlatform.getLastKnownPositionCallCount, 0);
        expect(fakePlatform.getCurrentPositionCallCount, 1);
        expect(success.location.latitude, -25.4384);
        expect(success.location.longitude, -49.2849);
        expect(success.location.accuracyMeters, 11.5);
      });

      test('check-in solicita LocationAccuracy.high e utiliza timeout entre 10s e 15s', () async {
        final fixedNow = DateTime(2026, 11, 11, 12, 0, 0);
        final customService = GeolocatorLocationService(clock: () => fixedNow);
        fakePlatform.positionToReturn = makePos(timestamp: fixedNow, accuracy: 18.0);

        await customService.getCurrentLocation(
          purpose: LocationPurpose.presenceVerification,
        );
        await customService.getCurrentLocation(
          purpose: LocationPurpose.presenceVerification,
          timeout: const Duration(seconds: 4),
        );
        await customService.getCurrentLocation(
          purpose: LocationPurpose.presenceVerification,
          timeout: const Duration(seconds: 30),
        );
        await customService.getCurrentLocation(
          purpose: LocationPurpose.presenceVerification,
          timeout: const Duration(seconds: 15),
        );

        expect(fakePlatform.capturedLocationSettings, hasLength(4));
        for (final s in fakePlatform.capturedLocationSettings) {
          expect(s?.accuracy, LocationAccuracy.high);
        }
        expect(
          fakePlatform.capturedLocationSettings[0]?.timeLimit,
          const Duration(seconds: 12),
        );
        expect(
          fakePlatform.capturedLocationSettings[1]?.timeLimit,
          const Duration(seconds: 10),
        );
        expect(
          fakePlatform.capturedLocationSettings[2]?.timeLimit,
          const Duration(seconds: 15),
        );
        expect(
          fakePlatform.capturedLocationSettings[3]?.timeLimit,
          const Duration(seconds: 15),
        );
      });

      test('check-in rejeita coordenadas inválidas e acurácia negativa/NaN', () async {
        final fixedNow = DateTime(2026, 11, 11, 12, 0, 0);
        final customService = GeolocatorLocationService(clock: () => fixedNow);

        final invalidPositions = [
          makePos(lat: 0.0, lng: 0.0, accuracy: 5.0, timestamp: fixedNow),
          makePos(lat: 91.0, lng: -49.28, accuracy: 5.0, timestamp: fixedNow),
          makePos(lat: -25.43, lng: 181.0, accuracy: 5.0, timestamp: fixedNow),
          makePos(lat: double.nan, lng: -49.28, accuracy: 5.0, timestamp: fixedNow),
          makePos(lat: -25.43, lng: -49.28, accuracy: -1.0, timestamp: fixedNow),
          makePos(lat: -25.43, lng: -49.28, accuracy: double.nan, timestamp: fixedNow),
        ];

        for (final invalid in invalidPositions) {
          fakePlatform.positionToReturn = invalid;
          final result = await customService.getCurrentLocation(
            purpose: LocationPurpose.presenceVerification,
          );
          expect(result, isA<LocationFailure>());
          expect((result as LocationFailure).reason, LocationFailureReason.error);
        }
      });

      test('check-in rejeita posição com timestamp antigo (> 1 min), futuro ou remoto', () async {
        final fixedNow = DateTime(2026, 11, 11, 12, 0, 0);
        final customService = GeolocatorLocationService(clock: () => fixedNow);

        final staleTimestamps = [
          fixedNow.subtract(const Duration(minutes: 2)),
          fixedNow.add(const Duration(minutes: 2)),
          DateTime.fromMillisecondsSinceEpoch(0),
        ];

        for (final ts in staleTimestamps) {
          fakePlatform.positionToReturn = makePos(accuracy: 10.0, timestamp: ts);
          final result = await customService.getCurrentLocation(
            purpose: LocationPurpose.presenceVerification,
          );
          expect(result, isA<LocationFailure>());
          final failure = result as LocationFailure;
          expect(failure.reason, LocationFailureReason.error);
          expect(failure.message, contains('desatualizada'));
        }

        // Leitura dentro de 1 minuto é aceita
        fakePlatform.positionToReturn = makePos(
          accuracy: 10.0,
          timestamp: fixedNow.subtract(const Duration(seconds: 45)),
        );
        final valid = await customService.getCurrentLocation(
          purpose: LocationPurpose.presenceVerification,
        );
        expect(valid, isA<LocationSuccess>());
      });

      test('check-in com acurácia <= 100m não dispara segunda tentativa', () async {
        final fixedNow = DateTime(2026, 11, 11, 12, 0, 0);
        final customService = GeolocatorLocationService(clock: () => fixedNow);
        fakePlatform.positionToReturn = makePos(accuracy: 100.0, timestamp: fixedNow);

        final result = await customService.getCurrentLocation(
          purpose: LocationPurpose.presenceVerification,
        );

        expect(result, isA<LocationSuccess>());
        final loc = (result as LocationSuccess).location;
        expect(loc.accuracyMeters, 100.0);
        expect(loc.isApproximate, isFalse);
        expect(fakePlatform.getCurrentPositionCallCount, 1);
      });

      test('check-in com acurácia > 100m em permissão exata tenta no máximo uma vez e adota se melhorar', () async {
        final fixedNow = DateTime(2026, 11, 11, 12, 0, 0);
        final customService = GeolocatorLocationService(clock: () => fixedNow);
        fakePlatform.locationAccuracyResult = LocationAccuracyStatus.precise;
        fakePlatform.onGetCurrentPosition = (call, _) async {
          if (call == 1) {
            return makePos(lat: -25.4380, lng: -49.2840, accuracy: 210.0, timestamp: fixedNow);
          }
          return makePos(lat: -25.4384, lng: -49.2849, accuracy: 14.0, timestamp: fixedNow);
        };

        final result = await customService.getCurrentLocation(
          purpose: LocationPurpose.presenceVerification,
        );

        expect(result, isA<LocationSuccess>());
        final loc = (result as LocationSuccess).location;
        expect(fakePlatform.getCurrentPositionCallCount, 2);
        expect(loc.latitude, -25.4384);
        expect(loc.accuracyMeters, 14.0);
        expect(loc.isApproximate, isFalse);
      });

      test('check-in com acurácia > 100m preserva leitura real se segunda tentativa falhar ou não melhorar', () async {
        final fixedNow = DateTime(2026, 11, 11, 12, 0, 0);
        final customService = GeolocatorLocationService(clock: () => fixedNow);
        fakePlatform.locationAccuracyResult = LocationAccuracyStatus.precise;
        fakePlatform.onGetCurrentPosition = (call, _) async {
          if (call == 1) {
            return makePos(accuracy: 165.0, timestamp: fixedNow);
          }
          throw TimeoutException('Sem fix GNSS na tentativa extra');
        };

        final timeoutRetryResult = await customService.getCurrentLocation(
          purpose: LocationPurpose.presenceVerification,
        );

        expect(timeoutRetryResult, isA<LocationSuccess>());
        final loc1 = (timeoutRetryResult as LocationSuccess).location;
        expect(fakePlatform.getCurrentPositionCallCount, 2, reason: 'Sem loop após 2ª tentativa');
        expect(loc1.accuracyMeters, 165.0);
        expect(loc1.isApproximate, isTrue);

        // Segunda tentativa pior não substitui acurácia melhor nem inventa valor artificial
        fakePlatform.getCurrentPositionCallCount = 0;
        fakePlatform.onGetCurrentPosition = (call, _) async {
          if (call == 1) {
            return makePos(accuracy: 150.0, timestamp: fixedNow);
          }
          return makePos(accuracy: 300.0, timestamp: fixedNow);
        };

        final worseRetryResult = await customService.getCurrentLocation(
          purpose: LocationPurpose.presenceVerification,
        );
        expect(fakePlatform.getCurrentPositionCallCount, 2);
        expect((worseRetryResult as LocationSuccess).location.accuracyMeters, 150.0);
      });

      test('permissão aproximada (reduced) sinaliza isPermissionApproximate e evita tentativa extra', () async {
        final fixedNow = DateTime(2026, 11, 11, 12, 0, 0);
        final customService = GeolocatorLocationService(clock: () => fixedNow);
        fakePlatform.locationAccuracyResult = LocationAccuracyStatus.reduced;
        fakePlatform.positionToReturn = makePos(accuracy: 420.0, timestamp: fixedNow);

        final result = await customService.getCurrentLocation(
          purpose: LocationPurpose.presenceVerification,
        );

        expect(result, isA<LocationSuccess>());
        final loc = (result as LocationSuccess).location;
        expect(fakePlatform.getCurrentPositionCallCount, 1);
        expect(loc.isPermissionApproximate, isTrue);
        expect(loc.isApproximate, isTrue);
        expect(loc.accuracyMeters, 420.0);

        // Mesmo se acurácia reportada pelo SO for <= 100m, isPermissionApproximate mantém isApproximate == true
        fakePlatform.getCurrentPositionCallCount = 0;
        fakePlatform.positionToReturn = makePos(accuracy: 50.0, timestamp: fixedNow);
        final lowAccWithReducedPerm = await customService.getCurrentLocation(
          purpose: LocationPurpose.presenceVerification,
        );
        final loc2 = (lowAccWithReducedPerm as LocationSuccess).location;
        expect(loc2.accuracyMeters, 50.0, reason: 'Não altera acurácia real recebida');
        expect(loc2.isPermissionApproximate, isTrue);
        expect(loc2.isApproximate, isTrue);
      });

      test('localização simulada do emulador (isMocked: true) continua permitida', () async {
        final fixedNow = DateTime(2026, 11, 11, 12, 0, 0);
        final customService = GeolocatorLocationService(clock: () => fixedNow);
        fakePlatform.locationAccuracyResult = LocationAccuracyStatus.precise;
        fakePlatform.positionToReturn = makePos(
          lat: -25.4384,
          lng: -49.2849,
          accuracy: 6.5,
          timestamp: fixedNow,
          isMocked: true,
        );

        final result = await customService.getCurrentLocation(
          purpose: LocationPurpose.presenceVerification,
        );

        expect(result, isA<LocationSuccess>());
        final loc = (result as LocationSuccess).location;
        expect(loc.latitude, -25.4384);
        expect(loc.longitude, -49.2849);
        expect(loc.accuracyMeters, 6.5);
        expect(loc.isApproximate, isFalse);
      });

      test('controla concorrência / timeout tardio via Completer no getCurrentPosition', () async {
        final started = Completer<void>();
        final completer = Completer<Position>();
        fakePlatform.onGetCurrentPosition = (_, __) {
          started.complete();
          return completer.future;
        };

        final future = service.getCurrentLocation(
          purpose: LocationPurpose.presenceVerification,
        );
        await started.future;
        completer.completeError(TimeoutException('GPS frio excedeu tempo'));

        final result = await future;
        expect(result, isA<LocationFailure>());
        expect((result as LocationFailure).reason, LocationFailureReason.timeout);
      });

      group('Bug 03 — Descoberta sem cache recente e fallback limitado de alta precisão', () {
        test('cache recente (<= 5 min) é reutilizado imediatamente sem captura ativa', () async {
          final fixedNow = DateTime(2026, 11, 14, 15, 0, 0);
          final customService = GeolocatorLocationService(clock: () => fixedNow);
          fakePlatform.lastKnownPositionToReturn = makePos(
            lat: -25.10,
            lng: -50.16,
            accuracy: 60.0,
            timestamp: fixedNow.subtract(const Duration(minutes: 4, seconds: 59)),
          );

          final result = await customService.getCurrentLocation(
            purpose: LocationPurpose.discovery,
          );

          expect(result, isA<LocationSuccess>());
          final loc = (result as LocationSuccess).location;
          expect(loc.latitude, -25.10);
          expect(loc.longitude, -50.16);
          expect(fakePlatform.getLastKnownPositionCallCount, 1);
          expect(fakePlatform.getCurrentPositionCallCount, 0);
        });

        test('cache expirado (> 5 min) é ignorado e captura leve (medium) vence em 1 chamada', () async {
          final fixedNow = DateTime(2026, 11, 14, 15, 0, 0);
          final customService = GeolocatorLocationService(clock: () => fixedNow);
          fakePlatform.lastKnownPositionToReturn = makePos(
            lat: -25.00,
            lng: -50.00,
            accuracy: 15.0,
            timestamp: fixedNow.subtract(const Duration(minutes: 6)),
          );
          fakePlatform.positionToReturn = makePos(
            lat: -25.095,
            lng: -50.162,
            accuracy: 42.0,
            timestamp: fixedNow,
          );

          final result = await customService.getCurrentLocation(
            purpose: LocationPurpose.discovery,
            timeout: const Duration(seconds: 5),
          );

          expect(result, isA<LocationSuccess>());
          final loc = (result as LocationSuccess).location;
          expect(loc.latitude, -25.095);
          expect(loc.longitude, -50.162);
          expect(fakePlatform.getCurrentPositionCallCount, 1);
          expect(fakePlatform.capturedLocationSettings.single!.accuracy, LocationAccuracy.medium);
          expect(fakePlatform.capturedLocationSettings.single!.timeLimit, const Duration(seconds: 5));
        });

        test('sem cache + timeout na tentativa leve executa 1 fallback high controlado e sucede', () async {
          final fixedNow = DateTime(2026, 11, 14, 15, 0, 0);
          final customService = GeolocatorLocationService(clock: () => fixedNow);
          fakePlatform.lastKnownPositionToReturn = null;
          fakePlatform.onGetCurrentPosition = (call, _) async {
            if (call == 1) {
              throw TimeoutException('Balanced provider sem fix em 5s');
            }
            return makePos(
              lat: -25.098,
              lng: -50.163,
              accuracy: 18.0,
              timestamp: fixedNow,
            );
          };

          final result = await customService.getCurrentLocation(
            purpose: LocationPurpose.discovery,
            timeout: const Duration(seconds: 5),
          );

          expect(result, isA<LocationSuccess>());
          final loc = (result as LocationSuccess).location;
          expect(loc.latitude, -25.098);
          expect(loc.longitude, -50.163);
          expect(loc.accuracyMeters, 18.0);
          expect(fakePlatform.getCurrentPositionCallCount, 2);
          expect(fakePlatform.capturedLocationSettings[0]!.accuracy, LocationAccuracy.medium);
          expect(fakePlatform.capturedLocationSettings[0]!.timeLimit, const Duration(seconds: 5));
          expect(fakePlatform.capturedLocationSettings[1]!.accuracy, LocationAccuracy.high);
          expect(
            fakePlatform.capturedLocationSettings[1]!.timeLimit,
            lessThanOrEqualTo(const Duration(seconds: 4)),
          );
        });

        test('sem cache + falha de provider na tentativa leve executa fallback high único e sucede', () async {
          final fixedNow = DateTime(2026, 11, 14, 15, 0, 0);
          final customService = GeolocatorLocationService(clock: () => fixedNow);
          fakePlatform.lastKnownPositionToReturn = null;
          fakePlatform.onGetCurrentPosition = (call, _) async {
            if (call == 1) {
              throw Exception('Network location provider unavailable');
            }
            return makePos(
              lat: -25.091,
              lng: -50.159,
              accuracy: 25.0,
              timestamp: fixedNow,
            );
          };

          final result = await customService.getCurrentLocation(
            purpose: LocationPurpose.discovery,
          );

          expect(result, isA<LocationSuccess>());
          expect(fakePlatform.getCurrentPositionCallCount, 2);
          expect(fakePlatform.capturedLocationSettings[0]!.accuracy, LocationAccuracy.medium);
          expect(fakePlatform.capturedLocationSettings[1]!.accuracy, LocationAccuracy.high);
        });

        test('quando fallback também falha ou expira, falha limpo sem entrar em loop', () async {
          final fixedNow = DateTime(2026, 11, 14, 15, 0, 0);
          final customService = GeolocatorLocationService(clock: () => fixedNow);
          fakePlatform.lastKnownPositionToReturn = null;
          fakePlatform.onGetCurrentPosition = (call, _) async {
            if (call == 1) {
              throw Exception('Sem fix network');
            }
            throw TimeoutException('GNSS frio também expirou');
          };

          final result = await customService.getCurrentLocation(
            purpose: LocationPurpose.discovery,
          );

          expect(result, isA<LocationFailure>());
          expect((result as LocationFailure).reason, LocationFailureReason.timeout);
          expect(fakePlatform.getCurrentPositionCallCount, 2);
        });

        test('orçamento explícito curto esgotado impede disparo do fallback (cancelamento/teto)', () async {
          final customService = GeolocatorLocationService();
          fakePlatform.lastKnownPositionToReturn = null;
          fakePlatform.onGetCurrentPosition = (call, _) async {
            await Future<void>.delayed(const Duration(milliseconds: 35));
            throw TimeoutException('Timeout dentro do orçamento reduzido');
          };

          final result = await customService.getCurrentLocation(
            purpose: LocationPurpose.discovery,
            timeout: const Duration(milliseconds: 20),
          );

          expect(result, isA<LocationFailure>());
          expect((result as LocationFailure).reason, LocationFailureReason.timeout);
          expect(fakePlatform.getCurrentPositionCallCount, 1);
        });

        test('descoberta com permissão aproximada continua sucedendo após fallback', () async {
          final fixedNow = DateTime(2026, 11, 14, 15, 0, 0);
          final customService = GeolocatorLocationService(clock: () => fixedNow);
          fakePlatform.locationAccuracyResult = LocationAccuracyStatus.reduced;
          fakePlatform.onGetCurrentPosition = (call, _) async {
            if (call == 1) {
              throw TimeoutException('Timeout inicial');
            }
            return makePos(
              lat: -25.10,
              lng: -50.16,
              accuracy: 1200.0,
              timestamp: fixedNow,
            );
          };

          final result = await customService.getCurrentLocation(
            purpose: LocationPurpose.discovery,
          );

          expect(result, isA<LocationSuccess>());
          final loc = (result as LocationSuccess).location;
          expect(loc.accuracyMeters, 1200.0);
          expect(fakePlatform.getCurrentPositionCallCount, 2);
        });

        test('descoberta com serviço de localização desligado falha imediatamente sem tentar fallback', () async {
          fakePlatform.serviceEnabled = false;

          final result = await service.getCurrentLocation(
            purpose: LocationPurpose.discovery,
          );

          expect(result, isA<LocationFailure>());
          expect((result as LocationFailure).reason, LocationFailureReason.serviceDisabled);
          expect(fakePlatform.getLastKnownPositionCallCount, 0);
          expect(fakePlatform.getCurrentPositionCallCount, 0);
        });
      });

      group('checkReadiness sem prompt nativo', () {
        test('retorna serviceDisabled quando serviço está desligado sem pedir permissão', () async {
          fakePlatform.serviceEnabled = false;
          fakePlatform.checkPermissionResult = LocationPermission.denied;

          final readiness = await service.checkReadiness(
            purpose: LocationPurpose.presenceVerification,
          );

          expect(readiness, isA<LocationNotReady>());
          expect((readiness as LocationNotReady).reason, LocationFailureReason.serviceDisabled);
          expect(fakePlatform.requestPermissionCallCount, 0);
        });

        test('retorna permissionDenied quando permissão está denied sem abrir diálogo nativo', () async {
          fakePlatform.serviceEnabled = true;
          fakePlatform.checkPermissionResult = LocationPermission.denied;

          final readiness = await service.checkReadiness(
            purpose: LocationPurpose.discovery,
          );

          expect(readiness, isA<LocationNotReady>());
          expect((readiness as LocationNotReady).reason, LocationFailureReason.permissionDenied);
          expect(fakePlatform.requestPermissionCallCount, 0);
        });

        test('retorna permissionDeniedForever quando permissão está bloqueada permanentemente', () async {
          fakePlatform.serviceEnabled = true;
          fakePlatform.checkPermissionResult = LocationPermission.deniedForever;

          final readiness = await service.checkReadiness(
            purpose: LocationPurpose.presenceVerification,
          );

          expect(readiness, isA<LocationNotReady>());
          expect((readiness as LocationNotReady).reason, LocationFailureReason.permissionDeniedForever);
          expect(fakePlatform.requestPermissionCallCount, 0);
        });

        test('identifica permissão aproximada para presenceVerification e ignora redução em discovery', () async {
          fakePlatform.serviceEnabled = true;
          fakePlatform.checkPermissionResult = LocationPermission.whileInUse;
          fakePlatform.locationAccuracyResult = LocationAccuracyStatus.reduced;

          final presence = await service.checkReadiness(
            purpose: LocationPurpose.presenceVerification,
          );
          final discovery = await service.checkReadiness(
            purpose: LocationPurpose.discovery,
          );

          expect(presence, isA<LocationReady>());
          expect((presence as LocationReady).isPermissionApproximate, isTrue);
          expect(discovery, isA<LocationReady>());
          expect((discovery as LocationReady).isPermissionApproximate, isFalse);
          expect(fakePlatform.requestPermissionCallCount, 0);
        });

        test('preserva permissionDeniedForever quando checkPermission do Android retorna denied após requestPermission deniedForever (Cenários A e D)', () async {
          fakePlatform.serviceEnabled = true;
          fakePlatform.checkPermissionResult = LocationPermission.denied;
          fakePlatform.requestPermissionResult = LocationPermission.deniedForever;

          final initialResult = await service.getCurrentLocation(
            purpose: LocationPurpose.discovery,
          );
          expect(initialResult, isA<LocationFailure>());
          expect(
            (initialResult as LocationFailure).reason,
            LocationFailureReason.permissionDeniedForever,
          );
          expect(fakePlatform.requestPermissionCallCount, 1);

          // Simula comportamento real do Android: ContextCompat.checkSelfPermission devolve denied
          // mesmo em negação permanente ao retornar das configurações (e em múltiplas reentradas).
          for (var i = 0; i < 3; i++) {
            final readiness = await service.checkReadiness(
              purpose: LocationPurpose.discovery,
            );
            expect(readiness, isA<LocationNotReady>());
            expect(
              (readiness as LocationNotReady).reason,
              LocationFailureReason.permissionDeniedForever,
            );
          }
          expect(fakePlatform.requestPermissionCallCount, 1);
          expect(fakePlatform.getCurrentPositionCallCount, 0);
        });

        test('limpa estado de negação permanente após permissão concedida em checkReadiness (Cenário B)', () async {
          fakePlatform.serviceEnabled = true;
          fakePlatform.checkPermissionResult = LocationPermission.denied;
          fakePlatform.requestPermissionResult = LocationPermission.deniedForever;

          final initialResult = await service.getCurrentLocation(
            purpose: LocationPurpose.presenceVerification,
          );
          expect(
            (initialResult as LocationFailure).reason,
            LocationFailureReason.permissionDeniedForever,
          );

          // Usuário concede permissão nas configurações
          fakePlatform.checkPermissionResult = LocationPermission.whileInUse;
          fakePlatform.locationAccuracyResult = LocationAccuracyStatus.precise;
          final grantedReadiness = await service.checkReadiness(
            purpose: LocationPurpose.presenceVerification,
          );
          expect(grantedReadiness, isA<LocationReady>());

          // Se for revogada posteriormente sem requestPermission, não mantém deniedForever obsoleto
          fakePlatform.checkPermissionResult = LocationPermission.denied;
          final revokedReadiness = await service.checkReadiness(
            purpose: LocationPurpose.presenceVerification,
          );
          expect(revokedReadiness, isA<LocationNotReady>());
          expect(
            (revokedReadiness as LocationNotReady).reason,
            LocationFailureReason.permissionDenied,
          );
        });

        test('mantém negação temporária como permissionDenied sem promover para deniedForever (Cenário C)', () async {
          fakePlatform.serviceEnabled = true;
          fakePlatform.checkPermissionResult = LocationPermission.denied;
          fakePlatform.requestPermissionResult = LocationPermission.denied;

          final initialResult = await service.getCurrentLocation(
            purpose: LocationPurpose.discovery,
          );
          expect(
            (initialResult as LocationFailure).reason,
            LocationFailureReason.permissionDenied,
          );

          final readiness = await service.checkReadiness(
            purpose: LocationPurpose.discovery,
          );
          expect(readiness, isA<LocationNotReady>());
          expect(
            (readiness as LocationNotReady).reason,
            LocationFailureReason.permissionDenied,
          );
          expect(fakePlatform.requestPermissionCallCount, 1);
        });
      });
    });
  });
}
