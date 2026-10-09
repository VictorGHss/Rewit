import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/business/domain/entities/business_entities.dart';
import 'package:rewit_mobile/features/business/domain/repositories/business_repository.dart';
import 'package:rewit_mobile/features/business/presentation/screens/my_business_accounts_screen.dart';
import 'package:rewit_mobile/features/business/presentation/state/business_accounts_notifier.dart';
import 'package:rewit_mobile/features/business/presentation/widgets/claim_place_bottom_sheet.dart';

class _FakeBusinessRepository implements BusinessRepository {
  List<BusinessAccount> accountsToReturn = [];
  Map<String, List<PlaceClaim>> claimsToReturn = {};
  Exception? errorToThrow;
  PlaceClaim? claimToReturn;

  String? lastCreatedCorporateName;
  String? lastCreatedTaxId;
  String? lastRequestedBusinessAccountId;
  String? lastRequestedPlaceId;
  String? lastRequestedEvidence;

  @override
  Future<List<BusinessAccount>> getMyBusinessAccounts() async {
    if (errorToThrow != null) throw errorToThrow!;
    return accountsToReturn;
  }

  @override
  Future<BusinessAccount> createBusinessAccount({
    required String corporateName,
    required String taxId,
  }) async {
    if (errorToThrow != null) throw errorToThrow!;
    lastCreatedCorporateName = corporateName;
    lastCreatedTaxId = taxId;

    return BusinessAccount(
      id: 'acc-new-1',
      corporateName: corporateName,
      taxId: taxId,
      verificationStatus: 'PENDING',
      planTier: 'FREE',
      createdAt: DateTime.now(),
      updatedAt: DateTime.now(),
    );
  }

  @override
  Future<PlaceClaim> requestPlaceClaim({
    required String businessAccountId,
    required String placeId,
    required String evidenceDescription,
  }) async {
    if (errorToThrow != null) throw errorToThrow!;
    lastRequestedBusinessAccountId = businessAccountId;
    lastRequestedPlaceId = placeId;
    lastRequestedEvidence = evidenceDescription;

    return claimToReturn ??
        PlaceClaim(
          id: 'claim-new-1',
          businessAccountId: businessAccountId,
          corporateName: 'Empresa Teste',
          taxId: '12345678000199',
          placeId: placeId,
          placeName: 'Local Teste',
          city: 'São Paulo',
          state: 'SP',
          status: 'PENDING',
          evidenceDescription: evidenceDescription,
          createdAt: DateTime.now(),
        );
  }

  @override
  Future<List<PlaceClaim>> getBusinessPlaceClaims(
    String businessAccountId, {
    int page = 0,
    int size = 20,
    String? status,
  }) async {
    if (errorToThrow != null) throw errorToThrow!;
    return claimsToReturn[businessAccountId] ?? [];
  }
}

