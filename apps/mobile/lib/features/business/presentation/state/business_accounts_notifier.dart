import 'package:flutter/foundation.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import '../../domain/entities/business_entities.dart';
import '../../domain/repositories/business_repository.dart';

class BusinessAccountsNotifier extends ChangeNotifier {
  final BusinessRepository repository;

  List<BusinessAccount> _accounts = [];
  final Map<String, List<PlaceClaim>> _claimsByAccount = {};
  bool _isLoading = false;
  bool _isCreatingAccount = false;
  bool _isSubmittingClaim = false;
  String? _errorMessage;
  String? _successMessage;

  BusinessAccountsNotifier({required this.repository});

  List<BusinessAccount> get accounts => _accounts;
  Map<String, List<PlaceClaim>> get claimsByAccount => _claimsByAccount;
  bool get isLoading => _isLoading;
  bool get isCreatingAccount => _isCreatingAccount;
  bool get isSubmittingClaim => _isSubmittingClaim;
  String? get errorMessage => _errorMessage;
  String? get successMessage => _successMessage;

  void clearMessages() {
    _errorMessage = null;
    _successMessage = null;
    notifyListeners();
  }

  Future<void> loadMyAccounts() async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      _accounts = await repository.getMyBusinessAccounts();
      // Carrega solicitações para cada conta
      for (final account in _accounts) {
        try {
          final claims = await repository.getBusinessPlaceClaims(account.id);
          _claimsByAccount[account.id] = claims;
        } catch (_) {
          _claimsByAccount[account.id] = [];
        }
      }
    } on ApiException catch (e) {
      _errorMessage = e.detail;
    } on NetworkException catch (e) {
      _errorMessage = e.message;
    } catch (_) {
      _errorMessage = 'Não foi possível carregar as contas comerciais.';
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  Future<void> loadClaimsForAccount(String businessAccountId) async {
    try {
      final claims = await repository.getBusinessPlaceClaims(businessAccountId);
      _claimsByAccount[businessAccountId] = claims;
      notifyListeners();
    } catch (_) {
      // Falha isolada ao listar solicitações não quebra a tela principal
    }
  }

  Future<bool> createAccount({
    required String corporateName,
    required String taxId,
  }) async {
    _isCreatingAccount = true;
    _errorMessage = null;
    _successMessage = null;
    notifyListeners();

    try {
      final newAccount = await repository.createBusinessAccount(
        corporateName: corporateName,
        taxId: taxId,
      );
      _accounts = [..._accounts, newAccount];
      _claimsByAccount[newAccount.id] = [];
      _successMessage = 'Conta comercial "${newAccount.corporateName}" criada com sucesso!';
      return true;
    } on ApiException catch (e) {
      if (e.errorCode == 'BUSINESS_TAX_ID_ALREADY_EXISTS' || e.isConflict) {
        _errorMessage = 'Este documento fiscal (CNPJ) já está cadastrado em outra conta comercial.';
      } else {
        _errorMessage = e.detail;
      }
      return false;
    } on NetworkException catch (e) {
      _errorMessage = e.message;
      return false;
    } catch (_) {
      _errorMessage = 'Falha ao cadastrar conta comercial. Verifique os dados e tente novamente.';
      return false;
    } finally {
      _isCreatingAccount = false;
      notifyListeners();
    }
  }

  Future<PlaceClaim?> requestPlaceClaim({
    required String businessAccountId,
    required String placeId,
    required String evidenceDescription,
  }) async {
    _isSubmittingClaim = true;
    _errorMessage = null;
    _successMessage = null;
    notifyListeners();

    try {
      final claim = await repository.requestPlaceClaim(
        businessAccountId: businessAccountId,
        placeId: placeId,
        evidenceDescription: evidenceDescription,
      );

      final current = _claimsByAccount[businessAccountId] ?? [];
      _claimsByAccount[businessAccountId] = [claim, ...current];
      _successMessage = 'Solicitação de reivindicação enviada com sucesso! Ela será avaliada pela moderação.';
      return claim;
    } on ApiException catch (e) {
      if (e.errorCode == 'PLACE_ALREADY_CLAIMED') {
        _errorMessage = 'Este local já foi reivindicado e está vinculado a outra empresa.';
      } else if (e.errorCode == 'PLACE_CLAIM_ALREADY_PENDING') {
        _errorMessage = 'Já existe uma solicitação de reivindicação em análise para este local.';
      } else if (e.errorCode == 'BUSINESS_ACCOUNT_REJECTED') {
        _errorMessage = 'Esta conta comercial está rejeitada e não pode reivindicar locais.';
      } else {
        _errorMessage = e.detail;
      }
      return null;
    } on NetworkException catch (e) {
      _errorMessage = e.message;
      return null;
    } catch (_) {
      _errorMessage = 'Ocorreu um erro ao enviar a solicitação. Tente novamente.';
      return null;
    } finally {
      _isSubmittingClaim = false;
      notifyListeners();
    }
  }
}
