import 'package:flutter/foundation.dart';
import '../../../../core/error/api_exception.dart';
import '../../domain/entities/place_detail.dart';
import '../../domain/repositories/place_commercial_repository.dart';

/// Notifier para gerenciamento e edição do perfil comercial e contatos públicos do estabelecimento (F2.4).
class PlaceBusinessProfileNotifier extends ChangeNotifier {
  final PlaceCommercialRepository repository;

  PlaceDetail? _profile;
  bool _isLoading = false;
  bool _isSaving = false;
  String? _errorMessage;
  String? _successMessage;
  bool _disposed = false;

  PlaceBusinessProfileNotifier({required this.repository});

  PlaceDetail? get profile => _profile;
  bool get isLoading => _isLoading;
  bool get isSaving => _isSaving;
  String? get errorMessage => _errorMessage;
  String? get successMessage => _successMessage;

  static final RegExp _phoneFormat = RegExp(r'^\+?[0-9(][0-9 ().-]*[0-9]$');
  static final RegExp _controlOrSpace = RegExp(r'[\s\x00-\x1F\x7F]');

  /// Valida a descrição institucional (máximo de 2000 caracteres).
  static String? validateDescription(String? value) {
    if (value != null && value.length > 2000) {
      return 'A descrição institucional deve ter no máximo 2000 caracteres.';
    }
    return null;
  }

  /// Valida número de telefone público (de 8 a 15 dígitos com separadores válidos).
  static String? validatePhone(String? value) {
    if (value == null || value.trim().isEmpty) return null;
    final normalized = value.trim().replaceAll(RegExp(r'\s+'), ' ');
    if (normalized.length > 32 || !_phoneFormat.hasMatch(normalized)) {
      return 'Telefone inválido: use de 8 a 15 dígitos, com "+" opcional no início e espaços, parênteses, pontos ou hífens como separadores.';
    }
    final digits = normalized.replaceAll(RegExp(r'[^0-9]'), '').length;
    if (digits < 8 || digits > 15) {
      return 'Telefone inválido: use de 8 a 15 dígitos, com "+" opcional no início e espaços, parênteses, pontos ou hífens como separadores.';
    }
    return null;
  }

  /// Valida links externos absolutos (http/https, sem espaços ou credenciais).
  static String? validateUrl(String? value, String label) {
    if (value == null || value.trim().isEmpty) return null;
    final trimmed = value.trim();
    if (trimmed.length > 500) {
      return 'Endereço $label inválido: use um link http ou https de até 500 caracteres.';
    }
    if (_controlOrSpace.hasMatch(trimmed)) {
      return 'Endereço $label inválido: use um link http ou https de até 500 caracteres.';
    }
    final uri = Uri.tryParse(trimmed);
    if (uri == null) {
      return 'Endereço $label inválido: use um link http ou https de até 500 caracteres.';
    }
    final scheme = uri.scheme.toLowerCase();
    if (scheme != 'http' && scheme != 'https') {
      return 'Endereço $label inválido: use um link http ou https de até 500 caracteres.';
    }
    if (uri.host.isEmpty || uri.userInfo.isNotEmpty) {
      return 'Endereço $label inválido: use um link http ou https de até 500 caracteres.';
    }
    return null;
  }

  /// Carrega os dados atuais do estabelecimento.
  Future<void> loadProfile(String placeId) async {
    if (_isLoading) return;
    _isLoading = true;
    _errorMessage = null;
    _successMessage = null;
    _notifySafely();

    try {
      _profile = await repository.getBusinessProfile(placeId);
    } catch (e) {
      _errorMessage = e is ApiException
          ? e.detail
          : 'Falha ao carregar perfil comercial do estabelecimento.';
    } finally {
      _isLoading = false;
      _notifySafely();
    }
  }

  /// Atualiza o perfil comercial e os contatos públicos.
  Future<bool> saveProfile({
    required String placeId,
    required String businessAccountId,
    String? description,
    String? phone,
    String? websiteUrl,
    String? instagramUrl,
    String? facebookUrl,
  }) async {
    if (_isSaving) return false;

    final descErr = validateDescription(description);
    if (descErr != null) {
      _errorMessage = descErr;
      _notifySafely();
      return false;
    }

    final phoneErr = validatePhone(phone);
    if (phoneErr != null) {
      _errorMessage = phoneErr;
      _notifySafely();
      return false;
    }

    final webErr = validateUrl(websiteUrl, 'do site');
    if (webErr != null) {
      _errorMessage = webErr;
      _notifySafely();
      return false;
    }

    final instaErr = validateUrl(instagramUrl, 'do Instagram');
    if (instaErr != null) {
      _errorMessage = instaErr;
      _notifySafely();
      return false;
    }

    final fbErr = validateUrl(facebookUrl, 'do Facebook');
    if (fbErr != null) {
      _errorMessage = fbErr;
      _notifySafely();
      return false;
    }

    _isSaving = true;
    _errorMessage = null;
    _successMessage = null;
    _notifySafely();

    try {
      final updated = await repository.updateBusinessProfile(
        placeId: placeId,
        businessAccountId: businessAccountId,
        description: description,
        phone: phone,
        websiteUrl: websiteUrl,
        instagramUrl: instagramUrl,
        facebookUrl: facebookUrl,
      );
      _profile = updated;
      _successMessage = 'Perfil comercial atualizado com sucesso.';
      return true;
    } on ApiException catch (e) {
      if (e.statusCode == 400) {
        _errorMessage = e.detail.isNotEmpty ? e.detail : 'Dados do perfil comercial inválidos.';
      } else if (e.statusCode == 401) {
        _errorMessage = 'Sessão expirada. Faça login novamente.';
      } else if (e.statusCode == 403) {
        _errorMessage = 'Você não possui permissão para gerenciar este estabelecimento.';
      } else if (e.statusCode == 404) {
        _errorMessage = 'Estabelecimento não encontrado.';
      } else {
        _errorMessage = e.detail.isNotEmpty ? e.detail : 'Erro ao atualizar perfil comercial.';
      }
      return false;
    } catch (_) {
      _errorMessage = 'Falha de comunicação ao salvar o perfil comercial.';
      return false;
    } finally {
      _isSaving = false;
      _notifySafely();
    }
  }

  void _notifySafely() {
    if (!_disposed) {
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
