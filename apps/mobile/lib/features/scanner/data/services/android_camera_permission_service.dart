import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../../domain/services/camera_permission_service.dart';

/// Implementação Android via [MethodChannel] para consultar e solicitar a
/// permissão de câmera refletindo o estado real do sistema operacional.
class AndroidCameraPermissionService implements CameraPermissionService {
  @visibleForTesting
  static const String channelName = 'app.rewit.mobile/camera_permission';

  static const MethodChannel _defaultChannel = MethodChannel(channelName);

  final MethodChannel _channel;

  const AndroidCameraPermissionService({MethodChannel? channel})
      : _channel = channel ?? _defaultChannel;

  @override
  Future<CameraPermissionStatus> checkPermissionStatus({
    bool afterRequest = false,
  }) async {
    try {
      final rawStatus = await _channel.invokeMethod<String>(
        'checkPermissionStatus',
        {'afterRequest': afterRequest},
      );
      return _parseStatus(rawStatus);
    } on MissingPluginException {
      return CameraPermissionStatus.unknown;
    } on PlatformException {
      return CameraPermissionStatus.unknown;
    }
  }

  @override
  Future<bool> hasRequestedPermissionBefore() async {
    try {
      final hasRequested = await _channel.invokeMethod<bool>(
        'hasRequestedPermissionBefore',
      );
      return hasRequested ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  @override
  Future<CameraPermissionStatus> requestPermission() async {
    try {
      final rawStatus = await _channel.invokeMethod<String>(
        'requestPermission',
      );
      return _parseStatus(rawStatus);
    } on MissingPluginException {
      return CameraPermissionStatus.unknown;
    } on PlatformException {
      return CameraPermissionStatus.unknown;
    }
  }

  @override
  Future<bool> openAppSettings() async {
    try {
      final opened = await _channel.invokeMethod<bool>('openAppSettings');
      return opened ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  CameraPermissionStatus _parseStatus(String? rawStatus) {
    return switch (rawStatus) {
      'granted' => CameraPermissionStatus.granted,
      'denied' => CameraPermissionStatus.denied,
      'permanentlyDenied' => CameraPermissionStatus.permanentlyDenied,
      _ => CameraPermissionStatus.unknown,
    };
  }
}
