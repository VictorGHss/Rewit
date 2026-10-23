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
}
