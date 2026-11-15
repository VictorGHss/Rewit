import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_tokens.dart';
import 'package:rewit_mobile/features/auth/data/models/auth_user_dto.dart';
import 'package:rewit_mobile/features/auth/domain/entities/auth_state.dart';
import 'package:rewit_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:rewit_mobile/features/auth/domain/services/social_sign_in_service.dart';
import 'package:rewit_mobile/features/auth/presentation/state/auth_notifier.dart';
import 'package:rewit_mobile/features/review_creation/presentation/widgets/target_search_selector_widget.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

class _FakeAuthRepository implements AuthRepository {
  AuthUserDto currentUser = const AuthUserDto(
    id: 'user-1',
    email: 'user1@rewit.test',
    handle: 'user1',
    displayName: 'Usuário Um',
    isVerified: true,
    reputationScore: 100,
  );

  @override
  Future<bool> hasStoredSession() async => true;

  @override
  Future<AuthUserDto> getMe() async => currentUser;

  @override
  Future<Authenticated> login({
    required String email,
    required String password,
  }) async {
    return Authenticated(
      user: currentUser,
      tokens: const AuthTokens(accessToken: 'acc', refreshToken: 'ref'),
    );
  }

  @override
  Future<void> logout() async {}

  @override
  Future<Authenticated> register({
    required String displayName,
    required String handle,
    required String email,
    required String password,
  }) =>
      throw UnimplementedError();

  @override
  Future<String> requestSocialNonce() => throw UnimplementedError();

  @override
  Future<Authenticated> signInWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) =>
      throw UnimplementedError();

  @override
  Future<Authenticated> reactivateWithSocial({
    required SocialAuthProvider provider,
    required String idToken,
    required String nonce,
  }) =>
      throw UnimplementedError();

  @override
  Future<Authenticated> refreshTokens() => throw UnimplementedError();

  @override
  Future<Authenticated> reactivate({
    required String email,
    required String password,
  }) =>
      throw UnimplementedError();

  @override
  Future<void> deactivateAccount() async {}

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {}
}

class FakeSearchRepository implements SearchRepository {
  int callCount = 0;
  String? lastQuery;
  SearchPage Function(String query, int page, int size)? onSearch;
  Completer<SearchPage>? delayedCompleter;
  Exception? exceptionToThrow;

  @override
  Future<SearchPage> search({
    required String query,
    int page = 0,
    int size = 20,
  }) async {
    callCount++;
    lastQuery = query;

    if (exceptionToThrow != null) {
      throw exceptionToThrow!;
    }

    if (delayedCompleter != null) {
      return delayedCompleter!.future;
    }

    if (onSearch != null) {
      return onSearch!(query, page, size);
    }

    return const SearchPage(
      items: [],
      pageNumber: 0,
      pageSize: 20,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    );
  }
}

