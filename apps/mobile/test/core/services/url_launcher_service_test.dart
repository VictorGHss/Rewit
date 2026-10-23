import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/services/url_launcher_service.dart';

void main() {
  group('UrlLauncherService Tests', () {
    const service = UrlLauncherServiceImpl();

    test('launchWebUrl recusa esquemas proibidos ou não http/https', () async {
      expect(
        await service.launchWebUrl('javascript:alert(1)'),
        LaunchResult.invalidScheme,
      );
      expect(
        await service.launchWebUrl('ftp://ftp.example.com/file'),
        LaunchResult.invalidScheme,
      );
      expect(
        await service.launchWebUrl('file:///etc/passwd'),
        LaunchResult.invalidScheme,
      );
      expect(
        await service.launchWebUrl('custom://app.open'),
        LaunchResult.invalidScheme,
      );
    });

    test('launchWebUrl recusa strings vazias ou URLs sem host', () async {
      expect(
        await service.launchWebUrl(''),
        LaunchResult.invalidFormat,
      );
      expect(
        await service.launchWebUrl('   '),
        LaunchResult.invalidFormat,
      );
      expect(
        await service.launchWebUrl('https://'),
        LaunchResult.invalidFormat,
      );
    });

    test('launchPhone recusa números vazios ou sem dígitos', () async {
      expect(
        await service.launchPhone(''),
        LaunchResult.invalidFormat,
      );
      expect(
        await service.launchPhone('   '),
        LaunchResult.invalidFormat,
      );
      expect(
        await service.launchPhone('+++'),
        LaunchResult.invalidFormat,
      );
      expect(
        await service.launchPhone('(--) . -'),
        LaunchResult.invalidFormat,
      );
    });
  });
}
