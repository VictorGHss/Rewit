import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/features/scanner/data/services/android_camera_permission_service.dart';
import 'package:rewit_mobile/features/scanner/domain/services/camera_permission_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel(AndroidCameraPermissionService.channelName);
  const service = AndroidCameraPermissionService(channel: channel);

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  group('AndroidCameraPermissionService', () {
    test('checkPermissionStatus interpreta estados e repassa afterRequest', () async {
      final calls = <MethodCall>[];
      var response = 'granted';

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return response;
      });

      expect(
        await service.checkPermissionStatus(),
        equals(CameraPermissionStatus.granted),
      );
      expect(calls.last.method, equals('checkPermissionStatus'));
      expect(calls.last.arguments, equals({'afterRequest': false}));

      response = 'denied';
      expect(
        await service.checkPermissionStatus(afterRequest: true),
        equals(CameraPermissionStatus.denied),
      );
      expect(calls.last.arguments, equals({'afterRequest': true}));

      response = 'permanentlyDenied';
      expect(
        await service.checkPermissionStatus(),
        equals(CameraPermissionStatus.permanentlyDenied),
      );

      response = 'unexpected_value';
      expect(
        await service.checkPermissionStatus(),
        equals(CameraPermissionStatus.unknown),
      );
    });

    test('requestPermission interpreta granted, denied e permanentlyDenied', () async {
      var response = 'denied';

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'requestPermission') {
          return response;
        }
        return null;
      });

      expect(
        await service.requestPermission(),
        equals(CameraPermissionStatus.denied),
      );

      response = 'permanentlyDenied';
      expect(
        await service.requestPermission(),
        equals(CameraPermissionStatus.permanentlyDenied),
      );

      response = 'granted';
      expect(
        await service.requestPermission(),
        equals(CameraPermissionStatus.granted),
      );
    });

    test('openAppSettings retorna resultado booleano do canal nativo', () async {
      var opened = true;

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'openAppSettings') {
          return opened;
        }
        return null;
      });

      expect(await service.openAppSettings(), isTrue);
      opened = false;
      expect(await service.openAppSettings(), isFalse);
    });

    test('hasRequestedPermissionBefore consulta histórico nativo com fallback false para null', () async {
      bool? response = true;

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'hasRequestedPermissionBefore') {
          return response;
        }
        return null;
      });

      expect(await service.hasRequestedPermissionBefore(), isTrue);
      response = false;
      expect(await service.hasRequestedPermissionBefore(), isFalse);
      response = null;
      expect(await service.hasRequestedPermissionBefore(), isFalse);
    });

    test('degrada com segurança em MissingPluginException e PlatformException', () async {
      // Sem handler registrado -> MissingPluginException
      expect(
        await service.checkPermissionStatus(),
        equals(CameraPermissionStatus.unknown),
      );
      expect(await service.hasRequestedPermissionBefore(), isFalse);
      expect(
        await service.requestPermission(),
        equals(CameraPermissionStatus.unknown),
      );
      expect(await service.openAppSettings(), isFalse);

      // Com PlatformException no canal nativo
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
        throw PlatformException(code: 'ERR', message: 'falha nativa');
      });

      expect(
        await service.checkPermissionStatus(),
        equals(CameraPermissionStatus.unknown),
      );
      expect(await service.hasRequestedPermissionBefore(), isFalse);
      expect(
        await service.requestPermission(),
        equals(CameraPermissionStatus.unknown),
      );
      expect(await service.openAppSettings(), isFalse);
    });
  });
}
