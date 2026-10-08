import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/app/router/app_router.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';
import 'package:rewit_mobile/features/search/presentation/screens/search_screen.dart';
import 'package:rewit_mobile/features/search/presentation/state/search_notifier.dart';

class StubSearchRepository implements SearchRepository {
  int callCount = 0;
  String? lastQuery;
  int? lastPage;
  int? lastSize;

  Future<SearchPage> Function(String query, int page, int size)? onSearch;

  @override
  Future<SearchPage> search({
    required String query,
    int page = 0,
    int size = 20,
  }) async {
    callCount++;
    lastQuery = query;
    lastPage = page;
    lastSize = size;

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
  Widget buildSubject({
    required SearchRepository repository,
    SearchNotifier? notifier,
    void Function(SearchResultItem item)? onTargetTap,
  }) {
    return MaterialApp(
      theme: AppTheme.lightTheme,
      home: SearchScreen(
        searchRepository: repository,
        searchNotifier: notifier,
        onTargetTap: onTargetTap,
      ),
    );
  }

  group('SearchScreen Widget Tests', () {
    late StubSearchRepository repository;

    const placeItem = SearchResultItem(
      id: 'place-1',
      name: 'Padaria Bella Vista',
      slug: 'padaria-bella-vista',
      category: 'Padaria',
      targetType: TargetType.place,
      rawTargetType: 'PLACE',
      status: 'ACTIVE',
    );

    const productItem = SearchResultItem(
      id: 'product-1',
      name: 'Croissant Tradicional',
      slug: 'croissant-tradicional',
      category: 'Pães',
      targetType: TargetType.product,
      rawTargetType: 'PRODUCT',
      status: 'ACTIVE',
    );

    setUp(() {
      repository = StubSearchRepository();
    });

    testWidgets('1. renderiza estado inicial com campo de busca, chips e orientações', (tester) async {
      await tester.pumpWidget(buildSubject(repository: repository));

      expect(find.text('Buscar no Catálogo'), findsOneWidget);
      expect(find.byType(TextFormField), findsOneWidget);
      expect(find.text('Todos'), findsOneWidget);
      expect(find.text('Locais'), findsOneWidget);
      expect(find.text('Produtos'), findsOneWidget);
      expect(find.text('Busca de Lugares'), findsOneWidget);
      expect(find.text('Digite ao menos 2 caracteres para pesquisar locais ou produtos.'), findsOneWidget);
    });

    testWidgets('2. digitação menor que 2 caracteres não dispara requisição', (tester) async {
      await tester.pumpWidget(buildSubject(repository: repository));

      await tester.enterText(find.byType(TextFormField), 'a');
      await tester.pump(const Duration(milliseconds: 400));

      expect(repository.callCount, equals(0));
      expect(find.text('Busca de Lugares'), findsOneWidget);
    });

    testWidgets('3. debounce dispara busca e exibe resultados de Local e Produto com badges corretos', (tester) async {
      repository.onSearch = (q, p, s) async => const SearchPage(
            items: [placeItem, productItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 2,
            totalPages: 1,
            isLast: true,
          );

      await tester.pumpWidget(buildSubject(repository: repository));

      await tester.enterText(find.byType(TextFormField), 'padaria');
      // Aguarda o debounce de 350ms
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(repository.callCount, equals(1));
      expect(find.text('Padaria Bella Vista'), findsOneWidget);
      expect(find.text('Croissant Tradicional'), findsOneWidget);
      expect(find.text('Local'), findsOneWidget);
      expect(find.text('Produto'), findsOneWidget);
      expect(find.byIcon(Icons.storefront_rounded), findsWidgets);
      expect(find.byIcon(Icons.inventory_2_outlined), findsWidgets);
    });

    testWidgets('4. alternância de chips filtra visualmente resultados carregados', (tester) async {
      repository.onSearch = (q, p, s) async => const SearchPage(
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

      // Inicialmente "Todos" exibe ambos
      expect(find.text('Padaria Bella Vista'), findsOneWidget);
      expect(find.text('Croissant Tradicional'), findsOneWidget);

      // Filtra por "Locais"
      await tester.tap(find.text('Locais'));
      await tester.pumpAndSettle();

      expect(find.text('Padaria Bella Vista'), findsOneWidget);
      expect(find.text('Croissant Tradicional'), findsNothing);

      // Filtra por "Produtos"
      await tester.tap(find.text('Produtos'));
      await tester.pumpAndSettle();

      expect(find.text('Padaria Bella Vista'), findsNothing);
      expect(find.text('Croissant Tradicional'), findsOneWidget);

      // Volta para "Todos"
      await tester.tap(find.text('Todos'));
      await tester.pumpAndSettle();

      expect(find.text('Padaria Bella Vista'), findsOneWidget);
      expect(find.text('Croissant Tradicional'), findsOneWidget);
    });

    testWidgets('5. botão de limpar apaga o texto e retorna ao estado inicial', (tester) async {
      repository.onSearch = (q, p, s) async => const SearchPage(
            items: [placeItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 1,
            totalPages: 1,
            isLast: true,
          );

      await tester.pumpWidget(buildSubject(repository: repository));

      await tester.enterText(find.byType(TextFormField), 'padaria');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(find.text('Padaria Bella Vista'), findsOneWidget);

      // Toca no botão de limpar (Clear icon)
      await tester.tap(find.byIcon(Icons.clear));
      await tester.pumpAndSettle();

      expect(find.text('Busca de Lugares'), findsOneWidget);
      expect(find.text('Padaria Bella Vista'), findsNothing);
    });

    testWidgets('6. exibe empty state com mensagem amigável quando nenhum item for retornado', (tester) async {
      repository.onSearch = (q, p, s) async => const SearchPage(
            items: [],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 0,
            totalPages: 0,
            isLast: true,
          );

      await tester.pumpWidget(buildSubject(repository: repository));

      await tester.enterText(find.byType(TextFormField), 'termoinexistente');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(find.text('Nenhum resultado encontrado'), findsOneWidget);
      expect(
        find.textContaining('Nenhum item encontrado para "termoinexistente"'),
        findsOneWidget,
      );
    });

    testWidgets('7. exibe erro e permite tentar novamente via botão', (tester) async {
      bool fail = true;
      repository.onSearch = (q, p, s) async {
        if (fail) {
          fail = false;
          throw const NetworkException('Servidor indisponível.');
        }
        return const SearchPage(
          items: [placeItem],
          pageNumber: 0,
          pageSize: 20,
          totalElements: 1,
          totalPages: 1,
          isLast: true,
        );
      };

      await tester.pumpWidget(buildSubject(repository: repository));

      await tester.enterText(find.byType(TextFormField), 'padaria');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(find.text('Não foi possível concluir a busca'), findsOneWidget);
      expect(find.text('Servidor indisponível.'), findsOneWidget);

      // Toca em Tentar novamente
      await tester.tap(find.text('Tentar novamente'));
      await tester.pumpAndSettle();

      expect(find.text('Padaria Bella Vista'), findsOneWidget);
    });

    testWidgets('8. scroll infinito dispara loadMore e exibe footer de erro/retry se falhar', (tester) async {
      // 1ª página com 15 itens
      final initialItems = List.generate(
        15,
        (i) => SearchResultItem(
          id: 'place-$i',
          name: 'Local Número $i',
          category: 'Categoria',
          targetType: TargetType.place,
          rawTargetType: 'PLACE',
          status: 'ACTIVE',
        ),
      );

      repository.onSearch = (q, page, size) async {
        if (page == 0) {
          return SearchPage(
            items: initialItems,
            pageNumber: 0,
            pageSize: 15,
            totalElements: 30,
            totalPages: 2,
            isLast: false,
          );
        } else {
          throw const NetworkException('Falha ao carregar página 2.');
        }
      };

      await tester.pumpWidget(buildSubject(repository: repository));

      await tester.enterText(find.byType(TextFormField), 'local');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      expect(find.text('Local Número 0'), findsOneWidget);
      expect(repository.callCount, equals(1));

      // Rola a lista até o fim
      await tester.drag(find.byType(ListView), const Offset(0, -1000));
      await tester.pumpAndSettle();

      expect(repository.callCount, equals(2));
      expect(find.text('Falha ao carregar página 2.'), findsOneWidget);
      expect(find.text('Tentar novamente'), findsOneWidget);
    });

    testWidgets('9. toque no card aciona callback onTargetTap', (tester) async {
      SearchResultItem? tappedItem;
      repository.onSearch = (q, p, s) async => const SearchPage(
            items: [placeItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 1,
            totalPages: 1,
            isLast: true,
          );

      await tester.pumpWidget(buildSubject(
        repository: repository,
        onTargetTap: (item) {
          tappedItem = item;
        },
      ));

      await tester.enterText(find.byType(TextFormField), 'padaria');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Padaria Bella Vista'));
      await tester.pumpAndSettle();

      expect(tappedItem, isNotNull);
      expect(tappedItem!.id, equals('place-1'));
    });

    testWidgets('10. toque em item PLACE sem onTargetTap navega para AppRouter.placeDetail', (tester) async {
      String? navigatedPlaceId;
      repository.onSearch = (q, p, s) async => const SearchPage(
            items: [placeItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 1,
            totalPages: 1,
            isLast: true,
          );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: SearchScreen(searchRepository: repository),
          routes: {
            AppRouter.placeDetail: (context) {
              navigatedPlaceId = ModalRoute.of(context)?.settings.arguments as String?;
              return const Scaffold(body: Text('PlaceDetailScreen Destino'));
            },
          },
        ),
      );

      await tester.enterText(find.byType(TextFormField), 'padaria');
      await tester.pump(const Duration(milliseconds: 400));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Padaria Bella Vista'));
      await tester.pumpAndSettle();

      expect(find.text('PlaceDetailScreen Destino'), findsOneWidget);
      expect(navigatedPlaceId, equals('place-1'));
    });
  });
}
