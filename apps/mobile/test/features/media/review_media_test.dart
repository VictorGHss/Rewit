import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:rewit_mobile/app/theme/app_theme.dart';
import 'package:rewit_mobile/core/error/api_exception.dart';
import 'package:rewit_mobile/core/network/http_client.dart';
import 'package:rewit_mobile/features/feed/domain/entities/feed_entities.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/review_creation_input.dart';
import 'package:rewit_mobile/features/review_creation/domain/entities/selected_media_item.dart';
import 'package:rewit_mobile/features/review_creation/domain/repositories/review_creation_repository.dart';
import 'package:rewit_mobile/features/review_creation/domain/services/media_picker_service.dart';
import 'package:rewit_mobile/features/review_creation/presentation/screens/review_create_screen.dart';
import 'package:rewit_mobile/features/review_creation/presentation/state/review_create_notifier.dart';
import 'package:rewit_mobile/features/review_creation/presentation/state/review_create_state.dart';
import 'package:rewit_mobile/features/review_detail/data/repositories/review_media_repository_impl.dart';
import 'package:rewit_mobile/features/review_detail/domain/entities/review_media.dart';
import 'package:rewit_mobile/features/review_detail/domain/repositories/review_media_repository.dart';
import 'package:rewit_mobile/features/review_detail/presentation/screens/review_detail_screen.dart';
import 'package:rewit_mobile/features/review_detail/presentation/widgets/authenticated_image.dart';

/// 1x1 PNG válido para testes de renderização com Image.memory
final Uint8List kTestPngBytes = Uint8List.fromList(<int>[
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
  0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
  0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
  0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
  0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
  0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00,
  0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
  0x42, 0x60, 0x82,
]);

class MockMediaPickerService implements MediaPickerService {
  List<SelectedMediaItem> itemsToReturn = [];

  @override
  Future<List<SelectedMediaItem>> pickImages({int maxImages = 5}) async {
    return itemsToReturn.take(maxImages).toList();
  }
}

class MockReviewCreationRepository implements ReviewCreationRepository {
  CreateReviewInput? capturedInput;
  FeedReview? mockResult;

  @override
  Future<FeedReview> createReview(CreateReviewInput input) async {
    capturedInput = input;
    return mockResult ??
        FeedReview(
          id: 'test-review-uuid-1',
          author: const FeedAuthor(id: 'author-1', displayName: 'Autor Autor'),
          visibility: input.visibility,
          status: 'ACTIVE',
          createdAt: DateTime.now(),
          targets: [
            FeedTarget(
              id: 't-1',
              targetId: input.targets.first.rateableTargetId,
              rating: input.targets.first.rating,
            ),
          ],
        );
  }
}

class MockReviewMediaRepository implements ReviewMediaRepository {
  List<ReviewMediaItem> mediaItems = [];
  Map<String, Uint8List> bytesMap = {};
  bool shouldThrowOnUpload = false;
  ApiException? apiExceptionToThrowOnUpload;
  bool shouldThrowOnDelete = false;
  bool shouldThrowOnGetMedia = false;
  bool shouldThrowOnGetBytes = false;
  final List<String> deletedMediaIds = [];
  final List<String> uploadedFilenames = [];

  @override
  Future<List<ReviewMediaItem>> getReviewMedia(String reviewId) async {
    if (shouldThrowOnGetMedia) {
      throw Exception('Falha ao obter mídias');
    }
    return mediaItems;
  }

  @override
  Future<ReviewMediaItem> uploadMedia({
    required String reviewId,
    required Uint8List bytes,
    required String filename,
    String? mimeType,
  }) async {
    if (shouldThrowOnUpload) {
      if (apiExceptionToThrowOnUpload != null) {
        throw apiExceptionToThrowOnUpload!;
      }
      throw Exception('Erro de conexão ao enviar mídia');
    }
    uploadedFilenames.add(filename);
    final item = ReviewMediaItem(
      id: 'media-${uploadedFilenames.length}',
      reviewId: reviewId,
      url: '/api/v1/reviews/$reviewId/media/media-${uploadedFilenames.length}',
      mediaType: 'IMAGE',
      mimeType: mimeType ?? 'image/jpeg',
      sizeBytes: bytes.length,
      status: 'ACTIVE',
      createdAt: DateTime.now(),
    );
    mediaItems.add(item);
    bytesMap[item.url] = bytes;
    return item;
  }

