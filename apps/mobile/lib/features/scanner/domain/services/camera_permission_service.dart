/// Estado da permissão de câmera no sistema operacional.
enum CameraPermissionStatus {
  /// Acesso à câmera concedido.
  granted,

  /// Permissão negada temporariamente ou ainda não solicitada.
  denied,

  /// Permissão negada permanentemente; requer liberação nas configurações.
  permanentlyDenied,

  /// Estado indisponível (ex.: plataforma ou canal nativo não registrado).
  unknown,
}

/// Contrato para inspeção, solicitação e abertura de configurações da câmera.
abstract class CameraPermissionService {
  /// Consulta o estado atual da permissão de câmera sem exibir prompt ao usuário.
  Future<CameraPermissionStatus> checkPermissionStatus({
    bool afterRequest = false,
  });

  /// Indica se já houve solicitação nativa prévia concluída para esta permissão.
  Future<bool> hasRequestedPermissionBefore();

  /// Solicita a permissão de câmera ao sistema operacional.
  Future<CameraPermissionStatus> requestPermission();

  /// Abre a tela de configurações do aplicativo no sistema operacional.
  Future<bool> openAppSettings();
}
