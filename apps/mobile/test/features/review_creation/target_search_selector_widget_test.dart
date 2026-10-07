import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/review_creation/presentation/widgets/target_search_selector_widget.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';

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
    ValueChanged<SearchResultItem>? onTargetSelected,
    bool Function(String targetId)? isTargetSelectedElsewhere,
    ValueChanged<String>? onManualTargetIdEntered,
  }) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      home: Scaffold(
        body: Padding(
          padding: const EdgeInsets.all(16.0),
          child: TargetSearchSelectorWidget(
            searchRepository: repository,
            targetIndex: 0,
            onTargetSelected: onTargetSelected ?? (_) {},
            isTargetSelectedElsewhere: isTargetSelectedElsewhere,
            onManualTargetIdEntered: onManualTargetIdEntered,
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
}
