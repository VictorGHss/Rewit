import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Os manifests de `src/debug` e `src/profile` só entram nos builds de desenvolvimento: no release, a permissão
/// precisa estar no manifest de `src/main`, ou o APK/AAB publicado não alcança a API.
void main() {
  const mainManifestPath = 'android/app/src/main/AndroidManifest.xml';
  final internetPermission = RegExp(
    r'''<uses-permission\s+android:name\s*=\s*["']android\.permission\.INTERNET["']''',
  );

  String withoutComments(String xml) => xml.replaceAll(RegExp(r'<!--.*?-->', dotAll: true), '');

  test('manifest principal (usado no release) declara a permissão INTERNET para alcançar a API', () {
    final manifest = File(mainManifestPath);
    expect(manifest.existsSync(), isTrue, reason: '$mainManifestPath não encontrado');

    expect(internetPermission.hasMatch(withoutComments(manifest.readAsStringSync())), isTrue,
        reason: 'sem android.permission.INTERNET no manifest principal, o build release não acessa a API');
  });

  test('manifest principal registra o retorno do Sign in with Apple (signinwithapple://callback)', () {
    final manifest = withoutComments(File(mainManifestPath).readAsStringSync());

    expect(manifest, contains('com.aboutyou.dart_packages.sign_in_with_apple.SignInWithAppleCallback'));
    expect(manifest, contains('android:scheme="signinwithapple"'));
    expect(manifest, contains('android:path="callback"'));
  });
}