void main() {
  const placeItem = SearchResultItem(
    id: '11111111-1111-1111-1111-111111111111',
    name: 'Padaria Estrela',
    category: 'Panificação',
    targetType: TargetType.place,
    rawTargetType: 'PLACE',
    status: 'ACTIVE',
  );

  const productItem = SearchResultItem(
    id: '22222222-2222-2222-2222-222222222222',
    name: 'Pão de Queijo Mineiro',
    category: 'Lanches',
    targetType: TargetType.product,
    rawTargetType: 'PRODUCT',
    status: 'ACTIVE',
  );

  Widget buildSubject({
    required FakeSearchRepository repository,
    int targetIndex = 0,
    bool enabled = true,
    ValueChanged<SearchResultItem>? onTargetSelected,
    bool Function(String targetId)? isTargetSelectedElsewhere,
    ValueChanged<String>? onManualTargetIdEntered,
    Future<SearchResultItem?> Function()? onScanTarget,
    String? currentUserId,
    AuthNotifier? authNotifier,
  }) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(
        body: Padding(
          padding: const EdgeInsets.all(16.0),
          child: TargetSearchSelectorWidget(
            searchRepository: repository,
            targetIndex: targetIndex,
            enabled: enabled,
            onTargetSelected: onTargetSelected ?? (_) {},
            isTargetSelectedElsewhere: isTargetSelectedElsewhere,
            onManualTargetIdEntered: onManualTargetIdEntered,
            onScanTarget: onScanTarget,
            currentUserId: currentUserId,
            authNotifier: authNotifier,
          ),
        ),
      ),
    );
  }

  group('TargetSearchSelectorWidget Widget Tests', () {
    late FakeSearchRepository repository;

    setUp(() {
      repository = FakeSearchRepository();
    });

    testWidgets('exibe campo de busca inicial e instrução para digitar ao menos 2 caracteres', (tester) async {
      await tester.pumpWidget(buildSubject(repository: repository));

      expect(find.text('O que você quer avaliar? *'), findsOneWidget);
      expect(find.text('Digite o nome do estabelecimento ou produto...'), findsOneWidget);
      expect(find.text('Digite ao menos 2 caracteres para buscar locais e produtos.'), findsOneWidget);
      expect(find.text('Inserir UUID manualmente'), findsOneWidget);
    });

    testWidgets('aplica debounce de ~350ms antes de disparar busca', (tester) async {
      repository.onSearch = (q, p, s) => const SearchPage(
            items: [placeItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 1,
            totalPages: 1,
            isLast: true,
          );

      await tester.pumpWidget(buildSubject(repository: repository));

      // Digita termo
      await tester.enterText(find.byType(TextFormField), 'pad');
      await tester.pump(const Duration(milliseconds: 100));

      // Antes dos 350ms não chamou
      expect(repository.callCount, 0);

      // Avança mais 260ms (totalizando > 350ms)
      await tester.pump(const Duration(milliseconds: 260));
      await tester.pumpAndSettle();

      expect(repository.callCount, 1);
      expect(repository.lastQuery, 'pad');
      expect(find.text('Padaria Estrela'), findsOneWidget);
    });

    testWidgets('exibe resultados com distinção visual entre Local e Produto', (tester) async {
      repository.onSearch = (q, p, s) => const SearchPage(
            items: [placeItem, productItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 2,
            totalPages: 1,
            isLast: true,
          );

      await tester.pumpWidget(buildSubject(repository: repository));

      await tester.enterText(find.byType(TextFormField), 'padaria');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      // Local
      expect(find.text('Padaria Estrela'), findsOneWidget);
      expect(find.text('Local'), findsOneWidget);
      expect(find.text('• Panificação'), findsOneWidget);

      // Produto
      expect(find.text('Pão de Queijo Mineiro'), findsOneWidget);
      expect(find.text('Produto'), findsOneWidget);
      expect(find.text('• Lanches'), findsOneWidget);
    });

    testWidgets('seleciona alvo ao clicar no botão Selecionar ou no card', (tester) async {
      SearchResultItem? selected;
      repository.onSearch = (q, p, s) => const SearchPage(
            items: [placeItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 1,
            totalPages: 1,
            isLast: true,
          );

      await tester.pumpWidget(buildSubject(
        repository: repository,
        onTargetSelected: (item) {
          selected = item;
        },
      ));

      await tester.enterText(find.byType(TextFormField), 'padaria');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      // Clica em Selecionar
      await tester.tap(find.text('Selecionar'));
      await tester.pumpAndSettle();

      expect(selected, isNotNull);
      expect(selected?.id, placeItem.id);
      expect(selected?.name, 'Padaria Estrela');
    });

    testWidgets('impede seleção de alvos já adicionados (duplicados)', (tester) async {
      SearchResultItem? selected;
      repository.onSearch = (q, p, s) => const SearchPage(
            items: [placeItem, productItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 2,
            totalPages: 1,
            isLast: true,
          );

      await tester.pumpWidget(buildSubject(
        repository: repository,
        onTargetSelected: (item) {
          selected = item;
        },
        isTargetSelectedElsewhere: (id) => id == placeItem.id,
      ));

      await tester.enterText(find.byType(TextFormField), 'padaria');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      // Item place já está selecionado
      expect(find.text('Já adicionado'), findsOneWidget);

      // Clica no item duplicado
      await tester.tap(find.text('Padaria Estrela'));
      await tester.pump();

      // Callback não foi disparado
      expect(selected, isNull);
      expect(find.text('O alvo "Padaria Estrela" já foi adicionado a esta avaliação.'), findsOneWidget);

      // Clica no item disponível (produto)
      await tester.tap(find.text('Pão de Queijo Mineiro'));
      await tester.pumpAndSettle();

      expect(selected, isNotNull);
      expect(selected?.id, productItem.id);
    });

    testWidgets('utiliza cache em memória para termos de busca repetidos na mesma sessão', (tester) async {
      repository.onSearch = (q, p, s) => const SearchPage(
            items: [placeItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 1,
            totalPages: 1,
            isLast: true,
          );

      await tester.pumpWidget(buildSubject(repository: repository));

      // Primeira busca
      await tester.enterText(find.byType(TextFormField), 'padaria');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(repository.callCount, 1);

      // Apaga o texto
      await tester.enterText(find.byType(TextFormField), '');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      // Busca novamente o mesmo termo
      await tester.enterText(find.byType(TextFormField), 'padaria');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      // O contador de chamadas permanece 1 devido ao cache
      expect(repository.callCount, 1);
      expect(find.text('Padaria Estrela'), findsOneWidget);
    });

    testWidgets('exibe mensagem quando nenhum resultado é encontrado', (tester) async {
      repository.onSearch = (q, p, s) => const SearchPage(
            items: [],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 0,
            totalPages: 0,
            isLast: true,
          );

      await tester.pumpWidget(buildSubject(repository: repository));

      await tester.enterText(find.byType(TextFormField), 'inexistente');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(find.text('Nenhum resultado para "inexistente".'), findsOneWidget);
      expect(find.text('Verifique a digitação ou tente outro nome.'), findsOneWidget);
    });

    testWidgets('exibe banner de erro RFC 7807 e permite tentar novamente', (tester) async {
      repository.exceptionToThrow = const ApiException(
        ProblemDetail(
          type: 'https://api.rewit.app/errors/internal',
          title: 'Internal Server Error',
          status: 500,
          detail: 'Falha temporária no catálogo de busca.',
          code: 'SEARCH_INTERNAL_ERROR',
        ),
      );

      await tester.pumpWidget(buildSubject(repository: repository));

      await tester.enterText(find.byType(TextFormField), 'cafeteria');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(find.text('Falha temporária no catálogo de busca.'), findsOneWidget);
      expect(find.text('Tentar novamente'), findsOneWidget);

      // Limpa erro e simula sucesso na nova tentativa
      repository.exceptionToThrow = null;
      repository.onSearch = (q, p, s) => const SearchPage(
            items: [placeItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 1,
            totalPages: 1,
            isLast: true,
          );

      await tester.tap(find.text('Tentar novamente'));
      await tester.pumpAndSettle();

      expect(find.text('Padaria Estrela'), findsOneWidget);
    });

    testWidgets('permite alternar para inserção de UUID manualmente', (tester) async {
      String? enteredManualUuid;

      await tester.pumpWidget(buildSubject(
        repository: repository,
        onManualTargetIdEntered: (val) {
          enteredManualUuid = val;
        },
      ));

      // Clica no link para inserção manual
      await tester.tap(find.text('Inserir UUID manualmente'));
      await tester.pumpAndSettle();

      expect(find.text('Identificador do Alvo (UUID) *'), findsOneWidget);
      expect(find.text('Voltar para busca por nome'), findsOneWidget);

      // Preenche o UUID
      await tester.enterText(find.byType(TextFormField), '33333333-3333-3333-3333-333333333333');
      await tester.pump();

      expect(enteredManualUuid, '33333333-3333-3333-3333-333333333333');

      // Volta para busca
      await tester.tap(find.text('Voltar para busca por nome'));
      await tester.pumpAndSettle();

      expect(find.text('O que você quer avaliar? *'), findsOneWidget);
    });
  });

  group('TargetSearchSelectorWidget - Integração com Scanner (onScanTarget)', () {
    late FakeSearchRepository repository;

    setUp(() {
      repository = FakeSearchRepository();
    });

    testWidgets('botão de scanner fica visível apenas quando onScanTarget é fornecido', (tester) async {
      await tester.pumpWidget(buildSubject(repository: repository));
      expect(find.byKey(const Key('target_scanner_button_0')), findsNothing);
      expect(find.byIcon(Icons.qr_code_scanner), findsNothing);

      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          onScanTarget: () async => null,
        ),
      );

      final scannerBtn = find.byKey(const Key('target_scanner_button_0'));
      expect(scannerBtn, findsOneWidget);
      expect(find.byTooltip('Escanear QR, EAN ou ISBN'), findsOneWidget);
    });

    testWidgets('botão de scanner respeita enabled: false e não dispara callback', (tester) async {
      var callbackCalls = 0;

      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          enabled: false,
          onScanTarget: () async {
            callbackCalls++;
            return productItem;
          },
        ),
      );

      final buttonFinder = find.byKey(const Key('target_scanner_button_0'));
      expect(buttonFinder, findsOneWidget);
      final iconButton = tester.widget<IconButton>(buttonFinder);
      expect(iconButton.onPressed, isNull);

      await tester.tap(buttonFinder);
      await tester.pump();

      expect(callbackCalls, equals(0));
    });

    testWidgets('seleciona produto e lugar retornados pelo scanner', (tester) async {
      SearchResultItem? selectedProduct;
      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          targetIndex: 0,
          onScanTarget: () async => productItem,
          onTargetSelected: (item) => selectedProduct = item,
        ),
      );

      await tester.tap(find.byKey(const Key('target_scanner_button_0')));
      await tester.pumpAndSettle();
      expect(selectedProduct?.id, equals(productItem.id));
      expect(selectedProduct?.targetType, equals(TargetType.product));

      SearchResultItem? selectedPlace;
      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          targetIndex: 1,
          onScanTarget: () async => placeItem,
          onTargetSelected: (item) => selectedPlace = item,
        ),
      );

      await tester.tap(find.byKey(const Key('target_scanner_button_1')));
      await tester.pumpAndSettle();
      expect(selectedPlace?.id, equals(placeItem.id));
      expect(selectedPlace?.targetType, equals(TargetType.place));
    });

    testWidgets('bloqueia alvo duplicado retornado pelo scanner e exibe feedback equivalente', (tester) async {
      SearchResultItem? selectedItem;
      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          onScanTarget: () async => productItem,
          isTargetSelectedElsewhere: (id) => id == productItem.id,
          onTargetSelected: (item) => selectedItem = item,
        ),
      );

      await tester.tap(find.byKey(const Key('target_scanner_button_0')));
      await tester.pumpAndSettle();

      expect(selectedItem, isNull);
      expect(
        find.text('O alvo "${productItem.name}" já foi adicionado a esta avaliação.'),
        findsOneWidget,
      );
    });

    testWidgets('descarta retorno do scanner quando widget é desmontado durante await', (tester) async {
      final completer = Completer<SearchResultItem?>();
      SearchResultItem? selectedItem;

      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          onScanTarget: () => completer.future,
          onTargetSelected: (item) => selectedItem = item,
        ),
      );

      await tester.tap(find.byKey(const Key('target_scanner_button_0')));
      await tester.pump();

      // Desmonta o seletor antes do retorno do scanner
      await tester.pumpWidget(const MaterialApp(home: Scaffold(body: SizedBox.shrink())));
      completer.complete(productItem);
      await tester.pumpAndSettle();

      expect(selectedItem, isNull);
    });

    testWidgets('descarta retorno do scanner quando usuário atual muda durante await (MB-1a)', (tester) async {
      final completer = Completer<SearchResultItem?>();
      SearchResultItem? selectedItem;

      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          currentUserId: 'user-1',
          onScanTarget: () => completer.future,
          onTargetSelected: (item) => selectedItem = item,
        ),
      );

      await tester.tap(find.byKey(const Key('target_scanner_button_0')));
      await tester.pump();

      // Troca o usuário autenticado enquanto a tela de scanner está aberta
      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          currentUserId: 'user-2',
          onScanTarget: () => completer.future,
          onTargetSelected: (item) => selectedItem = item,
        ),
      );

      completer.complete(placeItem);
      await tester.pumpAndSettle();

      expect(selectedItem, isNull);
    });

    testWidgets('L-2: descarta retorno do scanner quando enabled passa de true para false durante await e libera lock', (tester) async {
      var completer = Completer<SearchResultItem?>();
      SearchResultItem? selectedItem;
      var scanCalls = 0;

      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          enabled: true,
          onScanTarget: () {
            scanCalls++;
            return completer.future;
          },
          onTargetSelected: (item) => selectedItem = item,
        ),
      );

      await tester.tap(find.byKey(const Key('target_scanner_button_0')));
      await tester.pump();
      expect(scanCalls, equals(1));

      // Widget é desabilitado enquanto a tela do scanner ainda está suspensa
      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          enabled: false,
          onScanTarget: () {
            scanCalls++;
            return completer.future;
          },
          onTargetSelected: (item) => selectedItem = item,
        ),
      );

      completer.complete(productItem);
      await tester.pumpAndSettle();

      // Seleção não pode ser propagada porque enabled == false no retorno
      expect(selectedItem, isNull);

      // Reabilitar o widget prova que o lock _isOpeningScanner foi liberado no finally
      completer = Completer<SearchResultItem?>();
      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          enabled: true,
          onScanTarget: () {
            scanCalls++;
            return completer.future;
          },
          onTargetSelected: (item) => selectedItem = item,
        ),
      );

      await tester.tap(find.byKey(const Key('target_scanner_button_0')));
      await tester.pump();
      expect(scanCalls, equals(2));

      completer.complete(productItem);
      await tester.pumpAndSettle();
      expect(selectedItem?.id, equals(productItem.id));
    });

    testWidgets('L-2: bloqueia toques concorrentes enquanto scanner está aberto e libera lock após cancelamento', (tester) async {
      var completer = Completer<SearchResultItem?>();
      var scanCalls = 0;
      SearchResultItem? selectedItem;

      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          onScanTarget: () {
            scanCalls++;
            return completer.future;
          },
          onTargetSelected: (item) => selectedItem = item,
        ),
      );

      await tester.tap(find.byKey(const Key('target_scanner_button_0')));
      await tester.pump();
      // Segundo toque imediato enquanto completer está pendente deve ser ignorado
      await tester.tap(find.byKey(const Key('target_scanner_button_0')));
      await tester.pump();
      expect(scanCalls, equals(1));

      // Usuário cancela o scanner (retorno null)
      completer.complete(null);
      await tester.pumpAndSettle();
      expect(selectedItem, isNull);

      // Lock foi liberado no finally: próximo toque abre normalmente
      completer = Completer<SearchResultItem?>();
      await tester.tap(find.byKey(const Key('target_scanner_button_0')));
      await tester.pump();
      expect(scanCalls, equals(2));
      completer.complete(placeItem);
      await tester.pumpAndSettle();
      expect(selectedItem?.id, equals(placeItem.id));
    });

    testWidgets('L-2: descarta SearchResultItem com id ou nome vazio retornado pelo scanner', (tester) async {
      SearchResultItem? selectedItem;

      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          onScanTarget: () async => const SearchResultItem(
            id: '   ',
            name: 'Nome Aparente',
            targetType: TargetType.product,
            rawTargetType: 'PRODUCT',
            status: 'ACTIVE',
          ),
          onTargetSelected: (item) => selectedItem = item,
        ),
      );

      await tester.tap(find.byKey(const Key('target_scanner_button_0')));
      await tester.pumpAndSettle();
      expect(selectedItem, isNull);

      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          onScanTarget: () async => const SearchResultItem(
            id: 'valid-id-123',
            name: '   ',
            targetType: TargetType.place,
            rawTargetType: 'PLACE',
            status: 'ACTIVE',
          ),
          onTargetSelected: (item) => selectedItem = item,
        ),
      );

      await tester.tap(find.byKey(const Key('target_scanner_button_0')));
      await tester.pumpAndSettle();
      expect(selectedItem, isNull);
    });

    testWidgets('M-1: troca de conta via AuthNotifier real descarta retorno do scanner (MB-1a)', (tester) async {
      final authRepo = _FakeAuthRepository();
      final authNotifier = AuthNotifier(authRepository: authRepo);
      addTearDown(authNotifier.dispose);

      await authNotifier.checkAuthStatus();
      expect((authNotifier.state as Authenticated).user.id, equals('user-1'));

      final completer = Completer<SearchResultItem?>();
      SearchResultItem? selectedItem;

      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          authNotifier: authNotifier,
          onScanTarget: () => completer.future,
          onTargetSelected: (item) => selectedItem = item,
        ),
      );

      await tester.tap(find.byKey(const Key('target_scanner_button_0')));
      await tester.pump();

      // Troca real de conta via AuthNotifier enquanto scanner está aberto (logout -> login outro usuário)
      await authNotifier.logout();
      authRepo.currentUser = const AuthUserDto(
        id: 'user-2',
        email: 'user2@rewit.test',
        handle: 'user2',
        displayName: 'Usuário Dois',
        isVerified: true,
        reputationScore: 50,
      );
      await authNotifier.login('user2@rewit.test', 'secret');
      await tester.pump();
      expect((authNotifier.state as Authenticated).user.id, equals('user-2'));

      completer.complete(placeItem);
      await tester.pumpAndSettle();

      expect(selectedItem, isNull);
    });

    testWidgets('L-B: normaliza id e nome com espaços nas bordas preservando case e demais campos', (tester) async {
      String? checkedDuplicateId;
      SearchResultItem? selectedItem;

      const untrimmedItem = SearchResultItem(
        id: '  Prod-MixedCase-UUID-42  ',
        name: '  Café Torrado Especial  ',
        slug: 'cafe-torrado-especial',
        category: 'BEBIDAS',
        targetType: TargetType.product,
        rawTargetType: 'PRODUCT',
        status: 'ACTIVE',
      );

      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          onScanTarget: () async => untrimmedItem,
          isTargetSelectedElsewhere: (id) {
            checkedDuplicateId = id;
            return false;
          },
          onTargetSelected: (item) => selectedItem = item,
        ),
      );

      await tester.tap(find.byKey(const Key('target_scanner_button_0')));
      await tester.pumpAndSettle();

      expect(checkedDuplicateId, equals('Prod-MixedCase-UUID-42'));
      expect(selectedItem, isNotNull);
      expect(selectedItem!.id, equals('Prod-MixedCase-UUID-42'));
      expect(selectedItem!.name, equals('Café Torrado Especial'));
      expect(selectedItem!.slug, equals('cafe-torrado-especial'));
      expect(selectedItem!.category, equals('BEBIDAS'));
      expect(selectedItem!.targetType, equals(TargetType.product));
      expect(selectedItem!.rawTargetType, equals('PRODUCT'));
      expect(selectedItem!.status, equals('ACTIVE'));
    });

    testWidgets('L-B: checagem de duplicidade usa ID já normalizado quando callback retorna espaços nas bordas', (tester) async {
      final duplicateCheckArgs = <String>[];
      SearchResultItem? selectedItem;

      await tester.pumpWidget(
        buildSubject(
          repository: repository,
          onScanTarget: () async => SearchResultItem(
            id: '  ${productItem.id}  ',
            name: '  ${productItem.name}  ',
            category: productItem.category,
            targetType: TargetType.product,
            rawTargetType: 'PRODUCT',
            status: 'ACTIVE',
          ),
          isTargetSelectedElsewhere: (id) {
            duplicateCheckArgs.add(id);
            return id == productItem.id;
          },
          onTargetSelected: (item) => selectedItem = item,
        ),
      );

      await tester.tap(find.byKey(const Key('target_scanner_button_0')));
      await tester.pumpAndSettle();

      expect(duplicateCheckArgs, equals([productItem.id]));
      expect(selectedItem, isNull);
      expect(
        find.text('O alvo "${productItem.name}" já foi adicionado a esta avaliação.'),
        findsOneWidget,
      );
    });
  });
}
