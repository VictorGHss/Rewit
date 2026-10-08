import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_entities.dart';
import 'package:rewit_mobile/features/search/domain/entities/search_filter.dart';
import 'package:rewit_mobile/features/search/domain/repositories/search_repository.dart';
import 'package:rewit_mobile/features/search/presentation/state/search_notifier.dart';
import 'package:rewit_mobile/features/search/presentation/state/search_state.dart';

class MockSearchRepository implements SearchRepository {
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
  group('SearchNotifier & SearchState Tests', () {
    late MockSearchRepository repository;
    late SearchNotifier notifier;

    const placeItem = SearchResultItem(
      id: 'place-1',
      name: 'Café Central',
      slug: 'cafe-central',
      category: 'Cafeteria',
      targetType: TargetType.place,
      rawTargetType: 'PLACE',
      status: 'ACTIVE',
    );

    const productItem = SearchResultItem(
      id: 'product-1',
      name: 'Café Especial Grãos',
      slug: 'cafe-especial-graos',
      category: 'Bebidas',
      targetType: TargetType.product,
      rawTargetType: 'PRODUCT',
      status: 'ACTIVE',
    );

    setUp(() {
      repository = MockSearchRepository();
      notifier = SearchNotifier(
        searchRepository: repository,
        debounceDuration: const Duration(milliseconds: 50),
      );
    });

    tearDown(() {
      notifier.dispose();
    });

    test('1. estado inicial é SearchInitial com campos vazios', () {
      expect(notifier.state, isA<SearchInitial>());
      expect(notifier.state.query, isEmpty);
      expect(notifier.state.filter, equals(SearchFilter.all));
    });

    test('2. query com menos de 2 caracteres não dispara requisição e permanece em SearchInitial', () async {
      notifier.onQueryChanged('a');
      await Future<void>.delayed(const Duration(milliseconds: 80));

      expect(notifier.state, isA<SearchInitial>());
      expect(repository.callCount, equals(0));

      notifier.onQueryChanged('   ');
      await Future<void>.delayed(const Duration(milliseconds: 80));

      expect(notifier.state, isA<SearchInitial>());
      expect(repository.callCount, equals(0));
    });

    test('3. debounce agrupa múltiplas digitações e dispara somente a última', () async {
      repository.onSearch = (q, p, s) async => const SearchPage(
            items: [placeItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 1,
            totalPages: 1,
            isLast: true,
          );

      notifier.onQueryChanged('ca');
      notifier.onQueryChanged('caf');
      notifier.onQueryChanged('cafe');

      await Future<void>.delayed(const Duration(milliseconds: 80));

      expect(repository.callCount, equals(1));
      expect(repository.lastQuery, equals('cafe'));
      expect(notifier.state, isA<SearchResults>());
    });

    test('4. busca com resultados emite SearchLoading e depois SearchResults', () async {
      repository.onSearch = (q, p, s) async => const SearchPage(
            items: [placeItem, productItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 2,
            totalPages: 1,
            isLast: true,
          );

      final future = notifier.search('café');
      expect(notifier.state, isA<SearchLoading>());

      await future;

      expect(notifier.state, isA<SearchResults>());
      final results = notifier.state as SearchResults;
      expect(results.allItems.length, equals(2));
      expect(results.totalElements, equals(2));
      expect(results.isLast, isTrue);
      expect(results.page, equals(0));
    });

    test('5. busca vazia emite SearchEmpty quando lista de itens é vazia', () async {
      repository.onSearch = (q, p, s) async => const SearchPage(
            items: [],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 0,
            totalPages: 0,
            isLast: true,
          );

      await notifier.search('inexistente');

      expect(notifier.state, isA<SearchEmpty>());
      expect((notifier.state as SearchEmpty).query, equals('inexistente'));
    });

    test('6. erro RFC 7807 (ApiException) emite SearchError com mensagem e retryAfterSeconds', () async {
      repository.onSearch = (q, p, s) async => throw const ApiException(
            ProblemDetail(
              type: 'about:blank',
              status: 429,
              title: 'Too Many Requests',
              detail: 'Limite de requisições excedido.',
            ),
            retryAfterSeconds: 30,
          );

      await notifier.search('palavra');

      expect(notifier.state, isA<SearchError>());
      final err = notifier.state as SearchError;
      expect(err.message, equals('Limite de requisições excedido.'));
      expect(err.retryAfterSeconds, equals(30));
    });

    test('7. erro de rede (NetworkException) emite SearchError', () async {
      repository.onSearch = (q, p, s) async => throw const NetworkException(
            'Falha de conexão com a internet.',
          );

      await notifier.search('palavra');

      expect(notifier.state, isA<SearchError>());
      final err = notifier.state as SearchError;
      expect(err.message, equals('Falha de conexão com a internet.'));
    });

    test('8. retry() no SearchError reexecuta a busca e recupera resultado', () async {
      bool failOnce = true;
      repository.onSearch = (q, p, s) async {
        if (failOnce) {
          failOnce = false;
          throw const NetworkException('Timeout');
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

      await notifier.search('teste');
      expect(notifier.state, isA<SearchError>());

      notifier.retry();
      await Future<void>.delayed(const Duration(milliseconds: 10));

      expect(notifier.state, isA<SearchResults>());
      expect((notifier.state as SearchResults).allItems.length, equals(1));
    });

    test('9. paginação (loadMore) acumula novos itens e deduplica', () async {
      repository.onSearch = (q, page, size) async {
        if (page == 0) {
          return const SearchPage(
            items: [placeItem],
            pageNumber: 0,
            pageSize: 1,
            totalElements: 2,
            totalPages: 2,
            isLast: false,
          );
        } else {
          return const SearchPage(
            items: [placeItem, productItem], // placeItem repetido para testar deduplicação
            pageNumber: 1,
            pageSize: 1,
            totalElements: 2,
            totalPages: 2,
            isLast: true,
          );
        }
      };

      await notifier.search('cafe');
      final firstResults = notifier.state as SearchResults;
      expect(firstResults.allItems.length, equals(1));
      expect(firstResults.isLast, isFalse);

      await notifier.loadMore();

      final secondResults = notifier.state as SearchResults;
      expect(secondResults.page, equals(1));
      expect(secondResults.allItems.length, equals(2)); // placeItem + productItem (sem duplicata)
      expect(secondResults.isLast, isTrue);
      expect(secondResults.isLoadingMore, isFalse);
    });

    test('10. loadMore() é ignorado se isLast == true ou se já estiver carregando', () async {
      repository.onSearch = (q, page, size) async => const SearchPage(
            items: [placeItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 1,
            totalPages: 1,
            isLast: true,
          );

      await notifier.search('cafe');
      final callsBefore = repository.callCount;

      await notifier.loadMore();
      expect(repository.callCount, equals(callsBefore));
    });

    test('11. erro no loadMore() preserva itens existentes e registra loadMoreError', () async {
      repository.onSearch = (q, page, size) async {
        if (page == 0) {
          return const SearchPage(
            items: [placeItem],
            pageNumber: 0,
            pageSize: 1,
            totalElements: 2,
            totalPages: 2,
            isLast: false,
          );
        } else {
          throw const NetworkException('Falha na página 2');
        }
      };

      await notifier.search('cafe');
      await notifier.loadMore();

      expect(notifier.state, isA<SearchResults>());
      final results = notifier.state as SearchResults;
      expect(results.allItems.length, equals(1)); // itens preservados!
      expect(results.loadMoreError, equals('Falha na página 2'));
      expect(results.isLoadingMore, isFalse);
    });

    test('12. cache de sessão evita requisições redundantes para a mesma query', () async {
      repository.onSearch = (q, p, s) async => const SearchPage(
            items: [placeItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 1,
            totalPages: 1,
            isLast: true,
          );

      await notifier.search('padaria');
      expect(repository.callCount, equals(1));

      // Mesma query com espaços ou caixa diferente deve atingir o cache em memória
      await notifier.search('  PADARIA  ');
      expect(repository.callCount, equals(1)); // não chamou o repositório novamente!
      expect(notifier.state, isA<SearchResults>());
    });

    test('13. setFilter() filtra visualmente entre Todos, Locais e Produtos', () async {
      repository.onSearch = (q, p, s) async => const SearchPage(
            items: [placeItem, productItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 2,
            totalPages: 1,
            isLast: true,
          );

      await notifier.search('cafe');
      final results = notifier.state as SearchResults;

      // Filtro Todos
      expect(results.filteredItems.length, equals(2));

      // Filtro Locais
      notifier.setFilter(SearchFilter.places);
      final placesResults = notifier.state as SearchResults;
      expect(placesResults.filter, equals(SearchFilter.places));
      expect(placesResults.filteredItems.length, equals(1));
      expect(placesResults.filteredItems.first.isPlace, isTrue);

      // Filtro Produtos
      notifier.setFilter(SearchFilter.products);
      final productsResults = notifier.state as SearchResults;
      expect(productsResults.filter, equals(SearchFilter.products));
      expect(productsResults.filteredItems.length, equals(1));
      expect(productsResults.filteredItems.first.isProduct, isTrue);
    });

    test('14. resposta atrasada de busca anterior é descartada (stale response protection)', () async {
      repository.onSearch = (q, p, s) async {
        if (q == 'lenta') {
          await Future<void>.delayed(const Duration(milliseconds: 60));
          return const SearchPage(
            items: [placeItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 1,
            totalPages: 1,
            isLast: true,
          );
        } else {
          return const SearchPage(
            items: [productItem],
            pageNumber: 0,
            pageSize: 20,
            totalElements: 1,
            totalPages: 1,
            isLast: true,
          );
        }
      };

      // Dispara busca lenta
      final slowFuture = notifier.search('lenta');
      // Imediatamente dispara busca rápida
      final fastFuture = notifier.search('rapida');

      await Future.wait([slowFuture, fastFuture]);

      // O estado final deve refletir a busca rápida ('rapida'), e não a lenta atrasada
      expect(notifier.state, isA<SearchResults>());
      final finalResults = notifier.state as SearchResults;
      expect(finalResults.query, equals('rapida'));
      expect(finalResults.allItems.first.id, equals('product-1'));
    });

    test('15. clear() cancela timers e redefine estado para SearchInitial', () {
      notifier.onQueryChanged('teste');
      notifier.clear();

      expect(notifier.state, isA<SearchInitial>());
      expect(notifier.state.query, isEmpty);
    });
  });
}
