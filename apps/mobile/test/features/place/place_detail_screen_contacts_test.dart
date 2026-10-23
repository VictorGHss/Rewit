import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rewit_mobile/core/services/url_launcher_service.dart';
import 'package:rewit_mobile/features/place/domain/entities/place_detail.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_reviews_page.dart';
import 'package:rewit_mobile/features/place/domain/entities/target_stats.dart';
import 'package:rewit_mobile/features/place/domain/repositories/place_repository.dart';
import 'package:rewit_mobile/features/place/presentation/screens/place_detail_screen.dart';
import 'dart:typed_data';

class _FakeUrlLauncherService implements UrlLauncherService {
  String? lastLaunchedWebUrl;
  String? lastLaunchedPhone;

  @override
  Future<LaunchResult> launchWebUrl(String url) async {
    lastLaunchedWebUrl = url;
    return LaunchResult.success;
  }

  @override
  Future<LaunchResult> launchPhone(String phone) async {
    lastLaunchedPhone = phone;
    return LaunchResult.success;
  }
}

class _FakePlaceRepository implements PlaceRepository {
  PlaceDetail? placeToReturn;

  @override
  Future<PlaceDetail> getPlaceById(String id) async {
    return placeToReturn ??
        PlaceDetail(
          id: id,
          name: 'Empório Central',
          slug: 'emporio-central',
          category: 'ALIMENTACAO',
          description: 'Produtos artesanais',
          addressText: 'Rua das Palmeiras, 10',
          validationRadiusMeters: 50,
          origin: 'CLAIMED',
          isVerified: true,
          status: 'ACTIVE',
          phone: '+55 (11) 97777-6666',
          websiteUrl: 'https://emporiocentral.com.br',
          instagramUrl: 'https://instagram.com/emporiocentral',
          facebookUrl: 'https://facebook.com/emporiocentral',
        );
  }

  @override
  Future<TargetStats> getTargetStats(String id) async {
    return const TargetStats(
      targetId: 't-1',
      averageRating: 4.8,
      reviewsCount: 5,
    );
  }

  @override
  Future<TargetReviewsPage> getTargetReviews(
    String id, {
    int page = 0,
    int size = 10,
    String sort = 'newest',
    bool verifiedOnly = false,
  }) async {
    return const TargetReviewsPage(
      reviews: [],
      pageNumber: 0,
      pageSize: 10,
      totalElements: 0,
      totalPages: 0,
      isLast: true,
    );
  }

  @override
  Future<PlaceDetail> createPlace(CreatePlaceInput input) => throw UnimplementedError();

  @override
  Future<void> deletePlaceMedia({required String placeId, required String mediaId}) =>
      throw UnimplementedError();

  @override
  Future<List<PlaceMediaItem>> getPlaceMedia(String placeId) async => [];

  @override
  Future<PlaceMediaItem> setPrimaryPlaceMedia({required String placeId, required String mediaId}) =>
      throw UnimplementedError();

  @override
  Future<PlaceMediaItem> uploadPlaceMedia({
    required String placeId,
    required Uint8List fileBytes,
    required String filename,
    String? mimeType,
    String? idempotencyKey,
  }) =>
      throw UnimplementedError();

  @override
  Future<String> uploadPlacePhoto({
    required String placeId,
    required Uint8List fileBytes,
    required String filename,
    String? idempotencyKey,
  }) =>
      throw UnimplementedError();
}

void main() {
  group('PlaceDetailScreen - Contatos Comerciais Públicos (F2.5)', () {
    late _FakePlaceRepository repository;
    late _FakeUrlLauncherService launcher;

    setUp(() {
      repository = _FakePlaceRepository();
      launcher = _FakeUrlLauncherService();
    });

    Widget createScreen(String placeId) {
      return MaterialApp(
        home: PlaceDetailScreen(
          placeId: placeId,
          repository: repository,
          urlLauncherService: launcher,
        ),
      );
    }

    testWidgets('exibe seção de contatos comerciais quando estabelecimento possui dados', (tester) async {
      await tester.pumpWidget(createScreen('place-with-contacts'));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('place_public_contacts_section')), findsOneWidget);
      expect(find.text('Contatos e Redes Sociais'), findsOneWidget);
      expect(find.byKey(const Key('place_contact_phone_item')), findsOneWidget);
      expect(find.text('+55 (11) 97777-6666'), findsOneWidget);

      await tester.tap(find.byKey(const Key('place_contact_phone_item')));
      await tester.pumpAndSettle();
      expect(launcher.lastLaunchedPhone, '+55 (11) 97777-6666');
    });

    testWidgets('não exibe seção de contatos quando dados são omitidos pelo backend', (tester) async {
      repository.placeToReturn = const PlaceDetail(
        id: 'place-no-contacts',
        name: 'Local Comum',
        slug: 'local-comum',
        category: 'GERAL',
        addressText: 'Rua Um, 1',
        validationRadiusMeters: 50,
        origin: 'USER',
        isVerified: false,
        status: 'ACTIVE',
        phone: null,
        websiteUrl: null,
        instagramUrl: null,
        facebookUrl: null,
      );

      await tester.pumpWidget(createScreen('place-no-contacts'));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('place_public_contacts_section')), findsNothing);
      expect(find.text('Contatos e Redes Sociais'), findsNothing);
      // Confirma que informações do local continuam presentes
      expect(find.text('Informações do Local'), findsOneWidget);
      expect(find.text('Rua Um, 1'), findsOneWidget);
    });

    testWidgets('recarregamento atualiza seção de contatos de acordo com novo estado do backend', (tester) async {
      repository.placeToReturn = const PlaceDetail(
        id: 'place-dynamic',
        name: 'Local Dinâmico',
        slug: 'local-dinamico',
        category: 'GERAL',
        addressText: 'Rua Dois, 2',
        validationRadiusMeters: 50,
        origin: 'CLAIMED',
        isVerified: true,
        status: 'ACTIVE',
        phone: null,
      );

      await tester.pumpWidget(createScreen('place-dynamic'));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('place_public_contacts_section')), findsNothing);

      // Backend agora retorna telefone configurado após aprovação/edição
      repository.placeToReturn = const PlaceDetail(
        id: 'place-dynamic',
        name: 'Local Dinâmico',
        slug: 'local-dinamico',
        category: 'GERAL',
        addressText: 'Rua Dois, 2',
        validationRadiusMeters: 50,
        origin: 'CLAIMED',
        isVerified: true,
        status: 'ACTIVE',
        phone: '+55 11 98888-7777',
      );

      // Dispara pull-to-refresh
      await tester.fling(find.byType(ListView), const Offset(0, 300), 1000);
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('place_public_contacts_section')), findsOneWidget);
      expect(find.text('+55 11 98888-7777'), findsOneWidget);
    });
  });
}