  @override
  Future<void> deleteMedia({
    required String reviewId,
    required String mediaId,
  }) async {
    if (shouldThrowOnDelete) {
      throw Exception('Erro ao deletar');
    }
    deletedMediaIds.add(mediaId);
    mediaItems.removeWhere((item) => item.id == mediaId);
  }

  @override
  Future<Uint8List> getMediaBytes(String pathOrUrl) async {
    if (shouldThrowOnGetBytes) {
      throw Exception('Falha ao baixar imagem');
    }
    return bytesMap[pathOrUrl] ?? kTestPngBytes;
  }
}

void main() {
  group('1. SelectedMediaItem - Entidade e Validações Locais', () {
    test('valida tamanho máximo de 10 MB e formatação correta', () {
      final validItem = SelectedMediaItem(
        id: '1',
        name: 'foto.jpg',
        sizeBytes: 5 * 1024 * 1024, // 5 MB
        mimeType: 'image/jpeg',
        bytes: kTestPngBytes,
      );
      expect(validItem.isSizeValid, isTrue);
      expect(validItem.formattedSize, '5.00 MB');

      final oversizedItem = SelectedMediaItem(
        id: '2',
        name: 'foto_grande.jpg',
        sizeBytes: 11 * 1024 * 1024, // 11 MB
        mimeType: 'image/jpeg',
        bytes: Uint8List(0),
      );
      expect(oversizedItem.isSizeValid, isFalse);

      final zeroItem = SelectedMediaItem(
        id: '3',
        name: 'vazio.jpg',
        sizeBytes: 0,
        mimeType: 'image/jpeg',
        bytes: Uint8List(0),
      );
      expect(zeroItem.isSizeValid, isFalse);
      expect(zeroItem.formattedSize, '0 B');
    });

    test('valida formatos permitidos (JPEG e PNG apenas)', () {
      final jpegItem = SelectedMediaItem(
        id: '1',
        name: 'imagem.jpg',
        sizeBytes: 1024,
        mimeType: 'image/jpeg',
        bytes: kTestPngBytes,
      );
      expect(jpegItem.isFormatValid, isTrue);

      final pngItem = SelectedMediaItem(
        id: '2',
        name: 'imagem.png',
        sizeBytes: 1024,
        mimeType: 'image/png',
        bytes: kTestPngBytes,
      );
      expect(pngItem.isFormatValid, isTrue);

      final invalidPdf = SelectedMediaItem(
        id: '3',
        name: 'documento.pdf',
        sizeBytes: 1024,
        mimeType: 'application/pdf',
        bytes: Uint8List(0),
      );
      expect(invalidPdf.isFormatValid, isFalse);

      final invalidSvg = SelectedMediaItem(
        id: '4',
        name: 'icone.svg',
        sizeBytes: 1024,
        mimeType: 'image/svg+xml',
        bytes: Uint8List(0),
      );
      expect(invalidSvg.isFormatValid, isFalse);
    });
  });

  group('2. ReviewMediaRepositoryImpl - Testes com Mock HTTP Client', () {
    const baseUrl = 'http://api.rewit.test';

    test('getReviewMedia decodifica lista de imagens ativas da avaliação', () async {
      final mockClient = MockClient((request) async {
        expect(request.method, 'GET');
        expect(request.url.path, '/api/v1/reviews/rev-1/media');
        return http.Response(
          jsonEncode([
            {
              'id': 'med-1',
              'reviewId': 'rev-1',
              'url': '/api/v1/reviews/rev-1/media/med-1',
              'mediaType': 'IMAGE',
              'mimeType': 'image/jpeg',
              'sizeBytes': 2048,
              'width': 800,
              'height': 600,
              'status': 'ACTIVE',
              'createdAt': '2026-10-07T12:00:00Z',
            }
          ]),
          200,
          headers: {'content-type': 'application/json'},
        );
      });

      final httpClient = RewitHttpClient(baseUrl: baseUrl, client: mockClient);
      final repo = ReviewMediaRepositoryImpl(client: httpClient);

      final list = await repo.getReviewMedia('rev-1');
      expect(list.length, 1);
      expect(list.first.id, 'med-1');
      expect(list.first.sizeBytes, 2048);
      expect(list.first.mimeType, 'image/jpeg');
    });

    test('uploadMedia envia multipart/form-data com campo "file" e retorna 201', () async {
      final mockClient = MockClient((request) async {
        expect(request.method, 'POST');
        expect(request.url.path, '/api/v1/reviews/rev-1/media');
        expect(request.headers['accept'], 'application/json');
        return http.Response(
          jsonEncode({
            'id': 'med-created-1',
            'reviewId': 'rev-1',
            'url': '/api/v1/reviews/rev-1/media/med-created-1',
            'mediaType': 'IMAGE',
            'mimeType': 'image/png',
            'sizeBytes': kTestPngBytes.length,
            'status': 'ACTIVE',
            'createdAt': '2026-10-07T12:00:00Z',
          }),
          201,
          headers: {'content-type': 'application/json'},
        );
      });

      final httpClient = RewitHttpClient(baseUrl: baseUrl, client: mockClient);
      final repo = ReviewMediaRepositoryImpl(client: httpClient);

      final uploaded = await repo.uploadMedia(
        reviewId: 'rev-1',
        bytes: kTestPngBytes,
        filename: 'foto.png',
        mimeType: 'image/png',
      );

      expect(uploaded.id, 'med-created-1');
      expect(uploaded.mimeType, 'image/png');
    });

    test('deleteMedia envia DELETE e espera 204 No Content', () async {
      final mockClient = MockClient((request) async {
        expect(request.method, 'DELETE');
        expect(request.url.path, '/api/v1/reviews/rev-1/media/med-1');
        return http.Response('', 204);
      });

      final httpClient = RewitHttpClient(baseUrl: baseUrl, client: mockClient);
      final repo = ReviewMediaRepositoryImpl(client: httpClient);

      await expectLater(
        repo.deleteMedia(reviewId: 'rev-1', mediaId: 'med-1'),
        completes,
      );
    });
  });

  group('3. ReviewCreateNotifier - Upload Sequencial e Tratamento de Erros de Mídia', () {
    late MockReviewCreationRepository creationRepo;
    late MockReviewMediaRepository mediaRepo;
    late MockMediaPickerService pickerService;
    late ReviewCreateNotifier notifier;

    setUp(() {
      creationRepo = MockReviewCreationRepository();
      mediaRepo = MockReviewMediaRepository();
      pickerService = MockMediaPickerService();
      notifier = ReviewCreateNotifier(
        repository: creationRepo,
        mediaRepository: mediaRepo,
        mediaPickerService: pickerService,
      );
      notifier.updateTarget(0, targetId: '11111111-1111-1111-1111-111111111111', rating: 5.0);
    });

    test('adiciona e remove mídias respeitando o limite máximo de 5', () async {
      expect(notifier.selectedMedia.length, 0);

      for (int i = 0; i < 5; i++) {
        notifier.addSelectedMediaItem(
          SelectedMediaItem(
            id: 'm-$i',
            name: 'foto_$i.jpg',
            sizeBytes: 1000,
            mimeType: 'image/jpeg',
            bytes: kTestPngBytes,
          ),
        );
      }
      expect(notifier.selectedMedia.length, 5);

      // Tentativa de adicionar 6ª mídia é ignorada
      notifier.addSelectedMediaItem(
        SelectedMediaItem(
          id: 'm-6',
          name: 'extra.jpg',
          sizeBytes: 1000,
          mimeType: 'image/jpeg',
          bytes: kTestPngBytes,
        ),
      );
      expect(notifier.selectedMedia.length, 5);

      // Remove uma mídia
      notifier.removeSelectedMediaItem(0);
      expect(notifier.selectedMedia.length, 4);
    });

    test('envio de avaliação com mídias executa upload sequencial com sucesso', () async {
      notifier.addSelectedMediaItem(
        SelectedMediaItem(
          id: '1',
          name: 'foto1.jpg',
          sizeBytes: 1000,
          mimeType: 'image/jpeg',
          bytes: kTestPngBytes,
        ),
      );
      notifier.addSelectedMediaItem(
        SelectedMediaItem(
          id: '2',
          name: 'foto2.png',
          sizeBytes: 2000,
          mimeType: 'image/png',
          bytes: kTestPngBytes,
        ),
      );

      final success = await notifier.submit();
      expect(success, isTrue);
      expect(notifier.state, isA<ReviewCreateSuccess>());
      final state = notifier.state as ReviewCreateSuccess;
      expect(state.uploadedMediaCount, 2);
      expect(state.failedMediaCount, 0);
      expect(state.hasMediaFailures, isFalse);
      expect(mediaRepo.uploadedFilenames, ['foto1.jpg', 'foto2.png']);
    });

    test('REGRA UX: avaliação é criada mesmo quando o upload de mídia falha', () async {
      mediaRepo.shouldThrowOnUpload = true;

      notifier.addSelectedMediaItem(
        SelectedMediaItem(
          id: '1',
          name: 'foto1.jpg',
          sizeBytes: 1000,
          mimeType: 'image/jpeg',
          bytes: kTestPngBytes,
        ),
      );

      final success = await notifier.submit();
      // Avaliação FOI criada no backend! Não houve rollback!
      expect(success, isTrue);
      expect(notifier.state, isA<ReviewCreateSuccess>());
      final state = notifier.state as ReviewCreateSuccess;
      expect(state.uploadedMediaCount, 0);
      expect(state.failedMediaCount, 1);
      expect(state.hasMediaFailures, isTrue);
      expect(state.createdReview.id, 'test-review-uuid-1');

      // Status da mídia selecionada ficou como failed
      expect(notifier.selectedMedia.first.status, MediaUploadStatus.failed);
      expect(notifier.selectedMedia.first.isTerminalError, isFalse);
    });

    test('rejeita auto-retry para erros terminais (413 MEDIA_SIZE_EXCEEDED, 415)', () async {
      mediaRepo.shouldThrowOnUpload = true;
      mediaRepo.apiExceptionToThrowOnUpload = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Payload Too Large',
          status: 413,
          code: 'MEDIA_SIZE_EXCEEDED',
          detail: 'Arquivo excede 10 MB',
        ),
      );

      notifier.addSelectedMediaItem(
        SelectedMediaItem(
          id: '1',
          name: 'foto.jpg',
          sizeBytes: 1000,
          mimeType: 'image/jpeg',
          bytes: kTestPngBytes,
        ),
      );

      await notifier.submit();
      final item = notifier.selectedMedia.first;
      expect(item.status, MediaUploadStatus.failed);
      expect(item.errorCode, 'MEDIA_SIZE_EXCEEDED');
      expect(item.isTerminalError, isTrue);

      // retryMediaUpload deve recusar tentar novamente erro terminal
      final retryResult = await notifier.retryMediaUpload(0, 'test-review-uuid-1');
      expect(retryResult, isFalse);
    });

    test('permite retry explícito para erro temporário (429 RATE_LIMIT_EXCEEDED)', () async {
      mediaRepo.shouldThrowOnUpload = true;
      mediaRepo.apiExceptionToThrowOnUpload = const ApiException(
        ProblemDetail(
          type: 'about:blank',
          title: 'Too Many Requests',
          status: 429,
          code: 'RATE_LIMIT_EXCEEDED',
          detail: 'Limite de taxa excedido',
        ),
        retryAfterSeconds: 60,
      );

      notifier.addSelectedMediaItem(
        SelectedMediaItem(
          id: '1',
          name: 'foto.jpg',
          sizeBytes: 1000,
          mimeType: 'image/jpeg',
          bytes: kTestPngBytes,
        ),
      );

      await notifier.submit();
      expect(notifier.selectedMedia.first.status, MediaUploadStatus.failed);
      expect(notifier.selectedMedia.first.errorCode, 'RATE_LIMIT_EXCEEDED');
      expect(notifier.selectedMedia.first.retryAfterSeconds, 60);
      expect(notifier.selectedMedia.first.isTerminalError, isFalse);

      // Agora a rede normaliza
      mediaRepo.shouldThrowOnUpload = false;
      mediaRepo.apiExceptionToThrowOnUpload = null;

      final retryResult = await notifier.retryMediaUpload(0, 'test-review-uuid-1');
      expect(retryResult, isTrue);
      expect(notifier.selectedMedia.first.status, MediaUploadStatus.uploaded);
    });
  });

  group('4. AuthenticatedImage - Widget Tests', () {
    testWidgets('exibe CircularProgressIndicator durante carregamento e Image.memory ao concluir',
        (tester) async {
      final mediaRepo = MockReviewMediaRepository();
      mediaRepo.bytesMap['/media/1'] = kTestPngBytes;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AuthenticatedImage(
              url: '/media/1',
              mediaRepository: mediaRepo,
              width: 100,
              height: 100,
            ),
          ),
        ),
      );

      // Inicialmente carregando
      expect(find.byType(CircularProgressIndicator), findsOneWidget);

      await tester.pumpAndSettle();

      // Ao concluir, renderiza Image
      expect(find.byType(Image), findsOneWidget);
      expect(find.byType(CircularProgressIndicator), findsNothing);
    });

    testWidgets('exibe ícone de erro e botão de retry se download falhar', (tester) async {
      final mediaRepo = MockReviewMediaRepository();
      mediaRepo.shouldThrowOnGetBytes = true;

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AuthenticatedImage(
              url: '/media/fail',
              mediaRepository: mediaRepo,
            ),
          ),
        ),
      );

      await tester.pumpAndSettle();

      expect(find.byIcon(Icons.broken_image_outlined), findsOneWidget);
      expect(find.byIcon(Icons.refresh), findsOneWidget);

      // Clica para retentar após erro ser corrigido
      mediaRepo.shouldThrowOnGetBytes = false;
      await tester.tap(find.byIcon(Icons.refresh));
      await tester.pumpAndSettle();

      expect(find.byType(Image), findsOneWidget);
      expect(find.byIcon(Icons.broken_image_outlined), findsNothing);
    });
  });

  group('5. ReviewDetailScreen - Galeria de Mídias e Exclusão pelo Autor', () {
    testWidgets('autor vê botão de exclusão e confirma remoção da foto', (tester) async {
      final mediaRepo = MockReviewMediaRepository();
      mediaRepo.mediaItems = [
        ReviewMediaItem(
          id: 'med-autor-1',
          reviewId: 'rev-1',
          url: '/media/1',
          mediaType: 'IMAGE',
          mimeType: 'image/jpeg',
          sizeBytes: 15000,
          status: 'ACTIVE',
          createdAt: DateTime(2026, 10, 7),
        ),
      ];

      final review = FeedReview(
        id: 'rev-1',
        author: const FeedAuthor(id: 'me-user-uuid', displayName: 'Eu Mesmo'),
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        createdAt: DateTime(2026, 10, 7),
        targets: const [],
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReviewDetailScreen(
            reviewId: 'rev-1',
            initialReview: review,
            mediaRepository: mediaRepo,
            currentUserId: 'me-user-uuid', // Usuário é o autor!
          ),
        ),
      );

      await tester.pumpAndSettle();

      // Galeria exibe fotos e o botão de exclusão está visível para o autor
      expect(find.text('Fotos e Anexos (1)'), findsOneWidget);
      expect(find.byKey(const ValueKey('delete_media_med-autor-1')), findsOneWidget);

      // Toca no botão de exclusão
      await tester.tap(find.byKey(const ValueKey('delete_media_med-autor-1')));
      await tester.pumpAndSettle();

      // Diálogo de confirmação aparece
      expect(find.text('Excluir Foto'), findsOneWidget);
      expect(find.text('Deseja realmente remover esta foto da avaliação? Esta ação não pode ser desfeita.'),
          findsOneWidget);

      // Confirma exclusão
      await tester.tap(find.text('Excluir'));
      await tester.pumpAndSettle();

      // Repositório foi chamado e item foi removido da tela
      expect(mediaRepo.deletedMediaIds, ['med-autor-1']);
      expect(find.text('Fotos e Anexos (1)'), findsNothing);
      expect(find.text('Foto removida com sucesso!'), findsOneWidget);
    });

    testWidgets('terceiros (não-autores) NÃO veem botão de exclusão de fotos', (tester) async {
      final mediaRepo = MockReviewMediaRepository();
      mediaRepo.mediaItems = [
        ReviewMediaItem(
          id: 'med-outro-1',
          reviewId: 'rev-1',
          url: '/media/1',
          mediaType: 'IMAGE',
          mimeType: 'image/jpeg',
          sizeBytes: 15000,
          status: 'ACTIVE',
          createdAt: DateTime(2026, 10, 7),
        ),
      ];

      final review = FeedReview(
        id: 'rev-1',
        author: const FeedAuthor(id: 'outro-user-uuid', displayName: 'Outro Autor'),
        visibility: 'PUBLIC',
        status: 'ACTIVE',
        createdAt: DateTime(2026, 10, 7),
        targets: const [],
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReviewDetailScreen(
            reviewId: 'rev-1',
            initialReview: review,
            mediaRepository: mediaRepo,
            currentUserId: 'me-visitor-uuid', // Não é o autor!
          ),
        ),
      );

      await tester.pumpAndSettle();

      // Galeria é exibida, mas botão de exclusão NÃO existe
      expect(find.text('Fotos e Anexos (1)'), findsOneWidget);
      expect(find.byKey(const ValueKey('delete_media_med-outro-1')), findsNothing);
      expect(find.byIcon(Icons.delete_outline), findsNothing);
    });
  });

  group('6. ReviewCreateScreen com Mídia - Interação Widget Tests', () {
    testWidgets('seleciona fotos da galeria, exibe miniaturas e permite remoção', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);

      final creationRepo = MockReviewCreationRepository();
      final mediaRepo = MockReviewMediaRepository();
      final picker = MockMediaPickerService();
      picker.itemsToReturn = [
        SelectedMediaItem(
          id: 'pick-1',
          name: 'minha_foto.jpg',
          sizeBytes: 500 * 1024,
          mimeType: 'image/jpeg',
          bytes: kTestPngBytes,
        ),
      ];

      final notifier = ReviewCreateNotifier(
        repository: creationRepo,
        mediaRepository: mediaRepo,
        mediaPickerService: picker,
      );

      await tester.pumpWidget(
        MaterialApp(
          theme: AppTheme.lightTheme,
          home: ReviewCreateScreen(
            repository: creationRepo,
            mediaRepository: mediaRepo,
            mediaPickerService: picker,
            notifier: notifier,
          ),
        ),
      );

      await tester.pumpAndSettle();

      // Inicialmente 0/5
      expect(find.text('0/5'), findsOneWidget);
      expect(find.text('Adicionar Fotos (Galeria)'), findsOneWidget);

      // Clica para adicionar fotos
      await tester.tap(find.byKey(const ValueKey('add_media_button')));
      await tester.pumpAndSettle();

      // Exibe 1/5 e o tamanho da foto
      expect(find.text('1/5'), findsOneWidget);
      expect(find.text('500.0 KB'), findsOneWidget);
      expect(find.byIcon(Icons.close), findsOneWidget);

      // Clica para remover foto
      await tester.tap(find.byIcon(Icons.close));
      await tester.pumpAndSettle();

      // Volta a 0/5
      expect(find.text('0/5'), findsOneWidget);
    });
  });
}