void main() {
  group('BusinessAccountsNotifier', () {
    late _FakeBusinessRepository repository;
    late BusinessAccountsNotifier notifier;

    setUp(() {
      repository = _FakeBusinessRepository();
      notifier = BusinessAccountsNotifier(repository: repository);
    });

    test('carrega contas comerciais e suas reivindicações com sucesso', () async {
      final account = BusinessAccount(
        id: 'acc-1',
        corporateName: 'Café do Ponto Ltda',
        taxId: '12345678000199',
        verificationStatus: 'APPROVED',
        planTier: 'FREE',
        createdAt: DateTime.now(),
        updatedAt: DateTime.now(),
      );
      final claim = PlaceClaim(
        id: 'claim-1',
        businessAccountId: 'acc-1',
        corporateName: 'Café do Ponto Ltda',
        taxId: '12345678000199',
        placeId: 'place-1',
        placeName: 'Cafeteria Paulista',
        city: 'São Paulo',
        state: 'SP',
        status: 'APPROVED',
        evidenceDescription: 'Contrato de locação comercial e alvará 2026.',
        createdAt: DateTime.now(),
      );

      repository.accountsToReturn = [account];
      repository.claimsToReturn = {
        'acc-1': [claim],
      };

      await notifier.loadMyAccounts();

      expect(notifier.accounts.length, 1);
      expect(notifier.accounts.first.corporateName, 'Café do Ponto Ltda');
      expect(notifier.claimsByAccount['acc-1']?.length, 1);
      expect(notifier.errorMessage, isNull);
    });

    test('cria nova conta comercial com sucesso', () async {
      final success = await notifier.createAccount(
        corporateName: 'Padaria Modelo Ltda',
        taxId: '98765432000188',
      );

      expect(success, isTrue);
      expect(repository.lastCreatedCorporateName, 'Padaria Modelo Ltda');
      expect(repository.lastCreatedTaxId, '98765432000188');
      expect(notifier.accounts.length, 1);
      expect(notifier.accounts.first.corporateName, 'Padaria Modelo Ltda');
      expect(notifier.errorMessage, isNull);
    });

    test('trata conflito de documento fiscal duplicado (409)', () async {
      repository.errorToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Conflict',
          status: 409,
          detail: 'Documento fiscal já cadastrado em outra conta comercial',
          code: 'BUSINESS_TAX_ID_ALREADY_EXISTS',
        ),
      );

      final success = await notifier.createAccount(
        corporateName: 'Empresa Duplicada',
        taxId: '12345678000199',
      );

      expect(success, isFalse);
      expect(notifier.errorMessage, contains('já está cadastrado'));
    });

    test('envia solicitação de reivindicação com sucesso', () async {
      final claim = await notifier.requestPlaceClaim(
        businessAccountId: 'acc-1',
        placeId: 'place-10',
        evidenceDescription: 'Somos proprietários do imóvel e do alvará de funcionamento.',
      );

      expect(claim, isNotNull);
      expect(repository.lastRequestedPlaceId, 'place-10');
      expect(notifier.claimsByAccount['acc-1']?.length, 1);
      expect(notifier.errorMessage, isNull);
    });

    test('trata erro de local já reivindicado (PLACE_ALREADY_CLAIMED)', () async {
      repository.errorToThrow = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Conflict',
          status: 409,
          detail: 'O local já está vinculado a uma conta comercial',
          code: 'PLACE_ALREADY_CLAIMED',
        ),
      );

      final claim = await notifier.requestPlaceClaim(
        businessAccountId: 'acc-1',
        placeId: 'place-10',
        evidenceDescription: 'Tentativa de reivindicação concorrente.',
      );

      expect(claim, isNull);
      expect(notifier.errorMessage, contains('já foi reivindicado'));
    });
  });

  group('MyBusinessAccountsScreen Widget Tests', () {
    late _FakeBusinessRepository repository;

    setUp(() {
      repository = _FakeBusinessRepository();
    });

    testWidgets('exibe estado vazio quando usuário não possui empresas', (tester) async {
      repository.accountsToReturn = [];

      await tester.pumpWidget(
        MaterialApp(
          home: MyBusinessAccountsScreen(repository: repository),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Minhas Empresas'), findsOneWidget);
      expect(find.text('Nenhuma empresa cadastrada'), findsOneWidget);
      expect(find.byKey(const Key('empty_add_business_button')), findsOneWidget);
    });

    testWidgets('lista empresas cadastradas com badge de verificação e solicitações', (tester) async {
      final account = BusinessAccount(
        id: 'acc-10',
        corporateName: 'Restaurante Central',
        taxId: '11222333000144',
        verificationStatus: 'APPROVED',
        planTier: 'FREE',
        createdAt: DateTime.now(),
        updatedAt: DateTime.now(),
      );
      final claim = PlaceClaim(
        id: 'claim-1',
        businessAccountId: 'acc-10',
        corporateName: 'Restaurante Central',
        taxId: '11222333000144',
        placeId: 'place-1',
        placeName: 'Cantina Italiana',
        city: 'Curitiba',
        state: 'PR',
        status: 'PENDING',
        evidenceDescription: 'Alvará municipal e conta de energia no endereço.',
        createdAt: DateTime.now(),
      );

      repository.accountsToReturn = [account];
      repository.claimsToReturn = {
        'acc-10': [claim],
      };

      await tester.pumpWidget(
        MaterialApp(
          home: MyBusinessAccountsScreen(repository: repository),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Restaurante Central'), findsOneWidget);
      expect(find.text('Documento: 11222333000144'), findsOneWidget);
      expect(find.text('Verificada'), findsOneWidget);
      expect(find.text('Plano: FREE'), findsOneWidget);
      expect(find.text('Cantina Italiana'), findsOneWidget);
      expect(find.text('Pendente'), findsOneWidget);
    });

    testWidgets('abre modal e valida campos ao cadastrar empresa', (tester) async {
      repository.accountsToReturn = [];

      await tester.pumpWidget(
        MaterialApp(
          home: MyBusinessAccountsScreen(repository: repository),
        ),
      );
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('add_business_account_fab')));
      await tester.pumpAndSettle();

      expect(
        find.descendant(
          of: find.byType(BottomSheet),
          matching: find.text('Cadastrar Empresa'),
        ),
        findsOneWidget,
      );

      // Tentar submeter vazio -> validação de campos obrigatórios
      await tester.tap(find.byKey(const Key('submit_create_account_button')));
      await tester.pumpAndSettle();

      expect(find.text('A razão social deve ter entre 2 e 255 caracteres.'), findsOneWidget);

      // Preencher dados válidos
      await tester.enterText(find.byKey(const Key('corporate_name_input')), 'Livraria Central Ltda');
      await tester.enterText(find.byKey(const Key('tax_id_input')), '12.345.678/0001-90');
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('submit_create_account_button')));
      await tester.pumpAndSettle();

      expect(repository.lastCreatedCorporateName, 'Livraria Central Ltda');
      expect(repository.lastCreatedTaxId, '12.345.678/0001-90');
    });
  });

  group('ClaimPlaceBottomSheet Widget Tests', () {
    late _FakeBusinessRepository repository;

    setUp(() {
      repository = _FakeBusinessRepository();
    });

    testWidgets('exibe aviso e botão de cadastro quando usuário não tem empresas', (tester) async {
      repository.accountsToReturn = [];

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Builder(
              builder: (context) => ElevatedButton(
                onPressed: () {
                  ClaimPlaceBottomSheet.show(
                    context,
                    placeId: 'place-99',
                    placeName: 'Café Paris',
                    city: 'São Paulo',
                    state: 'SP',
                    repository: repository,
                  );
                },
                child: const Text('Abrir'),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('Abrir'));
      await tester.pumpAndSettle();

      expect(find.text('Reivindicar Local'), findsOneWidget);
      expect(find.text('Café Paris (São Paulo/SP)'), findsOneWidget);
      expect(find.text('Você ainda não possui contas comerciais.'), findsOneWidget);
      expect(find.byKey(const Key('go_to_business_accounts_button')), findsOneWidget);
    });

    testWidgets('valida evidências entre 20 e 1000 caracteres e submete com sucesso', (tester) async {
      final account = BusinessAccount(
        id: 'acc-1',
        corporateName: 'Café Paris Ltda',
        taxId: '12345678000199',
        verificationStatus: 'PENDING',
        planTier: 'FREE',
        createdAt: DateTime.now(),
        updatedAt: DateTime.now(),
      );
      repository.accountsToReturn = [account];

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Builder(
              builder: (context) => ElevatedButton(
                onPressed: () {
                  ClaimPlaceBottomSheet.show(
                    context,
                    placeId: 'place-99',
                    placeName: 'Café Paris',
                    city: 'São Paulo',
                    state: 'SP',
                    repository: repository,
                  );
                },
                child: const Text('Abrir'),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('Abrir'));
      await tester.pumpAndSettle();

      expect(find.text('Selecione a Empresa Solicitante:'), findsOneWidget);
      final submitButton = find.byKey(const Key('submit_claim_button'));
      expect(tester.widget<ElevatedButton>(submitButton).enabled, isFalse);

      // Texto menor que 20 caracteres
      await tester.enterText(find.byKey(const Key('evidence_description_input')), 'Curto demais');
      await tester.pumpAndSettle();
      expect(tester.widget<ElevatedButton>(submitButton).enabled, isFalse);

      // Texto com mais de 20 caracteres
      await tester.enterText(
        find.byKey(const Key('evidence_description_input')),
        'Somos os legítimos proprietários do local conforme contrato anexo.',
      );
      await tester.pumpAndSettle();
      expect(tester.widget<ElevatedButton>(submitButton).enabled, isTrue);

      await tester.tap(submitButton);
      await tester.pumpAndSettle();

      expect(repository.lastRequestedBusinessAccountId, 'acc-1');
      expect(repository.lastRequestedPlaceId, 'place-99');
      expect(
        repository.lastRequestedEvidence,
        'Somos os legítimos proprietários do local conforme contrato anexo.',
      );
    });
  });
}
